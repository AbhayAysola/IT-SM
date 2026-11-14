package com.itsm.priority

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * Priority suggestions over the real stack: incidents reported through P2's intake (so the answers and duplicate flags are
 * stored as in production), P4's real priority policy with P1's catalog, the monitor run at chosen times, P2's priority
 * endpoint to apply a suggestion, notifications and the suggestion API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PrioritySuggestionIntegrationTest {

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
    @Autowired lateinit var monitor: PrioritySuggestionMonitor

    private lateinit var t: AdminApiTestSupport
    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    private fun service(criticality: Int = 3): Long = jdbc.queryForObject(
        "INSERT INTO it_services (name, business_criticality) VALUES (?, ?) RETURNING id", Long::class.java, AdminApiTestSupport.unique("sugg-svc-"), criticality
    )!!

    private fun call(caller: Caller, method: String, path: String, body: Any?, expect: Int): JsonNode {
        val req = (if (method == "GET") get(path) else post(path)).header("Authorization", caller.bearer)
            .apply { if (body != null) contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body)) }
        val response = mvc.perform(req).andReturn().response
        assertEquals(expect, response.status, "$method $path -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    /** Reported through intake. MEDIUM impact and HIGH urgency give P2 on an ordinary service (criticality 3). */
    private fun report(reporter: Caller, serviceId: Long, impact: String = "MEDIUM", urgency: String = "HIGH", duplicateOf: String? = null): String =
        call(reporter, "POST", "/api/incidents", mapOf(
            "title" to "Wi-Fi down", "description" to "No connection in Block C", "categoryId" to category, "serviceId" to serviceId,
            "impact" to impact, "urgency" to urgency, "aiDuplicateIds" to duplicateOf?.let { listOf(it) }
        ), 201)["id"].asText()

    private fun suggestion(admin: Caller, id: String): JsonNode? {
        val response = mvc.perform(get("/api/incidents/$id/priority-suggestion").header("Authorization", admin.bearer)).andReturn().response
        return when (response.status) { 204 -> null; 200 -> t.json.readTree(response.contentAsString); else -> error("GET suggestion -> ${response.status}") }
    }

    private fun rows(id: String) = jdbc.queryForList("SELECT suggested_priority, status FROM incident_priority_suggestions WHERE incident_id = ? ORDER BY id", id)
    private fun inbox(userId: Long, id: String) =
        jdbc.queryForList("SELECT type FROM notifications WHERE recipient_id = ? AND incident_id = ?", String::class.java, userId, id)

    @Test fun `a service that became mission-critical gets a suggestion once, and applying it closes it`() {
        val reporter = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN); val svc = service(criticality = 3)
        val id = report(reporter, svc)
        assertEquals("P2", jdbc.queryForObject("SELECT priority FROM incidents WHERE id = ?", String::class.java, id))
        monitor.runAt(Instant.now())
        assertEquals(null, suggestion(admin, id), "nothing while it still fits")

        jdbc.update("UPDATE it_services SET business_criticality = 5 WHERE id = ?", svc)
        monitor.runAt(Instant.now())
        val s = suggestion(admin, id)!!
        assertEquals("P2", s["currentPriority"].asText()); assertEquals("P1", s["suggestedPriority"].asText())
        assertEquals("CRITICALITY", s["rules"][0].asText())
        assertTrue(s["reasons"][0].asText().contains("business criticality 5"), s["reasons"].toString())
        assertEquals(listOf("PRIORITY_SUGGESTED"), inbox(admin.id, id))
        assertTrue("PRIORITY_SUGGESTED" in jdbc.queryForList("SELECT action FROM audit_events WHERE entity_id = ?", String::class.java, id))

        monitor.runAt(Instant.now())
        assertEquals(1, rows(id).size, "suggested once")

        call(admin, "POST", "/api/incidents/$id/priority", mapOf("priority" to "P1", "reason" to "Applied the suggestion"), 200)
        assertEquals(null, suggestion(admin, id), "gone as soon as it is applied")
        monitor.runAt(Instant.now())
        assertEquals(listOf(mapOf("suggested_priority" to "P1", "status" to "APPLIED")), rows(id))
    }

    @Test fun `a dismissed suggestion is not made again, and an admin's own change is respected`() {
        val reporter = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN)
        val svc = service(criticality = 3)
        val dismissed = report(reporter, svc)
        val overridden = report(reporter, svc)
        call(admin, "POST", "/api/incidents/$overridden/priority", mapOf("priority" to "P3", "reason" to "Only one person affected"), 200)
        jdbc.update("UPDATE it_services SET business_criticality = 5 WHERE id = ?", svc)

        monitor.runAt(Instant.now())
        assertEquals(emptyList<Map<String, Any>>(), rows(overridden), "the admin chose P3 by hand")
        call(admin, "POST", "/api/incidents/$dismissed/priority-suggestion/dismiss", null, 204)
        assertEquals(null, suggestion(admin, dismissed))
        monitor.runAt(Instant.now())
        assertEquals(listOf(mapOf("suggested_priority" to "P1", "status" to "DISMISSED")), rows(dismissed))
        call(admin, "POST", "/api/incidents/$dismissed/priority-suggestion/dismiss", null, 404)
    }

    @Test fun `three new reports flagged as duplicates suggest raising it one level`() {
        val reporter = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN); val svc = service(criticality = 3)
        val original = report(reporter, svc, impact = "LOW", urgency = "MEDIUM")
        val before = jdbc.queryForObject("SELECT priority FROM incidents WHERE id = ?", String::class.java, original)!!
        repeat(2) { report(reporter, svc, duplicateOf = original) }
        monitor.runAt(Instant.now())
        assertEquals(null, suggestion(admin, original), "two are not yet a pattern")

        report(reporter, svc, duplicateOf = original)
        monitor.runAt(Instant.now())
        val s = suggestion(admin, original)!!
        assertEquals(before, s["currentPriority"].asText())
        assertEquals("P" + (before.last().digitToInt() - 1), s["suggestedPriority"].asText())
        assertEquals("DUPLICATES", s["rules"][0].asText())
        assertTrue(s["reasons"][0].asText().startsWith("3 new reports"))
    }

    @Test fun `only admins read or dismiss suggestions`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        val id = report(reporter, service())
        call(tech, "GET", "/api/incidents/$id/priority-suggestion", null, 403)
        call(reporter, "POST", "/api/incidents/$id/priority-suggestion/dismiss", null, 403)
    }
}
