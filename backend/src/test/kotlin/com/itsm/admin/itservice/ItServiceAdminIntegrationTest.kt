package com.itsm.admin.itservice

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.AdminApiTestSupport.Companion.unique
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Role
import com.itsm.common.spi.ServiceCatalog
import com.itsm.user.UserRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * IT service administration on a real PostgreSQL 16 (Flyway V1..V12, Hibernate validation, V12's cycle trigger).
 * Every test uses unique service names; rows persist between tests. Required to execute in CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ItServiceAdminIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/services"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var service: ItServiceAdminService
    @Autowired lateinit var catalog: ServiceCatalog

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN)
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun MockHttpServletRequestBuilder.json(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))

    private fun call(request: MockHttpServletRequestBuilder, caller: Caller? = admin): ResultActions =
        mvc.perform(if (caller == null) request else request.header("Authorization", caller.bearer))

    private fun body(name: String = unique("Service "), criticality: Any? = 3, team: String? = "Infrastructure",
                     deps: List<Long?> = emptyList()) =
        mapOf("name" to name, "description" to "Does useful things", "businessCriticality" to criticality,
            "ownerTeam" to team, "dependsOnServiceIds" to deps)

    private fun create(name: String = unique("Service "), criticality: Int = 3, team: String? = "Infrastructure",
                       deps: List<Long> = emptyList()): Long =
        t.body(call(post(BASE).json(body(name, criticality, team, deps))).andExpect(status().isCreated))["id"].asLong()

    private fun read(id: Long) = t.body(call(get("$BASE/$id")).andExpect(status().isOk))

    private fun updateBody(id: Long, deps: List<Long?>? = null, name: String? = null, criticality: Int? = null): Map<String, Any?> {
        val current = read(id)
        return mapOf(
            "name" to (name ?: current["name"].asText()),
            "description" to current["description"].asText(),
            "businessCriticality" to (criticality ?: current["businessCriticality"].asInt()),
            "ownerTeam" to current["ownerTeam"].asText(),
            "dependsOnServiceIds" to (deps ?: current["dependsOn"].map { it["id"].asLong() }),
            "version" to current["version"].asLong()
        )
    }

    private fun update(id: Long, deps: List<Long?>? = null, name: String? = null, criticality: Int? = null) =
        call(put("$BASE/$id").json(updateBody(id, deps, name, criticality)))

    // --- authorization ---------------------------------------------------------------------------------------------

    @Test fun `anonymous callers get 401 and EMPLOYEE or TECHNICIAN get 403 on every endpoint, with no side effects`() {
        val id = create()
        val auditBefore = t.auditCount()
        val requests = listOf(
            { get(BASE) }, { get("$BASE/$id") }, { post(BASE).json(body()) },
            { put("$BASE/$id").json(body() + ("version" to 0)) },
            { post("$BASE/$id/activate") }, { post("$BASE/$id/deactivate") }
        )
        for (request in requests) {
            call(request(), caller = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            for (role in listOf(Role.EMPLOYEE, Role.TECHNICIAN)) {
                call(request(), t.caller(role)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }
        assertEquals(auditBefore, t.auditCount())
        assertTrue(read(id)["active"].asBoolean())
    }

    // --- create and dependencies -----------------------------------------------------------------------------------

    @Test fun `ADMIN creates a service with dependencies, both sides of the edge are visible and audited`() {
        val core = create(name = unique("Core "), criticality = 5)
        val name = unique("  Mail   ")
        val result = call(post(BASE).json(body(name = name, criticality = 4, team = "  Applications ", deps = listOf(core))))
            .andExpect(status().isCreated)
        val created = t.body(result)
        val id = created["id"].asLong()
        result.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("$BASE/$id")))
        assertEquals(name.trim().replace(Regex("\\s+"), " "), created["name"].asText())
        assertEquals("Applications", created["ownerTeam"].asText())
        assertEquals(4, created["businessCriticality"].asInt())
        assertEquals(listOf(core), created["dependsOn"].map { it["id"].asLong() })
        assertTrue(created["dependsOn"][0]["active"].asBoolean())
        assertEquals(listOf(id), read(core)["dependents"].map { it["id"].asLong() })

        val audit = t.auditRows("IT_SERVICE", id).single()
        assertEquals("IT_SERVICE_CREATED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals("ADMIN", audit.actorRole)
        assertEquals(listOf(core), audit.metadata["dependsOnServiceIds"].map { it.asLong() })
        assertEquals(4, audit.metadata["businessCriticality"].asInt())
    }

    @Test fun `duplicate service names are rejected ignoring case, and write no audit row`() {
        val name = unique("Wifi ")
        create(name = name)
        val auditBefore = t.auditCount()
        call(post(BASE).json(body(name = name.lowercase()))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        assertEquals(auditBefore, t.auditCount())
    }

    @Test fun `criticality outside 1-5 or not a number is a 400`() {
        for (bad in listOf(0, 6, null)) {
            call(post(BASE).json(body(criticality = bad))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("businessCriticality"))
        }
        call(post(BASE).json(body(criticality = "HIGH"))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        call(get(BASE).param("criticality", "9")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("criticality"))
    }

    @Test fun `duplicate, self, null and unknown dependencies are 400s`() {
        val dep = create()
        call(post(BASE).json(body(deps = listOf(dep, dep)))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.errors[0].field").value("dependsOnServiceIds"))
        call(post(BASE).json(body(deps = listOf(null)))).andExpect(status().isBadRequest)
        call(post(BASE).json(body(deps = listOf(999_999L)))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].message").value(containsString("999999")))

        val id = create()
        update(id, deps = listOf(id)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("dependsOnServiceIds"))
        assertTrue(read(id)["dependsOn"].isEmpty)
    }

    @Test fun `an active service cannot depend on an inactive one`() {
        val retired = create()
        call(post("$BASE/$retired/deactivate")).andExpect(status().isOk)
        call(post(BASE).json(body(deps = listOf(retired)))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
        val id = create()
        update(id, deps = listOf(retired)).andExpect(status().isConflict)
    }

    @Test fun `a dependency cycle is a 409 that names the path, and the database trigger is the backstop`() {
        val a = create(name = unique("Alpha "))
        val b = create(name = unique("Beta "), deps = listOf(a))
        val c = create(name = unique("Gamma "), deps = listOf(b))
        val names = listOf(a, c, b, a).map { read(it)["name"].asText() }

        update(a, deps = listOf(c)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.detail").value(containsString(names.joinToString(" -> "))))
        assertTrue(read(a)["dependsOn"].isEmpty)

        val ex = assertThrows<DataAccessException> {
            jdbc.update("insert into service_dependencies (service_id, depends_on_id) values (?, ?)", a, c)
        }
        assertTrue(ex.mostSpecificCause.message!!.contains("would create a cycle"))
    }

    // --- update ----------------------------------------------------------------------------------------------------

    @Test fun `changing fields and dependencies together publishes an update event and a dependency event`() {
        val old = create(); val new = create()
        val id = create(deps = listOf(old), criticality = 2)
        val newName = unique("Moved ")
        val updated = t.body(update(id, deps = listOf(new), name = newName, criticality = 5).andExpect(status().isOk))
        assertEquals(listOf(new), updated["dependsOn"].map { it["id"].asLong() })
        assertEquals(1, updated["version"].asLong())
        assertTrue(read(old)["dependents"].isEmpty)

        val audit = t.auditRows("IT_SERVICE", id)
        assertEquals(listOf("IT_SERVICE_CREATED", "IT_SERVICE_UPDATED", "IT_SERVICE_DEPENDENCIES_CHANGED"), audit.map { it.action })
        assertEquals(listOf("name", "businessCriticality"), audit[1].metadata["changedFields"].map { it.asText() })
        assertEquals(2, audit[1].metadata["businessCriticalityBefore"].asInt())
        assertEquals(5, audit[1].metadata["businessCriticalityAfter"].asInt())
        assertEquals(listOf(new), audit[2].metadata["addedDependencyIds"].map { it.asLong() })
        assertEquals(listOf(old), audit[2].metadata["removedDependencyIds"].map { it.asLong() })
    }

    @Test fun `stale versions are 409 and unchanged updates are silent no-ops`() {
        val id = create()
        val stale = updateBody(id)
        update(id, name = unique("Fresh ")).andExpect(status().isOk)
        call(put("$BASE/$id").json(stale)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))

        val auditBefore = t.auditCount()
        update(id).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        assertEquals(auditBefore, t.auditCount())
    }

    @Test fun `unknown services are 404 SERVICE_NOT_FOUND`() {
        for (request in listOf(get("$BASE/999999"), post("$BASE/999999/activate"), post("$BASE/999999/deactivate"),
                               put("$BASE/999999").json(body() + ("version" to 0)))) {
            call(request).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
        }
    }

    // --- activation ------------------------------------------------------------------------------------------------

    @Test fun `a service with active dependents cannot be deactivated, and a dependent cannot be activated over an inactive dependency`() {
        val base = create(name = unique("Base "))
        val dependent = create(name = unique("Dependent "), deps = listOf(base))
        val dependentName = read(dependent)["name"].asText()

        call(post("$BASE/$base/deactivate")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value(containsString(dependentName)))

        call(post("$BASE/$dependent/deactivate")).andExpect(status().isOk)
        call(post("$BASE/$base/deactivate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        call(post("$BASE/$dependent/activate")).andExpect(status().isConflict)

        call(post("$BASE/$base/activate")).andExpect(status().isOk)
        call(post("$BASE/$dependent/activate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(true))
        val audit = t.auditRows("IT_SERVICE", base)
        assertEquals(listOf("IT_SERVICE_CREATED", "IT_SERVICE_DEACTIVATED", "IT_SERVICE_ACTIVATED"), audit.map { it.action })
        assertEquals("ACTIVE" to "INACTIVE", audit[1].previousState to audit[1].newState)
    }

    // --- transactions ----------------------------------------------------------------------------------------------

    @Test fun `a rolled-back transaction leaves neither the service, its edges nor its audit rows`() {
        val dep = create()
        val name = unique("Rollback ")
        val auditBefore = t.auditCount()
        tx.executeWithoutResult { status ->
            t.asCaller(admin) { service.create(CreateServiceRequest(name, null, 3, null, listOf(dep))) }
            status.setRollbackOnly()
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from it_services where name = ?", Long::class.java, name))
        assertTrue(catalog.dependentsOf(dep).isEmpty())
        assertEquals(auditBefore, t.auditCount())
    }

    // --- list ------------------------------------------------------------------------------------------------------

    @Test fun `list filters by search, status, criticality and owner team, and sorts by criticality`() {
        val token = unique("qx")
        val team = unique("Team ")
        val low = create(name = "Low $token", criticality = 1, team = team)
        val high = create(name = "High $token", criticality = 5, team = team)
        val other = create(name = "Other $token", criticality = 5, team = "Elsewhere")
        call(post("$BASE/$other/deactivate")).andExpect(status().isOk)

        fun ids(vararg params: Pair<String, String>): List<Long> {
            val request = get(BASE).param("size", "100")
            params.forEach { request.param(it.first, it.second) }
            return t.body(call(request).andExpect(status().isOk))["items"].map { it["id"].asLong() }
        }
        assertEquals(setOf(low, high, other), ids("search" to token.uppercase()).toSet())
        assertEquals(listOf(high, low), ids("ownerTeam" to team.lowercase(), "sort" to "businessCriticality", "dir" to "desc"))
        assertEquals(listOf(high), ids("search" to token, "criticality" to "5", "active" to "true"))
        assertEquals(listOf(other), ids("search" to token, "active" to "false"))
        assertEquals(setOf(low, high), ids("search" to team).toSet(), "search also matches the owner team")
    }

    // --- ServiceCatalog SPI ----------------------------------------------------------------------------------------

    @Test fun `ServiceCatalog returns immutable views with direct edges only`() {
        val a = create(criticality = 5)
        val b = create(deps = listOf(a))
        val c = create(deps = listOf(b))

        val viewC = catalog.findById(c)!!
        assertEquals(setOf(b), viewC.dependsOnServiceIds)
        assertEquals(setOf(b), catalog.dependentsOf(a), "dependentsOf is direct: c depends on a only transitively")
        assertEquals(setOf(c), catalog.dependentsOf(b))
        assertTrue(catalog.dependentsOf(999_999).isEmpty())
        assertEquals(5, catalog.requireActive(a).businessCriticality)
        assertTrue(catalog.listActive().map { it.id }.containsAll(listOf(a, b, c)))
        assertThrows<UnsupportedOperationException> { (viewC.dependsOnServiceIds as MutableSet<Long>).add(1) }

        call(post("$BASE/$c/deactivate")).andExpect(status().isOk)
        assertFalse(catalog.findById(c)!!.active)
        assertEquals(setOf(c), catalog.dependentsOf(b), "inactive dependents are still reverse edges")
        assertTrue(catalog.listActive().none { it.id == c })
        assertThrows<InvalidInput> { catalog.requireActive(c) }
        assertNull(catalog.findById(999_999))
        assertThrows<ServiceNotFound> { catalog.requireActive(999_999) }
    }
}
