package com.itsm.integration

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.AssignmentStrategyCatalog
import com.itsm.common.spi.IncidentAccessPolicy
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * TEST-ONLY stand-in for P3's [AssignmentStrategyCatalog]: the test decides what it returns or throws.
 * `@Transactional` on purpose: a P3 implementation backed by a database would join the caller's transaction, and a
 * RuntimeException leaving a joined transactional proxy marks that transaction rollback-only.
 */
@Transactional
class ControllableStrategyCatalog : AssignmentStrategyCatalog {
    @Volatile var keys: Set<String> = setOf("SKILL_BASED", "LOAD_BASED")
    @Volatile var failure: RuntimeException? = null
    override fun registeredKeys(): Set<String> {
        failure?.let { throw it }
        return keys
    }
}

/** TEST-ONLY stand-in for P2's [IncidentAccessPolicy]. */
class ControllableIncidentPolicy : IncidentAccessPolicy {
    @Volatile var decide: (CurrentUser, String) -> Boolean = { _, _ -> true }
    override fun canView(user: CurrentUser, incidentId: String): Boolean = decide(user, incidentId)
}

@TestConfiguration
class ControllableProvidersConfig {
    @Bean @Primary fun controllableStrategyCatalog() = ControllableStrategyCatalog()
    @Bean @Primary fun controllableIncidentPolicy() = ControllableIncidentPolicy()
}

