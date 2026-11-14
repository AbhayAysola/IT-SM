package com.itsm.integration

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.admin.sla.JpaSlaPolicyProvider
import com.itsm.assignment.AssignmentOutcome
import com.itsm.auth.JwtService
import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.SlaPolicyProvider
import com.itsm.common.spi.SlaPolicyView
import com.itsm.lifecycle.IncidentAssignmentService
import com.itsm.lifecycle.IncidentLifecycleService
import com.itsm.lifecycle.IncidentTransition
import com.itsm.sla.SlaMonitor
import com.itsm.user.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.OffsetDateTime

/**
 * P4 reacting to P2/P3's incident domain events (docs/architecture/p4.md section 6) over the real stack: intake over HTTP,
 * P3's real lifecycle and assignment services, P1's real directory and SLA policies, PostgreSQL 16. No sweep runs here
 * (the schedules are off for the whole suite), so every SLA row, notification and escalation below comes from an event.
 * The suite turns the listeners off for other modules' tests; this class turns them back on.
 */
@SpringBootTest(properties = ["app.p4.event-listeners.enabled=true"])
@AutoConfigureMockMvc
@Import(IncidentEventReactionsIntegrationTest.Failing::class)
@Testcontainers(disabledWithoutDocker = true)
class IncidentEventReactionsIntegrationTest {

    /** P1's real SLA policies, which a test can make fail, to show a listener's failure stays P4's. */
    class FailingPolicies(private val real: SlaPolicyProvider) : SlaPolicyProvider {
        @Volatile var failing = false
        override fun resolve(serviceId: Long, priority: Priority): SlaPolicyView {
            if (failing) throw IllegalStateException("simulated SLA policy outage")
            return real.resolve(serviceId, priority)
        }
    }

    @TestConfiguration
    class Failing {
        @Bean @Primary fun failingPolicies(real: JpaSlaPolicyProvider) = FailingPolicies(real)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var lifecycle: IncidentLifecycleService
    @Autowired lateinit var assignment: IncidentAssignmentService
    @Autowired lateinit var sla: SlaMonitor
    @Autowired lateinit var policies: FailingPolicies

