package com.itsm.integration

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.sla.SlaMonitor
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
 * P3's incident list with P4's SLA facts (deadline column, nearest-deadline sort, predicted-miss filter, attention counts),
 * over HTTP on PostgreSQL 16 with the real SLA monitor. Each test titles its incidents with its own token and searches for
 * it, so tests do not see each other's incidents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class IncidentListSlaIntegrationTest {

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
    @Autowired lateinit var monitor: SlaMonitor

    private lateinit var t: AdminApiTestSupport
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private fun ago(minutes: Long) = now.minus(Duration.ofMinutes(minutes))
    private fun ts(i: Instant) = i.atOffset(ZoneOffset.UTC)

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    /** A service whose P2 policy is: respond in 10 minutes, resolve in 100, warn at 50 %. */
    private fun service(): Long {
        val id = jdbc.queryForObject(
            "INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("list-svc-")
        )!!
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, 'P2', 10, 100, 50)", id)
        return id
    }

    /** Being worked on since [createdAt] (responded to after 5 minutes). */
    private fun working(token: String, reporter: Long, tech: Long, serviceId: Long, createdAt: Instant): String {
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, first_response_at, category_id, service_id, priority, created_at, updated_at)
               VALUES (?, ?, 'd', 'IN_PROGRESS', ?, ?, ?, ?, ?, ?, 'P2', ?, ?)""",
            id, "Outage $token", reporter, tech, ts(createdAt), ts(createdAt.plus(Duration.ofMinutes(5))), category, serviceId, ts(createdAt), ts(createdAt)
        )
        return id
    }

    /** P4's predictive risk said this one will miss its deadline (as `SlaRiskDetector` records it). */
    private fun predictMiss(id: String) {
        val due = jdbc.queryForObject("SELECT resolution_due_at FROM incident_sla WHERE incident_id = ?", Timestamp::class.java, id)!!.toInstant()
        jdbc.update(
            """INSERT INTO incident_sla_risk (incident_id, raised_at, due_at, predicted_finish_at, basis, sample_size, typical_remaining_min, load_factor)
               VALUES (?, ?, ?, ?, 'CATEGORY', 5, 200, 1.00)""",
            id, ts(now), ts(due), ts(due.plus(Duration.ofHours(2)))
        )
    }

    private fun list(caller: Caller, query: String, expect: Int = 200): JsonNode {
        val response = mvc.perform(get("/api/incidents?$query").header("Authorization", caller.bearer)).andReturn().response
        assertEquals(expect, response.status, "GET /api/incidents?$query -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString)
    }

    private fun ids(page: JsonNode) = page["items"].map { it["id"].asText() }

    private class World(val token: String, val reporter: Caller, val tech: Caller, val onTrack: String, val warned: String, val breached: String, val predicted: String, val untracked: String)

    /** Four tracked incidents (on track, warned, breached, predicted to miss) and one SLA tracking has not reached. */
    private fun world(): World {
        val token = UUID.randomUUID().toString().take(8)
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN); val svc = service()
        val onTrack = working(token, reporter.id, tech.id, svc, ago(30))    // due in 70 minutes
        val warned = working(token, reporter.id, tech.id, svc, ago(60))     // 60 % used: warned, due in 40
        val breached = working(token, reporter.id, tech.id, svc, ago(200))  // due 100 minutes ago
        val predicted = working(token, reporter.id, tech.id, svc, ago(10))  // due in 90 minutes, predicted late
        val untracked = jdbc.queryForObject(
            "INSERT INTO incidents (title, description, reporter_id) VALUES (?, 'd', ?) RETURNING id", String::class.java, "Outage $token", reporter.id
        )!!
        monitor.runAt(now)
        predictMiss(predicted)
        return World(token, reporter, tech, onTrack, warned, breached, predicted, untracked)
    }

    @Test fun `rows carry their SLA, and the nearest deadline comes first`() {
        val w = world(); val admin = t.caller(Role.ADMIN)

        val page = list(admin, "q=${w.token}&sort=DEADLINE")
        assertEquals(listOf(w.breached, w.warned, w.onTrack, w.predicted, w.untracked), ids(page), "past deadline first, untracked last")

        val byId = page["items"].associateBy { it["id"].asText() }
        assertEquals("BREACHED", byId.getValue(w.breached)["sla"]["status"].asText())
        assertEquals("WARNING", byId.getValue(w.warned)["sla"]["status"].asText())
        assertEquals(now.plus(Duration.ofMinutes(70)).toString(), byId.getValue(w.onTrack)["sla"]["resolutionDueAt"].asText())
        assertTrue(byId.getValue(w.predicted)["sla"]["predictedMiss"].asBoolean())
        assertFalse(byId.getValue(w.onTrack)["sla"]["predictedMiss"].asBoolean())
        assertTrue(byId.getValue(w.untracked)["sla"].isNull, "not tracked yet")

        assertEquals(5, list(admin, "q=${w.token}")["items"].size(), "the default order still lists everything")
        list(admin, "q=${w.token}&sort=SOONEST", expect = 400)
    }

    @Test fun `predicted misses can be filtered, and the counts say what each SLA filter would show`() {
        val w = world(); val admin = t.caller(Role.ADMIN)

        assertEquals(listOf(w.predicted), ids(list(admin, "q=${w.token}&predictedMiss=true")))

        val all = list(admin, "q=${w.token}")["slaAttention"]
        assertEquals(listOf(1L, 1L, 1L), listOf(all["breached"].asLong(), all["atRisk"].asLong(), all["predictedMiss"].asLong()))
        val filtered = list(admin, "q=${w.token}&slaStatus=BREACHED")
        assertEquals(listOf(w.breached), ids(filtered))
        assertEquals(1L, filtered["slaAttention"]["predictedMiss"].asLong(), "the SLA filters do not narrow the counts")

        // Once the predicted incident is resolved its clock is met: no longer a predicted miss.
        jdbc.update("UPDATE incidents SET state = 'RESOLVED', resolved_at = ?, updated_at = ? WHERE id = ?", ts(now), ts(now), w.predicted)
        monitor.runAt(now.plusSeconds(60))
        assertEquals(emptyList<String>(), ids(list(admin, "q=${w.token}&predictedMiss=true")))
    }

    @Test fun `a technician only sees and counts their own incidents`() {
        val w = world()
        val other = t.caller(Role.TECHNICIAN)
        val theirs = list(other, "q=${w.token}")
        assertEquals(emptyList<String>(), ids(theirs))
        assertEquals(0L, theirs["slaAttention"]["breached"].asLong())
        assertEquals(4, list(w.tech, "q=${w.token}")["items"].size(), "the assignee sees the four assigned to them")
    }
}