/**
 * What P1 does when a PRESENT provider from another team misbehaves. Present/absent are covered by the dedicated
 * provider tests; this class covers the remaining contract states, with EXACT status and error code pinned:
 *  - an EMPTY but present catalog is AVAILABLE with `[]` (never confused with ABSENT or FAILED, where the list is null);
 *  - a FAILED catalog never fails a read, even a transactional one that marks the caller's transaction rollback-only;
 *  - a failed write is a generic 500 or 503, leaves the stored configuration untouched and writes no audit row;
 *  - the incident policy's domain exceptions keep their meaning, its infrastructure exceptions are generic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableProvidersConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class ProviderFailureSemanticsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        private const val SETTINGS = "/api/admin/assignment-settings"
        private const val LEAK = "secret-host:5432 password=hunter2 internal-detail"
        private val LEAK_MARKERS = listOf("hunter2", "secret-host", "internal-detail", "DataAccessResourceFailure", "IllegalState", "jdbc:")
    }

    /** Failure kinds a provider can throw, with the exact response each must produce. */
    private data class Expected(val failure: () -> RuntimeException, val status: Int, val code: String)
    private val infrastructureFailures = listOf(
        Expected({ IllegalStateException(LEAK) }, 500, "INTERNAL_ERROR"),
        Expected({ DataAccessResourceFailureException(LEAK) }, 503, "SERVICE_UNAVAILABLE"),
        Expected({ CannotCreateTransactionException(LEAK) }, 503, "SERVICE_UNAVAILABLE")
    )

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var catalog: ControllableStrategyCatalog
    @Autowired lateinit var policy: ControllableIncidentPolicy

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller
    private lateinit var employee: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN); employee = t.caller(Role.EMPLOYEE)
        catalog.keys = setOf("SKILL_BASED", "LOAD_BASED"); catalog.failure = null
        policy.decide = { _, _ -> true }
    }

    private fun getSettings(): ResultActions = mvc.perform(get(SETTINGS).header("Authorization", admin.bearer))

    private fun putSettings(strategy: String, workload: Int, version: Long): ResultActions = mvc.perform(
        put(SETTINGS).header("Authorization", admin.bearer).contentType(MediaType.APPLICATION_JSON).content(
            t.json.writeValueAsString(
                mapOf("activeStrategy" to strategy, "defaultMaxWorkload" to workload, "categoryOverrides" to emptyList<Any>(), "version" to version)
            )
        )
    )

    private fun settingsAuditCount(): Long =
        jdbc.queryForObject("select count(*) from audit_events where action = 'ASSIGNMENT_SETTINGS_UPDATED'", Long::class.java)!!

    /** The stored configuration, without the catalog hint (which legitimately changes with the catalog's state). */
    private fun stored(): JsonNode = t.body(getSettings().andExpect(status().isOk)).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        .also { it.remove("availableStrategies"); it.remove("strategyCatalogStatus") }

    private fun assertNoLeak(body: String) =
        LEAK_MARKERS.forEach { assertFalse(body.contains(it), "response leaked '$it': $body") }

    // --- catalog states on read -------------------------------------------------------------------------------------

    @Test fun `an answering catalog is AVAILABLE with its sorted keys`() {
        val view = t.body(getSettings().andExpect(status().isOk))
        assertEquals("AVAILABLE", view["strategyCatalogStatus"].asText())
        assertEquals(listOf("LOAD_BASED", "SKILL_BASED"), view["availableStrategies"].map { it.asText() })
    }

    @Test fun `an empty catalog is AVAILABLE with an empty list, distinct from ABSENT and FAILED where the list is null`() {
        catalog.keys = emptySet()
        val view = t.body(getSettings().andExpect(status().isOk))
        assertEquals("AVAILABLE", view["strategyCatalogStatus"].asText())
        assertTrue(view["availableStrategies"].isArray, "present-but-empty must serialise as [], not null")
        assertEquals(0, view["availableStrategies"].size())

        putSettings("SKILL_BASED", 7, view["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test fun `a failing catalog is FAILED on read, whatever it throws, and the read itself succeeds`() {
        val before = stored()
        for (case in infrastructureFailures) {
            catalog.failure = case.failure()
            val response = getSettings().andReturn().response
            assertEquals(200, response.status, "reading stored configuration must not depend on P3 (${case.code})")
            assertNoLeak(response.contentAsString)
            val view = t.json.readTree(response.contentAsString)
            assertEquals("FAILED", view["strategyCatalogStatus"].asText())
            assertTrue(view["availableStrategies"].isNull, "FAILED must not look like an empty list")
            assertEquals(before["activeStrategy"], view["activeStrategy"], "the stored configuration is still served")
        }
    }

    /**
     * The catalog is `@Transactional` here, so it JOINS the caller's transaction and the exception it throws marks that
     * transaction rollback-only. Before the catalog was called through a NOT_SUPPORTED gateway this read failed at COMMIT
     * with UnexpectedRollbackException (HTTP 500). The read must succeed AND the next request must be unaffected.
     */
    @Test fun `a transactional catalog that fails cannot poison the transaction of the settings read`() {
        val before = stored()
        catalog.failure = DataAccessResourceFailureException(LEAK)
        getSettings().andExpect(status().isOk).andExpect(jsonPath("$.strategyCatalogStatus").value("FAILED"))

        catalog.failure = null
        val after = t.body(getSettings().andExpect(status().isOk))
        assertEquals("AVAILABLE", after["strategyCatalogStatus"].asText())
        assertEquals(before["version"], after["version"])
    }

    // --- a failing catalog on write ---------------------------------------------------------------------------------

    @Test fun `a failing catalog makes the write a generic 500 or 503 and nothing is saved or audited`() {
        for (case in infrastructureFailures) {
            val before = stored(); val auditBefore = settingsAuditCount()
            catalog.failure = case.failure()

            val response = putSettings("SKILL_BASED", 99, before["version"].asLong()).andReturn().response
            assertEquals(case.status, response.status, "unexpected status for ${case.code}: ${response.contentAsString}")
            assertEquals(case.code, t.json.readTree(response.contentAsString)["code"].asText())
            assertNoLeak(response.contentAsString)

            catalog.failure = null
            assertEquals(before, stored(), "the failed write must leave the stored configuration untouched")
            assertEquals(auditBefore, settingsAuditCount(), "the failed write must not leave an audit row")
        }
    }

    // --- a throwing incident access policy --------------------------------------------------------------------------

    private fun trail(): ResultActions = mvc.perform(get("/api/incidents/INC-1/audit").header("Authorization", employee.bearer))

    @Test fun `a policy that says the incident does not exist is exactly a 404`() {
        policy.decide = { _, id -> throw ResourceNotFound("Incident", id) }
        trail().andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test fun `a policy that refuses the caller is exactly a 403`() {
        policy.decide = { _, _ -> throw UnauthorizedOperation("not yours") }
        trail().andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }

    @Test fun `a policy that returns false is a 403 and an empty trail is only ever returned after it allows`() {
        policy.decide = { _, _ -> false }
        trail().andExpect(status().isForbidden)
        policy.decide = { _, _ -> true }
        trail().andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty).andExpect(jsonPath("$.totalItems").value(0))
    }

    @Test fun `a policy that fails with an infrastructure error is exactly 500 or 503 by exception type and leaks nothing`() {
        for (case in infrastructureFailures) {
            policy.decide = { _, _ -> throw case.failure() }
            val response = trail().andReturn().response
            assertEquals(case.status, response.status, "unexpected status for ${case.code}: ${response.contentAsString}")
            assertEquals(case.code, t.json.readTree(response.contentAsString)["code"].asText())
            assertNoLeak(response.contentAsString)
        }
    }
}
