package com.itsm.integration

import com.fasterxml.jackson.databind.JsonNode
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Changing an incident's priority over the real stack: P2's endpoint writes it and publishes `IncidentPriorityChanged`;
 * P4 recalculates the SLA deadlines (the V44 rule, so a raise never breaches at once) and tells the assignee. The
 * listeners are on in this class; no sweep runs, so the SLA change and the notification come from the event.
 */
@SpringBootTest(properties = ["app.p4.event-listeners.enabled=true"])
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class IncidentPriorityChangeIntegrationTest {

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
    @Autowired lateinit var monitor: com.itsm.sla.SlaMonitor

    private lateinit var t: AdminApiTestSupport
    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }
    private fun ts(i: Instant) = i.atOffset(ZoneOffset.UTC)

    /** A service with P3 (respond 60, resolve 480) and P1 (respond 5, resolve 30) policies. */
    private fun service(): Long {
        val id = jdbc.queryForObject(
            "INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("prio-svc-")
        )!!
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, 'P3', 60, 480, 80)", id)
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, 'P1', 5, 30, 80)", id)
        return id
    }

    /** A P3 incident being worked on for [minutesAgo] minutes, responded to after 10, with its SLA tracked. */
    private fun working(reporter: Long, tech: Long, minutesAgo: Long, state: String = "IN_PROGRESS"): String {
        val created = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(minutesAgo))
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, first_response_at, resolved_at, category_id, service_id, priority, created_at, updated_at)
               VALUES (?, 'Mail delayed', 'Hours late', ?, ?, ?, ?, ?, ?, ?, ?, 'P3', ?, ?)""",
            id, state, reporter, tech, ts(created), ts(created.plus(Duration.ofMinutes(10))),
            if (state == "RESOLVED") ts(Instant.now()) else null, category, service(), ts(created), ts(created)
        )
        monitor.runAt(Instant.now())
        return id
    }

    private fun change(caller: Caller, id: String, priority: String, reason: String?, expect: Int): JsonNode {
        val body = buildMap { put("priority", priority); reason?.let { put("reason", it) } }
        val response = mvc.perform(
            post("/api/incidents/$id/priority").header("Authorization", caller.bearer).contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))
        ).andReturn().response
        assertEquals(expect, response.status, "POST priority -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    private fun sla(id: String) = jdbc.queryForMap("SELECT * FROM incident_sla WHERE incident_id = ?", id)
    private fun instant(v: Any?) = (v as Timestamp).toInstant()

    @Test fun `an admin raises the priority, the SLA follows from the change, and the assignee is told`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN); val admin = t.caller(Role.ADMIN)
        val id = working(reporter.id, tech.id, minutesAgo = 120) // P3: resolution due in 360 minutes

        val result = change(admin, id, "P1", "The whole finance team is now affected", 200)
        assertEquals("P1", result["priority"].asText()); assertEquals("P3", result["previousPriority"].asText())
        val changedAt = Instant.parse(result["changedAt"].asText())

        assertEquals("P1", jdbc.queryForObject("SELECT priority FROM incidents WHERE id = ?", String::class.java, id))
        val metadata = t.json.readTree(jdbc.queryForObject(
            "SELECT metadata::text FROM audit_events WHERE action = 'INCIDENT_PRIORITY_CHANGED' AND entity_id = ?", String::class.java, id
        ))
        assertEquals("The whole finance team is now affected", metadata["reason"].asText())

        // Recalculated by the event, without a sweep: 30 minutes from the change, not from the report (that would be breached).
        val row = sla(id)
        assertEquals("P1", row["priority"]); assertEquals("RUNNING", row["resolution_status"])
        val due = instant(row["resolution_due_at"])
        val expected = changedAt.plus(Duration.ofMinutes(30))
        assertTrue(!due.isBefore(expected) && due.isBefore(expected.plusSeconds(1)), "due $due, expected $expected (to the second, never earlier)")
        assertEquals("MET", row["response_status"], "the response was already met; a tighter target does not breach it")
        assertTrue("SLA_RECALCULATED" in jdbc.queryForList("SELECT action FROM audit_events WHERE entity_id = ?", String::class.java, id))

        assertEquals(listOf("PRIORITY_CHANGED"), jdbc.queryForList(
            "SELECT type FROM notifications WHERE recipient_id = ? AND incident_id = ?", String::class.java, tech.id, id
        ))
    }

    @Test fun `only admins may change it, with a reason, to another priority, while it is worked on`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN); val admin = t.caller(Role.ADMIN)
        val id = working(reporter.id, tech.id, minutesAgo = 30)

        change(tech, id, "P1", "Urgent", 403)
        change(reporter, id, "P1", "Urgent", 403)
        change(admin, id, "P1", null, 400)
        change(admin, id, "P1", "   ", 400)
        change(admin, id, "P9", "Urgent", 400)
        change(admin, id, "P3", "Same again", 400)
        change(admin, "INC" + "0".repeat(10), "P1", "Urgent", 404)
        change(admin, working(reporter.id, tech.id, 30, state = "RESOLVED"), "P1", "Too late", 400)
        assertEquals("P3", jdbc.queryForObject("SELECT priority FROM incidents WHERE id = ?", String::class.java, id), "nothing changed")
    }
}
