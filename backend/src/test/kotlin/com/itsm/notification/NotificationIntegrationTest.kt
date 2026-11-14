package com.itsm.notification

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.escalation.EscalationMonitor
import com.itsm.sla.SlaMonitor
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Notifications over the real stack: V42 on PostgreSQL 16, the lifecycle monitor, the SLA and escalation monitors'
 * events, P1's real reporting lines and the inbox API. Monitors run at chosen times; the background schedules are off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var lifecycle: NotificationMonitor
    @Autowired lateinit var sla: SlaMonitor
    @Autowired lateinit var escalations: EscalationMonitor
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService

    private lateinit var t: AdminApiTestSupport
    private val base: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private fun at(minutes: Long) = base.plus(Duration.ofMinutes(minutes))
    private fun ts(i: Instant?) = i?.atOffset(ZoneOffset.UTC)

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    private fun service(): Long = jdbc.queryForObject(
        "INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("ntf-svc-")
    )!!

    private fun incident(reporter: Long, assignee: Long?, priority: String = "P3", serviceId: Long = service(), createdAt: Instant = base): String {
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, category_id, service_id, priority, created_at, updated_at)
               VALUES (?, 'Badge reader broken', 'Door 4 will not open', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, if (assignee == null) "CLASSIFIED" else "ASSIGNED", reporter, assignee, ts(assignee?.let { createdAt }), category, serviceId, priority, ts(createdAt), ts(createdAt)
        )
        return id
    }

    private fun move(id: String, state: String, assignee: Long?, firstResponse: Boolean = true, resolved: Boolean = false) {
        jdbc.update(
            """UPDATE incidents SET state = ?, assignee_id = ?, assigned_at = COALESCE(assigned_at, now()),
                   first_response_at = CASE WHEN ? THEN COALESCE(first_response_at, now()) END,
                   resolved_at = CASE WHEN ? THEN now() END, updated_at = now() WHERE id = ?""",
            state, assignee, firstResponse, resolved, id
        )
    }

    private fun inboxOf(userId: Long, incidentId: String): List<String> = jdbc.queryForList(
        "SELECT type FROM notifications WHERE recipient_id = ? AND incident_id = ? ORDER BY id", String::class.java, userId, incidentId
    )

    private fun call(caller: Caller?, method: String, path: String, expect: Int): JsonNode {
        val req = (if (method == "GET") get(path) else post(path)).apply { if (caller != null) header("Authorization", caller.bearer) }
        val response = mvc.perform(req).andReturn().response
        assertEquals(expect, response.status, "$method $path -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    // --- lifecycle ----------------------------------------------------------------------------------------------------

    @Test fun `each lifecycle step tells the person who must act, once`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        val id = incident(reporter.id, assignee = tech.id, createdAt = Instant.now()) // a fresh assignment P4 has not seen yet

        lifecycle.runAt(Instant.now())
        assertEquals(listOf("INCIDENT_ASSIGNED"), inboxOf(tech.id, id))
        assertEquals(listOf("ASSIGNMENT_MADE"), inboxOf(reporter.id, id))
        lifecycle.runAt(Instant.now())
        assertEquals(1, inboxOf(tech.id, id).size, "nothing new, nothing sent")

        move(id, "ACKNOWLEDGED", tech.id); move(id, "IN_PROGRESS", tech.id); lifecycle.runAt(Instant.now())
        move(id, "PENDING_INFO", tech.id); lifecycle.runAt(Instant.now())
        move(id, "IN_PROGRESS", tech.id); lifecycle.runAt(Instant.now())
        move(id, "RESOLVED", tech.id, resolved = true); lifecycle.runAt(Instant.now())
        move(id, "IN_PROGRESS", tech.id); lifecycle.runAt(Instant.now()) // reopened

        assertEquals(listOf("ASSIGNMENT_MADE", "INFO_REQUESTED", "INCIDENT_RESOLVED"), inboxOf(reporter.id, id))
        assertEquals(listOf("INCIDENT_ASSIGNED", "INCIDENT_REOPENED"), inboxOf(tech.id, id))
    }

    @Test fun `an incident first seen long after its assignment is a silent baseline`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        val id = incident(reporter.id, assignee = tech.id, createdAt = Instant.now().minus(Duration.ofHours(3)))
        lifecycle.runAt(Instant.now())
        assertEquals(emptyList<String>(), inboxOf(tech.id, id) + inboxOf(reporter.id, id))
        move(id, "ASSIGNED", t.caller(Role.TECHNICIAN).id) // reassigned: news again
        lifecycle.runAt(Instant.now())
        assertEquals(listOf("ASSIGNMENT_MADE"), inboxOf(reporter.id, id))
    }

    // --- SLA and escalation -------------------------------------------------------------------------------------------

    @Test fun `a breached P1 tells the assignee on every channel and the team lead it was escalated to`() {
        val reporter = t.caller(Role.EMPLOYEE); val lead = t.caller(Role.TECHNICIAN); val tech = t.caller(Role.TECHNICIAN)
        val insert = "INSERT INTO technician_profiles (user_id, max_workload, experience_years, available, is_team_lead, reports_to_user_id) VALUES (?, 5, 3, TRUE, ?, ?)"
        jdbc.update(insert, lead.id, true, null); jdbc.update(insert, tech.id, false, lead.id)
        val svc = service()
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, 'P1', 10, 60, 50)", svc)
        val id = incident(reporter.id, assignee = tech.id, priority = "P1", serviceId = svc)

        sla.runAt(at(6)); escalations.runAt(at(6))     // warning at 5 minutes
        sla.runAt(at(11)); escalations.runAt(at(11))   // response breached at 10, escalated to the team lead

        assertEquals(listOf("SLA_WARNING", "SLA_BREACHED"), inboxOf(tech.id, id))
        assertEquals(listOf("ESCALATED"), inboxOf(lead.id, id))
        val channels = jdbc.queryForObject("SELECT array_to_string(channels, ',') FROM notifications WHERE recipient_id = ? AND type = 'SLA_BREACHED'", String::class.java, tech.id)
        assertEquals("IN_APP,EMAIL,SMS", channels)
    }

    // --- inbox API ----------------------------------------------------------------------------------------------------

    @Test fun `everyone reads and clears only their own inbox`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN); val other = t.caller(Role.EMPLOYEE)
        val id = incident(reporter.id, assignee = tech.id, createdAt = Instant.now())
        lifecycle.runAt(Instant.now())
        move(id, "ACKNOWLEDGED", tech.id); move(id, "IN_PROGRESS", tech.id); move(id, "PENDING_INFO", tech.id)
        lifecycle.runAt(Instant.now())

        assertEquals(2L, call(reporter, "GET", "/api/notifications/unread-count", 200)["count"].asLong())
        val page = call(reporter, "GET", "/api/notifications", 200)
        assertEquals(2L, page["totalItems"].asLong())
        assertEquals("INFO_REQUESTED", page["items"][0]["type"].asText(), "newest first")
        assertEquals(id, page["items"][0]["incidentId"].asText())
        val first = page["items"][0]["id"].asLong()

        assertEquals("RESOURCE_NOT_FOUND", call(other, "POST", "/api/notifications/$first/read", 404)["code"].asText(), "not theirs")
        val read = call(reporter, "POST", "/api/notifications/$first/read", 200)
        assertTrue(!read["readAt"].isNull)
        call(reporter, "POST", "/api/notifications/$first/read", 200) // idempotent
        assertEquals(1L, call(reporter, "GET", "/api/notifications?unreadOnly=true", 200)["totalItems"].asLong())
        assertEquals(1, call(reporter, "POST", "/api/notifications/read-all", 200)["updated"].asInt())
        assertEquals(0L, call(reporter, "GET", "/api/notifications/unread-count", 200)["count"].asLong())
        assertEquals(0L, call(other, "GET", "/api/notifications", 200)["totalItems"].asLong())

        call(null, "GET", "/api/notifications", 401)
        call(reporter, "GET", "/api/notifications?size=0", 400)
    }

    @Test fun `a notification can only be marked read, once, and never deleted`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        val id = incident(reporter.id, assignee = tech.id, createdAt = Instant.now())
        lifecycle.runAt(Instant.now())
        val nid = jdbc.queryForObject("SELECT id FROM notifications WHERE recipient_id = ? AND incident_id = ?", Long::class.java, tech.id, id)!!
        assertTrue(runCatching { jdbc.update("UPDATE notifications SET title = 'changed' WHERE id = ?", nid) }.isFailure)
        assertTrue(runCatching { jdbc.update("DELETE FROM notifications WHERE id = ?", nid) }.isFailure)
        assertEquals(1, jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ?", nid))
        assertTrue(runCatching { jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ?", nid) }.isFailure, "read only once")
    }
}
