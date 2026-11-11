package com.itsm.integration

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.AdminApiTestSupport.Companion.unique
import com.itsm.admin.sla.SlaPolicyRepository
import com.itsm.auth.JwtService
import com.itsm.common.error.MissingSlaConfiguration
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import com.itsm.common.spi.SlaPolicyProvider
import com.itsm.common.spi.SlaPolicyView
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** What the simulated P4 intake learned from the SLA lookup. */
data class IntakeResult(val sla: SlaPolicyView?, val noPolicy: Boolean, val unknownService: Boolean, val failure: String? = null)

/**
 * TEST-ONLY stand-in for the P4 calling pattern described in `docs/architecture/integration-guide.md`: an `@Transactional`
 * business operation that makes an UNRELATED database write, asks the REAL [SlaPolicyProvider] for a policy, catches the
 * documented outcomes ("no policy", "unknown service"), carries on, and commits.
 */
@Transactional
class SimulatedP4Intake(private val provider: SlaPolicyProvider, private val jdbc: JdbcTemplate) {

    /** Catches exactly the two documented outcomes; anything else (an infrastructure failure) is allowed to escape. */
    fun intake(note: String, serviceId: Long, priority: Priority): IntakeResult {
        jdbc.update("insert into b11_caller_write (note) values (?)", "before:$note")
        val result = try {
            IntakeResult(provider.resolve(serviceId, priority), noPolicy = false, unknownService = false)
        } catch (e: MissingSlaConfiguration) {
            IntakeResult(null, noPolicy = true, unknownService = false)
        } catch (e: ServiceNotFound) {
            IntakeResult(null, noPolicy = false, unknownService = true)
        }
        jdbc.update("insert into b11_caller_write (note) values (?)", "after:$note")   // "continues its business logic"
        return result
    }

    /** A sloppy caller that swallows EVERY failure of the lookup and carries on. */
    fun intakeSwallowingEverything(note: String, serviceId: Long, priority: Priority): IntakeResult {
        jdbc.update("insert into b11_caller_write (note) values (?)", "before:$note")
        val result = try {
            IntakeResult(provider.resolve(serviceId, priority), noPolicy = false, unknownService = false)
        } catch (e: Exception) {
            IntakeResult(null, noPolicy = false, unknownService = false, failure = e.javaClass.simpleName)
        }
        jdbc.update("insert into b11_caller_write (note) values (?)", "after:$note")
        return result
    }
}

@TestConfiguration
class SimulatedP4Config {
    @Bean fun simulatedP4Intake(provider: SlaPolicyProvider, jdbc: JdbcTemplate) = SimulatedP4Intake(provider, jdbc)
}

