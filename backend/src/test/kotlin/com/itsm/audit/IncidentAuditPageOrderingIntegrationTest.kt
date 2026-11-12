package com.itsm.audit

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Ordering of PAGINATION VALIDATION versus INCIDENT ACCESS AUTHORISATION on `GET /api/incidents/{id}/audit`.
 *
 * Intended contract (documented in docs/architecture/audit-retrieval.md):
 *   1. authentication (401), from the security filter chain, before the controller runs;
 *   2. request SHAPE, a pure function of the request that reads nothing about any incident: `page`/`size` (400, from
 *      `PageQuery`, constructed as the controller argument) and the incident id format (400);
 *   3. P2's `IncidentAccessPolicy` (404 unknown incident, 403 not allowed);
 *   4. only then the audit table is queried.
 * So an unreachable page is a 400 for every authenticated caller whatever the incident, which discloses nothing
 * (it is identical for an existing, a forbidden and a missing incident), and the policy is not even consulted.
 * This is the same position as a negative page or a malformed id, which were always rejected before the policy ran.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakePolicyConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class IncidentAuditPageOrderingIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        private const val UNREACHABLE = "page=21474837&size=100"      // offset 2147483700 > Int.MAX_VALUE
        private const val VALID = "page=0&size=20"
        private const val SECRET = "SECRET-DESCRIPTION-b13"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var policy: FakeIncidentAccessPolicy

    private lateinit var t: AdminApiTestSupport
    private lateinit var reporter: Caller
    private lateinit var stranger: Caller
    private lateinit var incident: String
    private lateinit var missing: String

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        reporter = t.caller(Role.EMPLOYEE); stranger = t.caller(Role.EMPLOYEE)
        incident = "INC" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        missing = "INC" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        policy.viewers[incident] = setOf(reporter.id)           // `missing` is deliberately absent: unknown incident
        policy.calls.clear()
        jdbc.update(
            """insert into audit_events (event_id, occurred_at, actor_type, actor_id, actor_role, action, entity_type, entity_id,
                   incident_id, description, metadata)
               values (?, ?, 'USER', 99, 'TECHNICIAN', 'INCIDENT_NOTED', 'INCIDENT', ?, ?, ?, '{}'::jsonb)""",
            UUID.randomUUID(), OffsetDateTime.now(), incident, incident, SECRET
        )
    }

    private fun trail(id: String, who: Caller?, query: String): ResultActions {
        val builder = get("/api/incidents/$id/audit?$query")
        if (who != null) builder.header("Authorization", who.bearer)
        return mvc.perform(builder)
    }

    /** No failure response may carry audit rows, the event description, or any item list. */
    private fun assertNoDisclosure(r: ResultActions) {
        val body = r.andReturn().response.contentAsString
        assertFalse(body.contains(SECRET), "response leaked the audit description: $body")
        assertFalse(body.contains("INCIDENT_NOTED"), "response leaked an audit row: $body")
        assertFalse(body.contains("\"items\""), "response carried an item list: $body")
        listOf("Exception", "jdbc:", "org.springframework", "com.itsm", "hibernate", "stacktrace").forEach {
            assertFalse(body.contains(it), "response leaked '$it': $body")
        }
    }

    private fun assertPageRejected(r: ResultActions) {
        r.andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.errors[0].field").value("page")).andExpect(jsonPath("$.errors[0].message").value("is out of range"))
        assertNoDisclosure(r)
    }

    @Test fun `1 anonymous caller with an unreachable page gets 401, authentication comes first`() {
        val r = trail(incident, null, UNREACHABLE).andExpect(status().isUnauthorized)
        assertNoDisclosure(r)
        assertTrue(policy.calls.isEmpty(), "the policy is never consulted for an anonymous caller")
    }

    @Test fun `2 unauthorised employee with an unreachable page gets 400, shape is checked before the policy`() {
        assertPageRejected(trail(incident, stranger, UNREACHABLE))
        assertTrue(policy.calls.isEmpty(), "the policy was not consulted: an invalid request never reaches access logic")
    }

    @Test fun `3 unauthorised employee with a valid page gets 403 and no data`() {
        val r = trail(incident, stranger, VALID).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
        assertNoDisclosure(r)
        assertEquals(listOf(stranger.id to incident), policy.calls.toList())
    }

    @Test fun `4 authorised employee with an unreachable page gets 400 and no data`() {
        assertPageRejected(trail(incident, reporter, UNREACHABLE))
        assertTrue(policy.calls.isEmpty())
    }

    @Test fun `5 authorised employee with a valid page gets 200 with the trail`() {
        val body = trail(incident, reporter, VALID).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(body.contains("\"items\""))
        assertEquals(listOf(reporter.id to incident), policy.calls.toList())
    }

    @Test fun `6 missing incident with an unreachable page gets the same 400 as an existing one, so nothing about existence leaks`() {
        val forMissing = trail(missing, reporter, UNREACHABLE)
        assertPageRejected(forMissing)
        val forExisting = trail(incident, reporter, UNREACHABLE).andReturn().response
        val forForbidden = trail(incident, stranger, UNREACHABLE).andReturn().response
        fun shape(json: String) = t.json.readTree(json).let { it["code"].asText() to it["errors"].toString() }
        assertEquals(shape(forExisting.contentAsString), shape(forMissing.andReturn().response.contentAsString))
        assertEquals(shape(forExisting.contentAsString), shape(forForbidden.contentAsString))
        assertTrue(policy.calls.isEmpty())
    }

    @Test fun `control missing incident with a valid page is still 404, so the policy ordering is unchanged`() {
        trail(missing, reporter, VALID).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        assertEquals(listOf(reporter.id to missing), policy.calls.toList())
    }
}
