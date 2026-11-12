package com.itsm.admin.sla

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.AdminApiTestSupport.Companion.unique
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.error.MissingSlaConfiguration
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import com.itsm.common.spi.SlaPolicyProvider
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
 * SLA policy administration and resolution on a real PostgreSQL 16 (Flyway V1..V14, Hibernate validation, the partial
 * unique indexes of V13). The four seeded global policies (P1..P4) are shared state: tests that touch them restore
 * them in `finally`. Everything else uses fresh services. Required to execute in CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SlaPolicyAdminIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/sla-policies"
        const val SERVICES = "/api/admin/services"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var adminService: SlaPolicyAdminService
    @Autowired lateinit var provider: SlaPolicyProvider

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

    private fun newService(prefix: String = "Svc "): Long = t.body(call(post(SERVICES).json(mapOf(
        "name" to unique(prefix), "description" to "Test service", "businessCriticality" to 3,
        "ownerTeam" to "Infrastructure", "dependsOnServiceIds" to emptyList<Long>()
    ))).andExpect(status().isCreated))["id"].asLong()

    private fun body(serviceId: Long?, priority: Any? = "P1", response: Any? = 10, resolution: Any? = 60, warning: Any? = null) =
        mapOf("serviceId" to serviceId, "priority" to priority, "responseMinutes" to response,
            "resolutionMinutes" to resolution, "warningThresholdPct" to warning)

    private fun create(serviceId: Long?, priority: String = "P1", response: Int = 10, resolution: Int = 60, warning: Int? = null): Long =
        t.body(call(post(BASE).json(body(serviceId, priority, response, resolution, warning))).andExpect(status().isCreated))["id"].asLong()

    private fun read(id: Long) = t.body(call(get("$BASE/$id")).andExpect(status().isOk))

    private fun updateBody(id: Long, response: Int? = null, resolution: Int? = null, warning: Int? = null): Map<String, Any?> {
        val current = read(id)
        return mapOf(
            "responseMinutes" to (response ?: current["responseMinutes"].asInt()),
            "resolutionMinutes" to (resolution ?: current["resolutionMinutes"].asInt()),
            "warningThresholdPct" to warning,
            "version" to current["version"].asLong()
        )
    }

    private fun globalId(priority: String): Long = jdbc.queryForObject(
        "select id from sla_policies where service_id is null and priority = ? and active", Long::class.java, priority)!!

    /** Runs [block] with the seeded active global policy of [priority] deactivated, then restores it. */
    private fun <T> withoutActiveGlobal(priority: String, block: () -> T): T {
        val id = globalId(priority)
        call(post("$BASE/$id/deactivate")).andExpect(status().isOk)
        try {
            return block()
        } finally {
            jdbc.update("update sla_policies set active = false where service_id is null and priority = ? and active", priority)
            call(post("$BASE/$id/activate")).andExpect(status().isOk)
        }
    }

    // --- migration seed --------------------------------------------------------------------------------------------

    @Test fun `V13 seeds the specification global defaults, one active per priority`() {
        val expected = mapOf("P1" to (5 to 30), "P2" to (15 to 120), "P3" to (60 to 480), "P4" to (240 to 1440))
        for ((p, targets) in expected) {
            val view = provider.resolve(newService(), Priority.valueOf(p))
            assertNull(view.serviceId)
            assertEquals(targets, view.responseMinutes to view.resolutionMinutes)
            assertEquals(80, view.warningThresholdPct)
        }
    }

    // --- authorization ---------------------------------------------------------------------------------------------

    @Test fun `anonymous callers get 401 and EMPLOYEE or TECHNICIAN get 403 on every endpoint, with no side effects`() {
        val id = create(newService())
        val auditBefore = t.auditCount()
        val requests = listOf(
            { get(BASE) }, { get("$BASE/$id") }, { get("$BASE/effective").param("serviceId", "1").param("priority", "P1") },
            { post(BASE).json(body(null, "P2")) },
            { put("$BASE/$id").json(mapOf("responseMinutes" to 1, "resolutionMinutes" to 2, "version" to 0)) },
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

    // --- create ----------------------------------------------------------------------------------------------------

    @Test fun `ADMIN creates a service-specific policy that coexists with the global one, and it is audited`() {
        val service = newService()
        val result = call(post(BASE).json(body(service, "P1", 3, 20, 70))).andExpect(status().isCreated)
        val created = t.body(result)
        val id = created["id"].asLong()
        result.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("$BASE/$id")))
        assertEquals(service, created["service"]["id"].asLong())
        assertEquals("P1", created["priority"].asText())
        assertEquals(3, created["responseMinutes"].asInt())
        assertEquals(20, created["resolutionMinutes"].asInt())
        assertEquals(70, created["warningThresholdPct"].asInt())
        assertTrue(created["active"].asBoolean())
        assertEquals(0, created["version"].asLong())

        val audit = t.auditRows("SLA_POLICY", id).single()
        assertEquals("SLA_POLICY_CREATED", audit.action)
        assertEquals("USER", audit.actorType)
        assertEquals(admin.id, audit.actorId)
        assertEquals("ADMIN", audit.actorRole)
        assertEquals("ACTIVE", audit.newState)
        assertEquals(service, audit.metadata["serviceId"].asLong())
        assertEquals(20, audit.metadata["resolutionMinutes"].asInt())
        assertFalse(audit.metadata["global"].asBoolean())
        assertEquals(80, read(create(newService(), "P2"))["warningThresholdPct"].asInt(), "default warning threshold")
    }

    @Test fun `invalid targets, thresholds and priorities are 400s that write no audit row`() {
        val service = newService()
        val auditBefore = t.auditCount()
        fun rejected(b: Map<String, Any?>, field: String) = call(post(BASE).json(b)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value(field))

        rejected(body(service, response = 10, resolution = 10), "resolutionMinutes")
        rejected(body(service, response = 50, resolution = 10), "resolutionMinutes")
        rejected(body(service, response = 0, resolution = 10), "responseMinutes")
        rejected(body(service, response = -5, resolution = 10), "responseMinutes")
        rejected(body(service, response = 10, resolution = 43_201), "resolutionMinutes")
        rejected(body(service, warning = 0), "warningThresholdPct")
        rejected(body(service, warning = 100), "warningThresholdPct")
        rejected(body(service, priority = "P9"), "priority")
        call(post(BASE).json(body(service, response = null))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        call(post(BASE).json(body(service, priority = null))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        assertEquals(auditBefore, t.auditCount())
    }

    @Test fun `exactly 30 days is accepted`() {
        val id = create(newService(), "P4", 60, 43_200)
        assertEquals(43_200, read(id)["resolutionMinutes"].asInt())
    }

    @Test fun `unknown and inactive services cannot receive a new policy`() {
        call(post(BASE).json(body(999_999L))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
        val retired = newService()
        call(post("$SERVICES/$retired/deactivate")).andExpect(status().isOk)
        call(post(BASE).json(body(retired))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test fun `a second active policy for the same service and priority, or a second global one, is a 409`() {
        val service = newService()
        create(service, "P2")
        val auditBefore = t.auditCount()
        call(post(BASE).json(body(service, "P2"))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        call(post(BASE).json(body(null, "P2"))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        assertEquals(auditBefore, t.auditCount())
        create(service, "P3")   // another priority for the same service is fine
    }

    @Test fun `the database itself rejects duplicate active policies and keeps any number of inactive ones`() {
        val service = newService()
        create(service, "P1")
        fun insert(active: Boolean) = jdbc.update(
            "insert into sla_policies (service_id, priority, response_minutes, resolution_minutes, active) values (?, 'P1', 1, 2, ?)",
            service, active)
        val ex = assertThrows<DataAccessException> { insert(true) }
        assertTrue(ex.mostSpecificCause.message!!.contains("uq_sla_active_service_priority"))
        insert(false); insert(false)
        val global = assertThrows<DataAccessException> {
            jdbc.update("insert into sla_policies (priority, response_minutes, resolution_minutes) values ('P1', 1, 2)")
        }
        assertTrue(global.mostSpecificCause.message!!.contains("uq_sla_active_global_priority"))
    }

    @Test fun `unknown policy ids are 404`() {
        for (request in listOf(get("$BASE/999999"), post("$BASE/999999/activate"), post("$BASE/999999/deactivate"),
            put("$BASE/999999").json(mapOf("responseMinutes" to 1, "resolutionMinutes" to 2, "version" to 0)))) {
            call(request).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        }
    }

    // --- update ----------------------------------------------------------------------------------------------------

    @Test fun `update changes targets and threshold, bumps the version and audits before and after`() {
        val id = create(newService(), "P1", 10, 60, 80)
        val updated = t.body(call(put("$BASE/$id").json(updateBody(id, response = 5, resolution = 90, warning = 60))).andExpect(status().isOk))
        assertEquals(5, updated["responseMinutes"].asInt())
        assertEquals(90, updated["resolutionMinutes"].asInt())
        assertEquals(60, updated["warningThresholdPct"].asInt())
        assertEquals(1, updated["version"].asLong())

        val audit = t.auditRows("SLA_POLICY", id).last()
        assertEquals("SLA_POLICY_UPDATED", audit.action)
        assertEquals(listOf("responseMinutes", "resolutionMinutes", "warningThresholdPct"), audit.metadata["changedFields"].map { it.asText() })
        assertEquals(10, audit.metadata["responseMinutesBefore"].asInt())
        assertEquals(5, audit.metadata["responseMinutesAfter"].asInt())
        assertEquals(60, audit.metadata["resolutionMinutesBefore"].asInt())
        assertEquals(90, audit.metadata["resolutionMinutesAfter"].asInt())
        assertEquals(80, audit.metadata["warningThresholdPctBefore"].asInt())
        assertEquals(60, audit.metadata["warningThresholdPctAfter"].asInt())
    }

    @Test fun `an omitted warning threshold keeps the current value`() {
        val id = create(newService(), "P1", 10, 60, 55)
        val result = t.body(call(put("$BASE/$id").json(updateBody(id, resolution = 61))).andExpect(status().isOk))
        assertEquals(55, result["warningThresholdPct"].asInt())
    }

    @Test fun `stale versions are 409, invalid updates are 400, and unchanged updates are silent no-ops`() {
        val id = create(newService())
        val stale = updateBody(id)
        call(put("$BASE/$id").json(updateBody(id, resolution = 120))).andExpect(status().isOk)
        call(put("$BASE/$id").json(stale)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))

        val auditBefore = t.auditCount()
        call(put("$BASE/$id").json(updateBody(id, response = 200, resolution = 100))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("resolutionMinutes"))
        call(put("$BASE/$id").json(updateBody(id))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        assertEquals(auditBefore, t.auditCount())
        assertEquals(1, read(id)["version"].asLong())
    }

    // --- activation ------------------------------------------------------------------------------------------------

    @Test fun `deactivate and activate record transitions, repeating one is a no-op, and history is kept`() {
        val service = newService()
        val id = create(service, "P3")
        call(post("$BASE/$id/deactivate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        call(post("$BASE/$id/deactivate")).andExpect(status().isOk)
        val replacement = create(service, "P3", 1, 2)          // the scope is free again
        call(post("$BASE/$id/activate")).andExpect(status().isConflict)   // ...so the old one cannot return
            .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        call(post("$BASE/$replacement/deactivate")).andExpect(status().isOk)
        call(post("$BASE/$id/activate")).andExpect(status().isOk).andExpect(jsonPath("$.active").value(true))

        val audit = t.auditRows("SLA_POLICY", id)
        assertEquals(listOf("SLA_POLICY_CREATED", "SLA_POLICY_DEACTIVATED", "SLA_POLICY_ACTIVATED"), audit.map { it.action })
        assertEquals("ACTIVE" to "INACTIVE", audit[1].previousState to audit[1].newState)
        assertEquals("INACTIVE" to "ACTIVE", audit[2].previousState to audit[2].newState)
        val inactive = t.body(call(get(BASE).param("serviceId", "$service").param("active", "false")).andExpect(status().isOk))
        assertEquals(listOf(replacement), inactive["items"].map { it["id"].asLong() })
    }

    // --- resolution (SlaPolicyProvider) ----------------------------------------------------------------------------

    @Test fun `a service-specific policy wins over the global one, and the effective endpoint reports the scope`() {
        val service = newService()
        val specific = create(service, "P1", 2, 15)
        val view = provider.resolve(service, Priority.P1)
        assertEquals(specific, view.id)
        assertEquals(service, view.serviceId)
        assertEquals(2, view.responseMinutes)
        assertEquals(15, view.resolutionMinutes)
        assertEquals(java.time.Duration.ofMinutes(15), view.resolutionTarget)

        call(get("$BASE/effective").param("serviceId", "$service").param("priority", "P1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.scope").value("SERVICE")).andExpect(jsonPath("$.policyId").value(specific))
        call(get("$BASE/effective").param("serviceId", "${newService()}").param("priority", "p1")).andExpect(status().isOk)
            .andExpect(jsonPath("$.scope").value("GLOBAL")).andExpect(jsonPath("$.responseMinutes").value(5))
    }

    @Test fun `an inactive service-specific policy falls back to the active global policy, and is never returned`() {
        val service = newService()
        val specific = create(service, "P2", 1, 5)
        call(post("$BASE/$specific/deactivate")).andExpect(status().isOk)

        val view = provider.resolve(service, Priority.P2)
        assertNull(view.serviceId, "falls back to the global policy")
        assertEquals(15, view.responseMinutes)
        assertTrue(view.id != specific)

        call(post("$BASE/$specific/activate")).andExpect(status().isOk)
        assertEquals(specific, provider.resolve(service, Priority.P2).id)
    }

    @Test fun `with no active policy in either scope the provider throws MissingSlaConfiguration, and the API answers 422`() {
        val service = newService()
        withoutActiveGlobal("P4") {
            val ex = assertThrows<MissingSlaConfiguration> { provider.resolve(service, Priority.P4) }
            assertEquals(422, ex.code.httpStatus)
            call(get("$BASE/effective").param("serviceId", "$service").param("priority", "P4"))
                .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("MISSING_SLA_CONFIG"))

            // A service-specific policy still serves that service, only others have nothing.
            val own = create(service, "P4", 30, 600)
            assertEquals(own, provider.resolve(service, Priority.P4).id)
            assertThrows<MissingSlaConfiguration> { provider.resolve(newService(), Priority.P4) }
            call(post("$BASE/$own/deactivate")).andExpect(status().isOk)
            assertThrows<MissingSlaConfiguration> { provider.resolve(service, Priority.P4) }
        }
        assertEquals(240, provider.resolve(service, Priority.P4).responseMinutes, "the seeded global was restored")
    }

    @Test fun `an unknown service throws ServiceNotFound, but an inactive service still resolves`() {
        assertThrows<ServiceNotFound> { provider.resolve(999_999, Priority.P1) }
        call(get("$BASE/effective").param("serviceId", "999999").param("priority", "P1"))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
        call(get("$BASE/effective").param("serviceId", "1").param("priority", "P7")).andExpect(status().isBadRequest)

        val service = newService()
        val own = create(service, "P3", 20, 100)
        call(post("$SERVICES/$service/deactivate")).andExpect(status().isOk)
        assertEquals(own, provider.resolve(service, Priority.P3).id, "historical incidents on a retired service keep resolving")
    }

    // --- transactions ----------------------------------------------------------------------------------------------

    @Test fun `a rolled-back transaction leaves neither the policy nor its audit row`() {
        val service = newService()
        val auditBefore = t.auditCount()
        tx.executeWithoutResult { status ->
            t.asCaller(admin) { adminService.create(CreateSlaPolicyRequest(service, "P2", 5, 10, null)) }
            status.setRollbackOnly()
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from sla_policies where service_id = ?", Long::class.java, service))
        assertEquals(auditBefore, t.auditCount())
    }

    // --- list ------------------------------------------------------------------------------------------------------

    @Test fun `list filters by service, scope, priority, status and service name, sorts, and pages`() {
        val token = unique("sl")
        val a = t.body(call(post(SERVICES).json(mapOf("name" to "Alpha $token", "businessCriticality" to 3))).andExpect(status().isCreated))["id"].asLong()
        val b = t.body(call(post(SERVICES).json(mapOf("name" to "Beta $token", "businessCriticality" to 3))).andExpect(status().isCreated))["id"].asLong()
        val a1 = create(a, "P1", 5, 30); val a2 = create(a, "P2", 10, 60); val b1 = create(b, "P1", 7, 40)
        call(post("$BASE/$a2/deactivate")).andExpect(status().isOk)

        fun ids(vararg params: Pair<String, String>): List<Long> {
            val request = get(BASE).param("size", "100")
            params.forEach { request.param(it.first, it.second) }
            return t.body(call(request).andExpect(status().isOk))["items"].map { it["id"].asLong() }
        }
        assertEquals(setOf(a1, a2, b1), ids("search" to token.uppercase()).toSet())
        assertEquals(setOf(a1, b1), ids("search" to token, "priority" to "p1").toSet())
        assertEquals(listOf(a2), ids("serviceId" to "$a", "active" to "false"))
        assertEquals(listOf(a1, a2), ids("serviceId" to "$a", "sort" to "priority"))
        assertEquals(listOf(b1, a1), ids("search" to token, "priority" to "P1", "sort" to "responseMinutes", "dir" to "desc"))
        assertTrue(ids("global" to "true", "active" to "true").size >= 4)
        assertTrue(ids("global" to "false", "search" to token).containsAll(listOf(a1, a2, b1)))

        val page = t.body(call(get(BASE).param("search", token).param("size", "2").param("page", "1")).andExpect(status().isOk))
        assertEquals(3, page["totalItems"].asLong()); assertEquals(2, page["totalPages"].asInt()); assertEquals(1, page["items"].size())
        call(get(BASE).param("sort", "bogus")).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("sort"))
        call(get(BASE).param("priority", "P9")).andExpect(status().isBadRequest)
        call(get(BASE).param("search", "100%_\\")).andExpect(status().isOk).andExpect(jsonPath("$.totalItems").value(0))
        call(get("$BASE/$a1")).andExpect(status().isOk).andExpect(jsonPath("$.service.name").value(containsString("Alpha")))
    }
}