/**
 * B10-INT-001. P4 will call `SlaPolicyProvider.resolve` from inside its own transaction and, per the integration guide,
 * may catch `MissingSlaConfiguration` / `ServiceNotFound` and degrade. The provider joins the caller's transaction, so
 * what happens to that transaction when the provider throws is part of the contract:
 *  - the two DOCUMENTED outcomes must leave the caller's transaction committable;
 *  - a real infrastructure failure must still doom it (a swallowed database error must never commit as success).
 *
 * Real PostgreSQL 16, the real provider, the real transaction manager. The only test double is one spy that injects a
 * genuine infrastructure exception at the repository, the same technique as `JwtFilterInfrastructureFailureIntegrationTest`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SimulatedP4Config::class)
@Testcontainers(disabledWithoutDocker = true)
class SlaProviderCallerTransactionIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        private val LEAK_MARKERS = listOf("Exception", "jdbc:", "org.springframework", "com.itsm", "hibernate", "stacktrace")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var provider: SlaPolicyProvider
    @Autowired lateinit var p4: SimulatedP4Intake
    @SpyBean lateinit var policies: SlaPolicyRepository

    @BeforeEach fun setUp() {
        jdbc.execute("create table if not exists b11_caller_write (id bigserial primary key, note text not null)")
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun newService(): Long = jdbc.queryForObject(
        "insert into it_services (name, business_criticality) values (?, 3) returning id", Long::class.java, unique("B11 svc ")
    )!!

    private fun newPolicy(serviceId: Long?, priority: String, response: Int, resolution: Int, active: Boolean = true): Long = jdbc.queryForObject(
        "insert into sla_policies (service_id, priority, response_minutes, resolution_minutes, active) values (?, ?, ?, ?, ?) returning id",
        Long::class.java, serviceId, priority, response, resolution, active
    )!!

    /** Rows the simulated P4 managed to commit for [note]. */
    private fun committed(note: String): List<String> =
        jdbc.queryForList("select note from b11_caller_write where note like ? order by id", String::class.java, "%:$note")

    private fun <T> withoutActiveGlobal(priority: String, block: () -> T): T {
        val ids = jdbc.queryForList("select id from sla_policies where service_id is null and priority = ? and active", Long::class.java, priority)
        jdbc.update("update sla_policies set active = false where id in (${ids.joinToString(",")})")
        try {
            return block()
        } finally {
            jdbc.update("update sla_policies set active = true where id in (${ids.joinToString(",")})")
        }
    }

    // --- the regression: documented outcomes must not poison the caller -----------------------------------------------

    @Test fun `a caller that catches MissingSlaConfiguration commits its own work, before and after the lookup`() {
        val note = unique("missing-")
        val service = newService()
        withoutActiveGlobal("P4") {
            // Before the fix this throws UnexpectedRollbackException on the way out of intake(): the provider, joined to
            // intake()'s transaction, marked it rollback-only when it threw, although the caller had caught the exception.
            val result = p4.intake(note, service, Priority.P4)
            assertTrue(result.noPolicy, "the caller saw the documented 'no policy' outcome")
            assertNull(result.sla)
        }
        assertEquals(listOf("before:$note", "after:$note"), committed(note), "both unrelated writes were committed")
    }

    @Test fun `a caller that catches ServiceNotFound commits its own work`() {
        val note = unique("unknown-")
        val result = p4.intake(note, 999_999, Priority.P1)
        assertTrue(result.unknownService)
        assertEquals(listOf("before:$note", "after:$note"), committed(note))
    }

    @Test fun `repeated missing-policy lookups in one caller transaction still commit`() {
        val note = unique("repeat-")
        val service = newService()
        withoutActiveGlobal("P4") {
            repeat(3) { assertTrue(p4.intake("$note-$it", service, Priority.P4).noPolicy) }
        }
        assertEquals(6, jdbc.queryForObject("select count(*) from b11_caller_write where note like ?", Long::class.java, "%:$note-%"))
    }

    // --- semantics are unchanged --------------------------------------------------------------------------------------

    @Test fun `inside a caller transaction a service-specific policy wins, the global one is the fallback, an inactive specific one is skipped`() {
        val service = newService()
        val specific = newPolicy(service, "P1", 2, 15)
        val viaSpecific = p4.intake(unique("spec-"), service, Priority.P1).sla!!
        assertEquals(specific, viaSpecific.id)
        assertEquals(service, viaSpecific.serviceId)

        val other = newService()
        val viaGlobal = p4.intake(unique("glob-"), other, Priority.P1).sla!!
        assertNull(viaGlobal.serviceId, "no own policy: the global default")
        assertEquals(5, viaGlobal.responseMinutes)

        val retired = newService()
        newPolicy(retired, "P2", 1, 5, active = false)
        val viaFallback = p4.intake(unique("inact-"), retired, Priority.P2).sla!!
        assertNull(viaFallback.serviceId, "an inactive service-specific policy never hides the active global one")
        assertEquals(15, viaFallback.responseMinutes)
    }

    @Test fun `called with no surrounding transaction the provider throws the same documented exceptions`() {
        val service = newService()
        withoutActiveGlobal("P4") {
            val ex = assertThrows<MissingSlaConfiguration> { provider.resolve(service, Priority.P4) }
            assertEquals(422, ex.code.httpStatus)
        }
        assertThrows<ServiceNotFound> { provider.resolve(999_999, Priority.P1) }
        assertEquals(5, provider.resolve(service, Priority.P1).responseMinutes)
    }

    // --- real failures are NOT swallowed ------------------------------------------------------------------------------

    @Test fun `an infrastructure failure from the lookup propagates as itself, never as a missing policy`() {
        val service = newService()
        doThrow(DataAccessResourceFailureException("db down")).`when`(policies).findEffectiveCandidates(service, Priority.P1)
        // assertThrows pins the exact type: a MissingSlaConfiguration (or anything else) would fail this test.
        assertThrows<DataAccessResourceFailureException> { provider.resolve(service, Priority.P1) }
    }

    @Test fun `a caller that swallows an infrastructure failure of the lookup cannot commit because that failure still dooms the transaction`() {
        val note = unique("dbfail-")
        val service = newService()
        doThrow(DataAccessResourceFailureException("db down")).`when`(policies).findEffectiveCandidates(service, Priority.P1)
        assertThrows<UnexpectedRollbackException> { p4.intakeSwallowingEverything(note, service, Priority.P1) }
        assertEquals(emptyList<String>(), committed(note), "nothing the caller wrote in that transaction was committed")
    }

    // --- API contract is unchanged ----------------------------------------------------------------------------------

    @Test fun `the effective-policy endpoint still answers 422 and 404 with fixed codes and no internals`() {
        val t = AdminApiTestSupport(users, jwt, jdbc)
        val admin = t.caller(Role.ADMIN)
        val service = newService()
        withoutActiveGlobal("P4") {
            val body = mvc.perform(get("/api/admin/sla-policies/effective").param("serviceId", "$service").param("priority", "P4").header("Authorization", admin.bearer))
                .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("MISSING_SLA_CONFIG"))
                .andReturn().response.contentAsString
            LEAK_MARKERS.forEach { assertFalse(body.contains(it), "response leaked '$it': $body") }
        }
        val missing = mvc.perform(get("/api/admin/sla-policies/effective").param("serviceId", "999999").param("priority", "P1").header("Authorization", admin.bearer))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
            .andReturn().response.contentAsString
        LEAK_MARKERS.forEach { assertFalse(missing.contains(it), "response leaked '$it': $missing") }
    }
}