    private lateinit var t: AdminApiTestSupport

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }
    @AfterEach fun heal() { policies.failing = false }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    /** A service with its own P2 policy: respond in 10 minutes, resolve in 60. */
    private fun service(): Long {
        val id = jdbc.queryForObject(
            "INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("evt-svc-")
        )!!
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, 'P2', 10, 60, 50)", id)
        return id
    }

    private fun technician(): Caller {
        val tech = t.caller(Role.TECHNICIAN)
        jdbc.update(
            "INSERT INTO technician_profiles (user_id, max_workload, experience_years, available, is_team_lead, reports_to_user_id) VALUES (?, 5, 3, TRUE, FALSE, NULL)",
            tech.id
        )
        return tech
    }

    private fun Caller.asUser() = CurrentUser(id, email, role)

    /** Reports an incident over HTTP, as P2's intake receives it: MEDIUM impact, HIGH urgency, ordinary service: P2. */
    private fun report(reporter: Caller, serviceId: Long): String {
        val body = mapOf(
            "title" to "Wi-Fi down", "description" to "No connection in Block C",
            "categoryId" to category, "serviceId" to serviceId, "impact" to "MEDIUM", "urgency" to "HIGH"
        )
        val response = mvc.perform(
            post("/api/incidents").header("Authorization", reporter.bearer).contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))
        ).andReturn().response
        assertEquals(201, response.status, response.contentAsString)
        return t.json.readTree(response.contentAsString)["id"].asText()
    }

    private fun inboxOf(userId: Long, incidentId: String): List<String> = jdbc.queryForList(
        "SELECT type FROM notifications WHERE recipient_id = ? AND incident_id = ? ORDER BY id", String::class.java, userId, incidentId
    )

    private fun slaRow(incidentId: String): Map<String, Any?>? =
        jdbc.queryForList("SELECT * FROM incident_sla WHERE incident_id = ?", incidentId).firstOrNull()

    private fun auditActions(incidentId: String, actor: String): List<String> = jdbc.queryForList(
        "SELECT action FROM audit_events WHERE entity_id = ? AND actor_type = 'SYSTEM' AND actor_name = ? ORDER BY id", String::class.java, incidentId, actor
    )

    private fun instant(v: Any?): Instant? = when (v) {
        null -> null
        is OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> error("unexpected timestamp $v")
    }

    @Test fun `each change is tracked and told at once, with the exact time of the change`() {
        val reporter = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN); val tech = technician()
        val id = report(reporter, service())

        // Classified: the SLA starts at once; a new incident is not news, so nobody is notified.
        val started = slaRow(id)
        assertNotNull(started, "tracking started without a sweep")
        started!!
        assertEquals("P2", started["priority"])
        assertNotNull(started["response_due_at"])
        assertEquals(listOf("SLA_STARTED"), auditActions(id, SlaMonitor.ACTOR))
        assertTrue(inboxOf(reporter.id, id).isEmpty())

        // Assigned by P3: both people are told at once.
        assignment.assignTo(id, tech.id, null, admin.asUser())
        assertEquals(listOf("INCIDENT_ASSIGNED"), inboxOf(tech.id, id))
        assertEquals(listOf("ASSIGNMENT_MADE"), inboxOf(reporter.id, id))

        // A question pauses the resolution clock at the very moment it was asked, and the reporter is told.
        lifecycle.transition(id, IncidentTransition.ACKNOWLEDGE, null, tech.asUser())
        lifecycle.transition(id, IncidentTransition.START_WORK, null, tech.asUser())
        lifecycle.transition(id, IncidentTransition.REQUEST_INFO, "Which floor?", tech.asUser())
        val askedAt = instant(jdbc.queryForObject("SELECT updated_at FROM incidents WHERE id = ?", OffsetDateTime::class.java, id))
        assertEquals(askedAt, instant(slaRow(id)!!["paused_since"]), "paused exactly when the question was asked")
        assertEquals(listOf("ASSIGNMENT_MADE", "INFO_REQUESTED"), inboxOf(reporter.id, id))

        // The answer resumes it; a later sweep finds nothing left to do or send.
        lifecycle.transition(id, IncidentTransition.PROVIDE_INFO, "Third floor", reporter.asUser())
        assertEquals(null, slaRow(id)!!["paused_since"])
        // Acknowledging was the first response, so the response clock was met at once too.
        val reactions = listOf("SLA_STARTED", "SLA_MET", "SLA_PAUSED", "SLA_RESUMED")
        assertEquals(reactions, auditActions(id, SlaMonitor.ACTOR))
        sla.runAt(Instant.now())
        assertEquals(reactions, auditActions(id, SlaMonitor.ACTOR), "nothing twice")
        assertEquals(listOf("INCIDENT_ASSIGNED"), inboxOf(tech.id, id))
    }

    @Test fun `nobody eligible escalates at once instead of after the unassigned wait`() {
        val reporter = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN)
        jdbc.update("UPDATE technician_profiles SET available = FALSE")
        val id = report(reporter, service())

        val result = assignment.assignAutomatically(id, null, admin.asUser())

        assertEquals(AssignmentOutcome.NO_ELIGIBLE_TECHNICIAN, result.decision.outcome)
        assertEquals(listOf("UNASSIGNED"), jdbc.queryForList("SELECT trigger FROM incident_escalations WHERE incident_id = ?", String::class.java, id))
        val metadata = t.json.readTree(jdbc.queryForObject(
            "SELECT metadata::text FROM audit_events WHERE action = 'INCIDENT_ESCALATED' AND entity_id = ?", String::class.java, id
        ))
        assertEquals("NO_ELIGIBLE_TECHNICIAN", metadata["reason"].asText())
        assertTrue("ESCALATED" in inboxOf(admin.id, id), "the active admins are told: ${inboxOf(admin.id, id)}")
    }

    @Test fun `a failing reaction never fails the change, and the sweep catches up`() {
        val reporter = t.caller(Role.EMPLOYEE)
        policies.failing = true

        val id = report(reporter, service()) // 201: P2's change committed although P4's SLA reaction threw
        assertEquals("CLASSIFIED", jdbc.queryForObject("SELECT state FROM incidents WHERE id = ?", String::class.java, id))
        assertEquals(null, slaRow(id), "the failed evaluation wrote nothing")

        policies.failing = false
        sla.runAt(Instant.now())
        assertEquals("P2", slaRow(id)!!["priority"], "the safety-net sweep started it")
    }
}
