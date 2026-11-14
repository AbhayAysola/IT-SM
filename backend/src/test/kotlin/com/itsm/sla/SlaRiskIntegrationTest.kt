package com.itsm.sla

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Predictive SLA risk over the real stack: V40 and V43 on PostgreSQL 16, the real SLA monitor and its risk step, P1's real
 * policies and technician profiles, P3's real workload count, notifications and the SLA API. History is built the way it
 * happens in production: incidents are resolved and the monitor records how long they took. Each test uses its own
 * category and priority, so their histories do not mix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SlaRiskIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var monitor: SlaMonitor
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService

    private lateinit var t: AdminApiTestSupport
    /** History happens a day ago; the incidents under test start "now". */
    private val past: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(1))
    private val start: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private fun after(from: Instant, minutes: Long) = from.plus(Duration.ofMinutes(minutes))
    private fun ts(i: Instant?) = i?.atOffset(ZoneOffset.UTC)

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private fun category(): Long {
        val code = "RISK_" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        return jdbc.queryForObject("INSERT INTO incident_categories (code, name) VALUES (?, ?) RETURNING id", Long::class.java, code, "Risk test $code")!!
    }

    /** A service with one policy: respond in 30 minutes, resolve in 240, warn at 80 % (minute 192). */
    private fun service(priority: String): Long {
        val id = jdbc.queryForObject(
            "INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("risk-svc-")
        )!!
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, ?, 30, 240, 80)", id, priority)
        return id
    }

    private fun technician(maxWorkload: Int = 5): Caller {
        val tech = t.caller(Role.TECHNICIAN)
        jdbc.update(
            "INSERT INTO technician_profiles (user_id, max_workload, experience_years, available, is_team_lead, reports_to_user_id) VALUES (?, ?, 3, TRUE, FALSE, NULL)",
            tech.id, maxWorkload
        )
        return tech
    }

    /** An incident being worked on since [createdAt], already acknowledged. */
    private fun working(reporter: Long, tech: Long, serviceId: Long, categoryId: Long, priority: String, createdAt: Instant): String {
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, first_response_at, category_id, service_id, priority, created_at, updated_at)
               VALUES (?, 'VPN drops', 'Every few minutes', 'IN_PROGRESS', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, reporter, tech, ts(createdAt), ts(after(createdAt, 5)), categoryId, serviceId, priority, ts(createdAt), ts(createdAt)
        )
        return id
    }

    /** Past incidents of this kind that needed [minutes] each, resolved and recorded by the monitor. */
    private fun history(reporter: Long, tech: Long, serviceId: Long, categoryId: Long, priority: String, vararg minutes: Long) {
        for (m in minutes) {
            val id = working(reporter, tech, serviceId, categoryId, priority, past)
            jdbc.update("UPDATE incidents SET state = 'RESOLVED', resolved_at = ?, updated_at = ? WHERE id = ?", ts(after(past, m)), ts(after(past, m)), id)
        }
        monitor.runAt(after(past, 600))
    }

    private fun risks(id: String): List<Map<String, Any>> = jdbc.queryForList("SELECT * FROM incident_sla_risk WHERE incident_id = ?", id)
    private fun inbox(userId: Long, id: String): List<String> =
        jdbc.queryForList("SELECT type FROM notifications WHERE recipient_id = ? AND incident_id = ? ORDER BY id", String::class.java, userId, id)

    @Test fun `a kind of incident that usually overruns is flagged before its warning, once, and the assignee is told`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = technician(); val svc = service("P3"); val cat = category()
        history(reporter.id, tech.id, svc, cat, "P3", 300, 320, 340, 360, 380)
        val id = working(reporter.id, tech.id, svc, cat, "P3", start)

        monitor.runAt(after(start, 10)) // 10 of 240 minutes used: far from the minute-192 warning

        val risk = risks(id).single()
        assertEquals("CATEGORY", risk["basis"]); assertEquals(5, risk["sample_size"])
        assertEquals(330L, risk["typical_remaining_min"], "the median of what similar incidents still needed after 10 minutes")
        assertEquals(after(start, 340), (risk["predicted_finish_at"] as java.sql.Timestamp).toInstant())
        assertEquals(listOf("SLA_AT_RISK"), inbox(tech.id, id))
        assertTrue("SLA_RISK_PREDICTED" in jdbc.queryForList("SELECT action FROM audit_events WHERE entity_id = ?", String::class.java, id))

        val sla = t.json.readTree(mvc.perform(get("/api/incidents/$id/sla").header("Authorization", reporter.bearer)).andReturn().response.contentAsString)
        assertEquals("RUNNING", sla["status"].asText(), "a prediction is not a warning: the clock is still on track")
        assertEquals(after(start, 340).toString(), sla["risk"]["predictedFinishAt"].asText())
        assertEquals("CATEGORY", sla["risk"]["basis"].asText())

        monitor.runAt(after(start, 20))
        assertEquals(1, risks(id).size, "raised once")
        assertEquals(listOf("SLA_AT_RISK"), inbox(tech.id, id), "told once")
    }

    @Test fun `a kind of incident that is usually quick is not flagged, nor one with too little history`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = technician(); val svc = service("P2"); val cat = category()
        history(reporter.id, tech.id, svc, cat, "P2", 30, 40, 50, 60, 70)
        val quick = working(reporter.id, tech.id, svc, cat, "P2", start)

        val newKind = service("P4"); val newCat = category()
        history(reporter.id, tech.id, newKind, newCat, "P4", 500, 600) // two only: P4 does not guess
        val unknown = working(reporter.id, tech.id, newKind, newCat, "P4", start)

        monitor.runAt(after(start, 10))
        assertEquals(emptyList<Map<String, Any>>(), risks(quick))
        assertEquals(emptyList<Map<String, Any>>(), risks(unknown))
        val sla = t.json.readTree(mvc.perform(get("/api/incidents/$quick/sla").header("Authorization", reporter.bearer)).andReturn().response.contentAsString)
        assertTrue(sla["risk"].isNull)
    }

    @Test fun `an overloaded assignee tips an incident that would otherwise make it`() {
        val reporter = t.caller(Role.EMPLOYEE); val svc = service("P1"); val cat = category()
        val historian = technician()
        history(reporter.id, historian.id, svc, cat, "P1", 150, 160, 170, 180, 190)

        // 10 minutes in, similar incidents still needed 160 minutes: done around minute 170, inside the 240-minute target.
        val relaxed = technician(maxWorkload = 5)
        val fine = working(reporter.id, relaxed.id, svc, cat, "P1", start)
        // The same incident with an assignee holding two active incidents against a ceiling of one: 160 x 2 = 320 minutes.
        val busy = technician(maxWorkload = 1)
        val late = working(reporter.id, busy.id, svc, cat, "P1", start)
        working(reporter.id, busy.id, service("P3"), category(), "P3", start)

        monitor.runAt(after(start, 10))
        assertEquals(emptyList<Map<String, Any>>(), risks(fine))
        val risk = risks(late).single()
        assertEquals(0, (risk["load_factor"] as java.math.BigDecimal).compareTo(java.math.BigDecimal("2.00")))
        assertEquals(busy.id, risk["assignee_id"])
        assertEquals(listOf("SLA_AT_RISK"), inbox(busy.id, late))
    }
}
