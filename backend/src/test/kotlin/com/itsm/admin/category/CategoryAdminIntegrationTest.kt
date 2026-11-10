package com.itsm.admin.category

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.AdminApiTestSupport.Companion.unique
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.model.Role
import com.itsm.common.spi.CategoryCatalog
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * Category administration on a real PostgreSQL 16 (V1, V10, V11, V12 applied by Flyway; Hibernate validates the
 * schema). Requests use real users and JWTs, so authorization runs through the production filter chain.
 *
 * Rows persist between tests (and audit rows cannot be deleted), so every test uses unique names, codes and
 * keywords. Skipped without Docker locally; in CI the workflow requires this class to execute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CategoryAdminIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/categories"
        val SEEDED = setOf("HARDWARE", "SOFTWARE", "NETWORK", "DATABASE", "CLOUD", "SECURITY", "AUTHENTICATION", "APPLICATION", "STORAGE")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var service: CategoryAdminService
    @Autowired lateinit var catalog: CategoryCatalog

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

    private fun createBody(code: String = unique("C_").uppercase(), name: String = unique("Category "),
                           keywords: List<String> = listOf(unique("kw")), description: String? = "Things that break") =
        mapOf("code" to code, "name" to name, "description" to description, "keywords" to keywords)

    private fun create(body: Map<String, Any?> = createBody()): Long =
        t.body(call(post(BASE).json(body)).andExpect(status().isCreated))["id"].asLong()

    private fun read(id: Long) = t.body(call(get("$BASE/$id")).andExpect(status().isOk))

    private fun updateBody(id: Long, name: String? = null, keywords: List<String>? = null, description: String? = null): Map<String, Any?> {
        val current = read(id)
        return mapOf(
            "name" to (name ?: current["name"].asText()),
            "description" to (description ?: current["description"]?.takeIf { !it.isNull }?.asText()),
            "keywords" to (keywords ?: current["keywords"].map { it.asText() }),
            "version" to current["version"].asLong()
        )
    }

    // --- migration and seed data -----------------------------------------------------------------------------------

    @Test fun `V11 seeds the nine specification categories, active, with normalised keywords`() {
        val page = t.body(call(get(BASE).param("size", "100").param("active", "true")).andExpect(status().isOk))
        val codes = page["items"].map { it["code"].asText() }.toSet()
        assertTrue(codes.containsAll(SEEDED), "missing seeded categories: ${SEEDED - codes}")
        val network = catalog.findByCode("NETWORK")!!
        assertTrue(network.active)
        assertEquals(listOf("connection", "dns", "latency", "router", "vpn", "wifi"), network.keywords)
    }

    // --- authorization ---------------------------------------------------------------------------------------------

    @Test fun `anonymous callers get 401 and EMPLOYEE or TECHNICIAN get 403 on every endpoint, with no side effects`() {
        val id = create()
        val auditBefore = t.auditCount()
        val requests = listOf(
            { get(BASE) }, { get("$BASE/$id") },
            { post(BASE).json(createBody()) },
            { put("$BASE/$id").json(mapOf("name" to "Renamed x", "keywords" to listOf("zz"), "version" to 0)) },
            { post("$BASE/$id/activate") }, { post("$BASE/$id/deactivate") }
        )
        for (request in requests) {
            call(request(), caller = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            for (role in listOf(Role.EMPLOYEE, Role.TECHNICIAN)) {
                call(request(), t.caller(role)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }
        assertEquals(auditBefore, t.auditCount(), "a rejected request must not write audit rows")
        assertTrue(read(id)["active"].asBoolean(), "a forbidden deactivate must not change anything")
    }

    // --- create ----------------------------------------------------------------------------------------------------

    @Test fun `ADMIN creates a category - 201, Location, normalised values and a CATEGORY_CREATED audit row`() {
        val code = unique("NEW_").uppercase()
        val kw = unique("kw")
        val result = call(post(BASE).json(mapOf(
            "code" to " $code ", "name" to "  Campus   ${kw.uppercase()} ", "description" to "  Lab kit  ",
            "keywords" to listOf("  ${kw.uppercase()}  Device ", "Zeta")
        ))).andExpect(status().isCreated)
        val body = t.body(result)
        val id = body["id"].asLong()
        result.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("$BASE/$id")))
        assertEquals(code, body["code"].asText())
        assertEquals("Campus ${kw.uppercase()}", body["name"].asText())
        assertEquals("Lab kit", body["description"].asText())
        assertEquals(listOf("$kw device", "zeta"), body["keywords"].map { it.asText() })
        assertTrue(body["active"].asBoolean())
        assertEquals(0, body["version"].asLong())
        assertNotNull(body["createdAt"].asText())

        val audit = t.auditRows("CATEGORY", id).single()
        assertEquals("CATEGORY_CREATED", audit.action)
        assertEquals("USER", audit.actorType)
        assertEquals(admin.id, audit.actorId)
        assertEquals("ADMIN", audit.actorRole)
        assertEquals("ACTIVE", audit.newState)
        assertEquals(code, audit.metadata["code"].asText())
        assertEquals(2, audit.metadata["keywordCount"].asInt())
    }

    @Test fun `duplicate names are rejected ignoring case and spacing, and write no audit row`() {
        val name = unique("Printers ")
        create(createBody(name = name))
        val auditBefore = t.auditCount()
        call(post(BASE).json(createBody(name = "  ${name.uppercase().replace(" ", "   ")} ")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        assertEquals(auditBefore, t.auditCount())
    }

    @Test fun `duplicate codes are rejected`() {
        val code = unique("DUP_").uppercase()
        create(createBody(code = code))
        call(post(BASE).json(createBody(code = code))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
    }

    @Test fun `invalid input is a 400 with field errors`() {
        call(post(BASE).json(createBody(code = "not-upper"))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.errors[0].field").value("code"))
        call(post(BASE).json(mapOf("code" to "X_MISSING", "keywords" to listOf("ab")))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("name"))
        call(post(BASE).json(createBody(name = " ab "))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("name"))
        call(post(BASE).json(createBody(keywords = (1..16).map { "kw$it" })))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        call(post(BASE).json(createBody(keywords = listOf("x")))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("keywords[0]"))
        call(post(BASE).json(createBody(keywords = listOf("ok", "bad#chars")))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("keywords[1]"))
        call(post(BASE).json(createBody(keywords = emptyList()))).andExpect(status().isBadRequest)
    }

    @Test fun `duplicate keywords within one category are rejected after normalisation`() {
        val kw = unique("vpn access ")
        call(post(BASE).json(createBody(keywords = listOf(kw, "  ${kw.uppercase().replace(" ", "   ")}  "))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.errors[0].field").value("keywords"))
    }

    @Test fun `a keyword already used by an active category is a 409 naming that category`() {
        call(post(BASE).json(createBody(keywords = listOf(unique("kw"), " ROUTER "))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("'Network'")))
    }

    @Test fun `keywords of an inactive category are free, and it cannot be reactivated while they are taken`() {
        val kw = unique("shared")
        val first = create(createBody(keywords = listOf(kw)))
        call(post("$BASE/$first/deactivate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))

        create(createBody(keywords = listOf(kw)))      // allowed: the only other owner is inactive

        call(post("$BASE/$first/activate")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
        assertFalse(read(first)["active"].asBoolean())
        assertEquals(listOf("CATEGORY_CREATED", "CATEGORY_DEACTIVATED"), t.auditRows("CATEGORY", first).map { it.action })
    }

    @Test fun `the database itself rejects an active keyword collision even without the service check`() {
        val owner = catalog.findByCode("STORAGE")!!.id
        val other = create()
        val ex = assertThrows<DataAccessException> {
            jdbc.update("insert into incident_category_keywords (category_id, keyword) values (?, 'backup')", other)
        }
        assertTrue(ex.mostSpecificCause.message!!.contains("uq_active_category_keyword"))
        assertTrue(catalog.findById(owner)!!.keywords.contains("backup"))
    }

    // --- update ----------------------------------------------------------------------------------------------------

    @Test fun `update replaces fields, bumps the version and audits the changed fields and keyword diff`() {
        val keep = unique("keep"); val drop = unique("drop"); val add = unique("add")
        val id = create(createBody(keywords = listOf(keep, drop)))
        val newName = unique("Renamed ")
        val body = t.body(call(put("$BASE/$id").json(updateBody(id, name = newName, keywords = listOf(keep, add.uppercase()))))
            .andExpect(status().isOk))
        assertEquals(newName, body["name"].asText())
        assertEquals(listOf(add, keep).sorted(), body["keywords"].map { it.asText() })
        assertEquals(1, body["version"].asLong())

        val audit = t.auditRows("CATEGORY", id).last()
        assertEquals("CATEGORY_UPDATED", audit.action)
        assertEquals(listOf("name", "keywords"), audit.metadata["changedFields"].map { it.asText() })
        assertEquals(newName, audit.metadata["nameAfter"].asText())
        assertEquals(listOf(add), audit.metadata["keywordsAdded"].map { it.asText() })
        assertEquals(listOf(drop), audit.metadata["keywordsRemoved"].map { it.asText() })
    }

    @Test fun `an update carrying a stale version is a 409 and changes nothing`() {
        val id = create()
        val stale = updateBody(id)
        call(put("$BASE/$id").json(updateBody(id, name = unique("First ")))).andExpect(status().isOk)
        call(put("$BASE/$id").json(stale + ("name" to unique("Second ")))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        assertEquals(1, read(id)["version"].asLong())
    }

    @Test fun `an update that changes nothing keeps the version and writes no audit row`() {
        val id = create()
        val auditBefore = t.auditCount()
        call(put("$BASE/$id").json(updateBody(id))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(0))
        assertEquals(auditBefore, t.auditCount())
    }

    @Test fun `unknown categories are 404 on every id endpoint`() {
        for (request in listOf(get("$BASE/999999"), post("$BASE/999999/activate"), post("$BASE/999999/deactivate"),
                               put("$BASE/999999").json(mapOf("name" to "Whatever", "keywords" to listOf("zz"), "version" to 0)))) {
            call(request).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }
    }

    // --- activation ------------------------------------------------------------------------------------------------

    @Test fun `deactivate and activate record state transitions, and repeating one is a silent no-op`() {
        val id = create()
        call(post("$BASE/$id/deactivate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        call(post("$BASE/$id/deactivate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        call(post("$BASE/$id/activate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(true))

        val audit = t.auditRows("CATEGORY", id)
        assertEquals(listOf("CATEGORY_CREATED", "CATEGORY_DEACTIVATED", "CATEGORY_ACTIVATED"), audit.map { it.action })
        assertEquals("ACTIVE" to "INACTIVE", audit[1].previousState to audit[1].newState)
        assertEquals("INACTIVE" to "ACTIVE", audit[2].previousState to audit[2].newState)
    }

    @Test fun `the last active category cannot be deactivated`() {
        // Inside one transaction that is always rolled back, so the shared database keeps its categories.
        val error = tx.execute { status ->
            status.setRollbackOnly()
            t.asCaller(admin) {
                val active = catalog.listActive()
                active.dropLast(1).forEach { service.deactivate(it.id) }
                assertEquals(1, catalog.listActive().size)
                assertThrows<ItsmException> { service.deactivate(active.last().id) }
            }
        }!!
        assertEquals(ErrorCode.CONFLICT, error.code)
        assertTrue(catalog.listActive().size >= SEEDED.size, "the rollback restored every category")
    }

    // --- transactions ----------------------------------------------------------------------------------------------

    @Test fun `a rolled-back transaction leaves neither the category nor its audit row`() {
        val code = unique("RB_").uppercase()
        val auditBefore = t.auditCount()
        tx.executeWithoutResult { status ->
            t.asCaller(admin) {
                service.create(CreateCategoryRequest(code, unique("Rolled back "), null, listOf(unique("kw"))))
            }
            status.setRollbackOnly()
        }
        assertNull(catalog.findByCode(code))
        assertEquals(auditBefore, t.auditCount())
    }

    // --- list ------------------------------------------------------------------------------------------------------

    @Test fun `list filters by search across name, code, description and keywords, by status, and pages`() {
        val token = unique("zq")
        val byKeyword = create(createBody(keywords = listOf("$token marker")))
        val byName = create(createBody(name = "Name ${token.uppercase()}"))
        val inactive = create(createBody(description = "About $token here"))
        call(post("$BASE/$inactive/deactivate")).andExpect(status().isOk)

        fun ids(vararg params: Pair<String, String>): List<Long> {
            val request = get(BASE).param("size", "100")
            params.forEach { request.param(it.first, it.second) }
            return t.body(call(request).andExpect(status().isOk))["items"].map { it["id"].asLong() }
        }
        assertEquals(setOf(byKeyword, byName, inactive), ids("search" to token.uppercase()).toSet())
        assertEquals(setOf(byKeyword, byName), ids("search" to token, "active" to "true").toSet())
        assertEquals(listOf(inactive), ids("search" to token, "active" to "false"))
        assertEquals(listOf(byName, byKeyword).sortedByDescending { read(it)["name"].asText() },
            ids("search" to token, "active" to "true", "sort" to "name", "dir" to "desc"))

        val page = t.body(call(get(BASE).param("search", token).param("size", "1").param("page", "1")).andExpect(status().isOk))
        assertEquals(3, page["totalItems"].asLong())
        assertEquals(3, page["totalPages"].asInt())
        assertEquals(1, page["items"].size())

        call(get(BASE).param("sort", "bogus")).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("sort"))
        call(get(BASE).param("size", "101")).andExpect(status().isBadRequest)
        call(get(BASE).param("search", "100%_\\")).andExpect(status().isOk).andExpect(jsonPath("$.totalItems").value(0))
    }

    // --- CategoryCatalog SPI ---------------------------------------------------------------------------------------

    @Test fun `CategoryCatalog exposes immutable views with the contract's active semantics`() {
        val id = create(createBody(keywords = listOf("zz top", "aa first")))
        val view = catalog.findById(id)!!
        assertEquals(listOf("aa first", "zz top"), view.keywords)
        assertEquals(view, catalog.requireActive(id))
        assertTrue(catalog.listActive().any { it.id == id })

        call(post("$BASE/$id/deactivate")).andExpect(status().isOk)
        assertFalse(catalog.findById(id)!!.active, "inactive categories stay readable for history")
        assertTrue(catalog.listActive().none { it.id == id })
        assertThrows<InvalidInput> { catalog.requireActive(id) }

        assertNull(catalog.findById(999_999))
        assertNull(catalog.findByCode("NO_SUCH_CODE"))
        assertThrows<ResourceNotFound> { catalog.requireActive(999_999) }
        assertThrows<UnsupportedOperationException> { (view.keywords as MutableList<String>).add("x") }
    }
}
