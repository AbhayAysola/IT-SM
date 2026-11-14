package com.itsm.analytics

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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The analytics summary over the real stack. The incidents are dated in March 2001, far from anything else in the
 * database, so every figure can be asserted exactly. The SLA and escalation facts are produced by the real monitors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsIntegrationTest {

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
    @Autowired lateinit var sla: SlaMonitor
    @Autowired lateinit var escalations: EscalationMonitor

    private lateinit var t: AdminApiTestSupport

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private fun ts(iso: String?) = iso?.let { Instant.parse(it).atOffset(ZoneOffset.UTC) }

    private fun call(caller: Caller?, path: String, expect: Int): JsonNode {
        val req = get(path).apply { if (caller != null) header("Authorization", caller.bearer) }
        val response = mvc.perform(req).andReturn().response
        assertEquals(expect, response.status, "GET $path -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    private fun incident(
        reporter: Long, serviceId: Long, state: String, assignee: Long?, created: String,
        firstResponse: String? = null, resolved: String? = null
    ): String {
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        val category = jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, category_id, service_id, priority,
                                      created_at, updated_at, first_response_at, resolved_at)
               VALUES (?, 'Analytics case', 'For the dashboard', ?, ?, ?, ?, ?, ?, 'P2', ?, ?, ?, ?)""",
            id, state, reporter, assignee, ts(if (assignee != null) created else null), category, serviceId,
            ts(created), ts(created), ts(firstResponse), ts(resolved)
        )
        return id
    }

    @Test fun `the summary counts volume, SLA compliance, times, escalations, days and technicians for the range`() {
        val admin = t.caller(Role.ADMIN); val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        jdbc.update("INSERT INTO technician_profiles (user_id, max_workload, experience_years, available) VALUES (?, 6, 2, TRUE)", tech.id)
        val svcName = AdminApiTestSupport.unique("Analytics svc ")
        val svc = jdbc.queryForObject("INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, svcName)!!
        // P2 on this service: respond in 10 minutes, resolve in 60.
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes) VALUES (?, 'P2', 10, 60)", svc)

        // Met both clocks: responded after 5 minutes, resolved after 40, rated 5.
        val met = incident(reporter.id, svc, "VERIFIED", tech.id, "2001-03-01T10:00:00Z", "2001-03-01T10:05:00Z", "2001-03-01T10:40:00Z")
        // Breached both: responded after 20 minutes, resolved after 120, rated 3.
        val late = incident(reporter.id, svc, "VERIFIED", tech.id, "2001-03-02T09:00:00Z", "2001-03-02T09:20:00Z", "2001-03-02T11:00:00Z")
        // Still nobody on it: both clocks breach, and it escalates.
        incident(reporter.id, svc, "CLASSIFIED", null, "2001-03-02T12:00:00Z")
        jdbc.update("INSERT INTO incident_feedback (incident_id, reporter_id, rating, submitted_at) VALUES (?, ?, 5, ?)", met, reporter.id, ts("2001-03-01T12:00:00Z"))
        jdbc.update("INSERT INTO incident_feedback (incident_id, reporter_id, rating, submitted_at) VALUES (?, ?, 3, ?)", late, reporter.id, ts("2001-03-02T12:00:00Z"))

        sla.runAt(Instant.parse("2001-03-03T00:00:00Z"))
        escalations.runAt(Instant.parse("2001-03-03T00:00:00Z"))

        val s = call(admin, "/api/admin/analytics/summary?from=2001-03-01&to=2001-03-03", 200)
        assertEquals("2001-03-01", s["from"].asText()); assertEquals("2001-03-03", s["to"].asText())

        assertEquals(3, s["incidents"]["reported"].asLong())
        assertEquals(2, s["incidents"]["resolved"].asLong())
        assertTrue(s["incidents"]["unassignedNow"].asLong() >= 1, "open counts are a snapshot of now")

        val overall = s["sla"]["overall"]
        assertEquals(3, overall["tracked"].asLong())
        assertEquals(1, overall["responseMet"].asLong()); assertEquals(2, overall["responseBreached"].asLong())
        assertEquals(1, overall["resolutionMet"].asLong()); assertEquals(2, overall["resolutionBreached"].asLong())
        assertEquals(33.3, overall["resolutionCompliancePct"].asDouble())
        assertEquals("P2", s["sla"]["byPriority"][0]["name"].asText())
        assertEquals(svcName, s["sla"]["byService"][0]["name"].asText())
        assertEquals("Network", s["sla"]["byCategory"][0]["name"].asText())

        // Respond: 5 and 20 minutes. Resolve: 40 and 120 minutes. p90 interpolates between the two.
        assertEquals(2, s["times"]["toRespond"]["count"].asLong())
        assertEquals(12.5, s["times"]["toRespond"]["meanMinutes"].asDouble(), 0.001)
        assertEquals(18.5, s["times"]["toRespond"]["p90Minutes"].asDouble(), 0.001)
        assertEquals(80.0, s["times"]["toResolve"]["meanMinutes"].asDouble(), 0.001)
        assertEquals(112.0, s["times"]["toResolve"]["p90Minutes"].asDouble(), 0.001)

        // The unassigned incident: both breaches, 150 %, 200 %, and unassigned.
        assertEquals(5, s["escalations"]["total"].asLong())
        assertEquals(1, s["escalations"]["byTrigger"]["UNASSIGNED"].asLong())

        val days = s["perDay"]
        assertEquals(3, days.size())
        assertEquals(listOf(1L, 1L, 0L, 0L), listOf(days[0]["reported"], days[0]["resolved"], days[0]["breaches"], days[0]["escalations"]).map { it.asLong() })
        // 2 March: two reported, one resolved, four breaches (both clocks of the late and the unassigned incidents).
        assertEquals(listOf(2L, 1L, 4L, 0L), listOf(days[1]["reported"], days[1]["resolved"], days[1]["breaches"], days[1]["escalations"]).map { it.asLong() })
        assertEquals(5, days[2]["escalations"].asLong())

        val row = s["technicians"].single { it["userId"].asLong() == tech.id }
        assertEquals(2, row["resolved"].asLong()); assertEquals(2, row["ratings"].asLong())
        assertEquals(4.0, row["averageRating"].asDouble()); assertEquals(0, row["activeIncidents"].asInt()); assertEquals(6, row["maxWorkload"].asInt())

        // A range with nothing in it is all zeros, never an error.
        val empty = call(admin, "/api/admin/analytics/summary?from=1999-01-01&to=1999-01-07", 200)
        assertEquals(0, empty["incidents"]["reported"].asLong())
        assertTrue(empty["sla"]["overall"]["resolutionCompliancePct"].isNull)
        assertEquals(7, empty["perDay"].size())
    }

    @Test fun `only admins see analytics, the default range is the last 30 days, and bad ranges are refused`() {
        val admin = t.caller(Role.ADMIN)
        call(t.caller(Role.TECHNICIAN), "/api/admin/analytics/summary", 403)
        call(t.caller(Role.EMPLOYEE), "/api/admin/analytics/summary", 403)
        call(null, "/api/admin/analytics/summary", 401)

        assertEquals(30, call(admin, "/api/admin/analytics/summary", 200)["perDay"].size())
        assertEquals("from", call(admin, "/api/admin/analytics/summary?from=2001-03-05&to=2001-03-01", 400)["errors"][0]["field"].asText())
        assertEquals("to", call(admin, "/api/admin/analytics/summary?from=2000-01-01&to=2001-03-01", 400)["errors"][0]["field"].asText())
        call(admin, "/api/admin/analytics/summary?from=yesterday", 400)
    }
}
