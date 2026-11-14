package com.itsm.escalation

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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Escalation over the real stack: V41 on PostgreSQL 16, the real SLA monitor feeding it, P1's real reporting lines
 * (`UserDirectory.escalationChain` and `listAdmins`), P2's access policy, audit persistence and the HTTP API. Both monitors
 * are run at chosen times; the background schedules are off in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EscalationIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var escalations: EscalationMonitor
    @Autowired lateinit var sla: SlaMonitor
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var tx: TransactionTemplate

    private lateinit var t: AdminApiTestSupport
    private val base: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private fun at(minutes: Long) = base.plus(Duration.ofMinutes(minutes))
    private fun ts(i: Instant?) = i?.atOffset(ZoneOffset.UTC)

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    /** Runs the SLA monitor, then escalation, at the same moment, as the schedules do one after the other. */
    private fun runBoth(minute: Long) {
        sla.runAt(at(minute))
        escalations.runAt(at(minute))
    }

    // --- data ---------------------------------------------------------------------------------------------------------

    private class Line(val tech: Caller, val lead: Caller, val manager: Caller)

    /** technician -> team lead -> manager, as P1 stores reporting lines. */
    private fun reportingLine(): Line {
        val manager = t.caller(Role.TECHNICIAN); val lead = t.caller(Role.TECHNICIAN); val tech = t.caller(Role.TECHNICIAN)
        val insert = "INSERT INTO technician_profiles (user_id, max_workload, experience_years, available, is_team_lead, reports_to_user_id) VALUES (?, 5, 3, TRUE, ?, ?)"
        jdbc.update(insert, manager.id, true, null)
        jdbc.update(insert, lead.id, true, manager.id)
        jdbc.update(insert, tech.id, false, lead.id)
        return Line(tech, lead, manager)
    }

    private fun service(): Long = jdbc.queryForObject(
        "INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("esc-svc-")
    )!!

    /** A service-specific P2 policy: respond in 10 minutes, resolve in 60. */
    private fun policy(serviceId: Long) {
        jdbc.update("INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, 'P2', 10, 60, 50)", serviceId)
    }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    private fun incident(reporter: Long, serviceId: Long, assignee: Long?): String {
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, category_id, service_id, priority, created_at, updated_at)
               VALUES (?, 'Switch down', 'Floor 3 offline', ?, ?, ?, ?, ?, ?, 'P2', ?, ?)""",
            id, if (assignee == null) "CLASSIFIED" else "ASSIGNED", reporter, assignee, ts(assignee?.let { base }), category, serviceId, ts(base), ts(base)
        )
        return id
    }

    private data class Row(val trigger: String, val tier: String, val targets: List<Long>)

    private fun logged(id: String): List<Row> = jdbc.query(
        "SELECT trigger, tier, target_user_ids FROM incident_escalations WHERE incident_id = ? ORDER BY id",
        { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            Row(rs.getString(1), rs.getString(2), (rs.getArray(3).array as Array<Long>).toList())
        },
        id
    )

    private fun adminIds(): Set<Long> = jdbc.queryForList("SELECT id FROM users WHERE role = 'ADMIN' AND active", Long::class.java).toSet()

    private fun call(caller: Caller?, path: String, expect: Int, body: Any? = null): JsonNode {
        val req = (if (body == null) get(path) else put(path).contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body)))
            .apply { if (caller != null) header("Authorization", caller.bearer) }
        val response = mvc.perform(req).andReturn().response
        assertEquals(expect, response.status, "$path -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    // --- monitor ------------------------------------------------------------------------------------------------------

    @Test fun `an overdue incident climbs the reporting line once per step, then reaches the admins`() {
        val line = reportingLine(); val reporter = t.caller(Role.EMPLOYEE); t.caller(Role.ADMIN)
        val svc = service(); policy(svc)
        val id = incident(reporter.id, svc, assignee = line.tech.id)

        runBoth(5)
        assertEquals(emptyList<Row>(), logged(id), "on track: nothing to escalate")

        runBoth(11) // response breached at 10
        assertEquals(listOf(Row("RESPONSE_BREACHED", "TEAM_LEAD", listOf(line.lead.id))), logged(id))
        runBoth(12)
        assertEquals(1, logged(id).size, "never twice")

        runBoth(61) // resolution breached at 60
        assertEquals(Row("RESOLUTION_BREACHED", "TEAM_LEAD", listOf(line.lead.id)), logged(id)[1])
        runBoth(90) // 150 % of 60 minutes
        assertEquals(Row("RESOLUTION_OVERDUE", "NEXT_LEVEL", listOf(line.manager.id)), logged(id)[2])
        runBoth(120) // 200 %
        val critical = logged(id)[3]
        assertEquals("RESOLUTION_CRITICAL", critical.trigger); assertEquals("ADMINS", critical.tier)
        assertEquals(adminIds(), critical.targets.toSet())

        val audits = jdbc.queryForList(
            "SELECT actor_type || ':' || actor_name || ':' || action FROM audit_events WHERE entity_type = 'INCIDENT' AND entity_id = ? AND action = 'INCIDENT_ESCALATED' ORDER BY id",
            String::class.java, id
        )
        assertEquals(List(4) { "SYSTEM:ESCALATION_MONITOR:INCIDENT_ESCALATED" }, audits)
    }

    @Test fun `an incident nobody picks up goes to the admins`() {
        val reporter = t.caller(Role.EMPLOYEE); t.caller(Role.ADMIN)
        val svc = service(); policy(svc)
        val id = incident(reporter.id, svc, assignee = null)
        escalations.runAt(at(29))
        assertFalse(logged(id).any { it.trigger == "UNASSIGNED" })
        escalations.runAt(at(31))
        val row = logged(id).single { it.trigger == "UNASSIGNED" }
        assertEquals("ADMINS", row.tier); assertEquals(adminIds(), row.targets.toSet())
    }

    @Test fun `the escalation log is append-only`() {
        val reporter = t.caller(Role.EMPLOYEE); t.caller(Role.ADMIN)
        val id = incident(reporter.id, service(), assignee = null)
        escalations.runAt(at(31))
        assertTrue(runCatching { jdbc.update("UPDATE incident_escalations SET tier = 'TEAM_LEAD' WHERE incident_id = ?", id) }.isFailure)
        assertTrue(runCatching { jdbc.update("DELETE FROM incident_escalations WHERE incident_id = ?", id) }.isFailure)
        assertEquals(1, logged(id).size)
    }

    @Test fun `a run is skipped while another holds the escalation lock`() {
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = thread {
            tx.execute {
                jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, EscalationStore.MONITOR_LOCK_KEY)
                locked.countDown(); release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(locked.await(30, TimeUnit.SECONDS))
            assertTrue(escalations.runAt(at(1)).skipped)
        } finally {
            release.countDown(); holder.join(30_000)
        }
        assertFalse(escalations.runAt(at(1)).skipped)
    }

    // --- settings API -------------------------------------------------------------------------------------------------

    @Test fun `admins configure when escalation fires, and changes are validated, versioned and audited`() {
        val admin = t.caller(Role.ADMIN); val tech = t.caller(Role.TECHNICIAN)
        val path = "/api/admin/escalation-settings"
        val current = call(admin, path, 200)
        val version = current["version"].asLong()
        try {
            call(tech, path, 403)
            call(null, path, 401)
            val invalid = call(admin, path, 400, mapOf("unassignedMinutes" to 0, "secondLevelPct" to 150, "adminLevelPct" to 120, "version" to version))
            assertEquals(setOf("unassignedMinutes", "adminLevelPct"), invalid["errors"].map { it["field"].asText() }.toSet())
            call(admin, path, 409, mapOf("unassignedMinutes" to 5, "secondLevelPct" to 150, "adminLevelPct" to 200, "version" to version + 7))

            val saved = call(admin, path, 200, mapOf("unassignedMinutes" to 5, "secondLevelPct" to 150, "adminLevelPct" to 200, "version" to version))
            assertEquals(5, saved["unassignedMinutes"].asInt()); assertEquals(version + 1, saved["version"].asLong())
            assertEquals(admin.id, saved["updatedByUserId"].asLong())
            val audit = jdbc.queryForObject(
                "SELECT metadata::text FROM audit_events WHERE action = 'ESCALATION_SETTINGS_UPDATED' ORDER BY id DESC LIMIT 1", String::class.java
            )
            assertTrue(audit!!.contains("\"unassignedMinutesAfter\": 5"), audit)

            // The new setting takes effect on the next run.
            val id = incident(t.caller(Role.EMPLOYEE).id, service(), assignee = null)
            escalations.runAt(at(6))
            assertTrue(logged(id).any { it.trigger == "UNASSIGNED" })
        } finally {
            val now = call(admin, path, 200)
            call(admin, path, 200, mapOf("unassignedMinutes" to 30, "secondLevelPct" to 150, "adminLevelPct" to 200, "version" to now["version"].asLong()))
        }
    }

    // --- incident escalations API -------------------------------------------------------------------------------------

    @Test fun `the escalation history is visible to staff who may see the incident, with names`() {
        val line = reportingLine(); val reporter = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN)
        val stranger = t.caller(Role.TECHNICIAN)
        val svc = service(); policy(svc)
        val id = incident(reporter.id, svc, assignee = line.tech.id)
        runBoth(11)
        val path = "/api/incidents/$id/escalations"

        val body = call(line.tech, path, 200)
        assertEquals(1, body.size())
        assertEquals("RESPONSE_BREACHED", body[0]["trigger"].asText()); assertEquals("TEAM_LEAD", body[0]["tier"].asText())
        assertEquals(line.lead.id, body[0]["targets"][0]["id"].asLong())
        assertEquals("Test TECHNICIAN", body[0]["targets"][0]["name"].asText())
        assertEquals(line.tech.id, body[0]["assigneeId"].asLong())

        call(admin, path, 200)
        assertEquals("FORBIDDEN", call(reporter, path, 403)["code"].asText(), "reporters do not see who it was escalated to")
        call(stranger, path, 403)
        call(null, path, 401)
        call(admin, "/api/incidents/INCNOSUCH/escalations", 404)
    }
}
