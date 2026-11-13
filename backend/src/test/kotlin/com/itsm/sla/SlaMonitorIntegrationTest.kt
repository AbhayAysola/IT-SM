package com.itsm.sla

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
 * The SLA monitor over the real stack: V40 on PostgreSQL 16, P1's real `SlaPolicyProvider`, P2's real access policy, audit
 * persistence and the HTTP API. Incidents are written with plain SQL (the table's CHECKs keep them consistent) and the
 * monitor is run at chosen times, so every step is deterministic. The background schedule is off in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SlaMonitorIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var monitor: SlaMonitor
    @Autowired lateinit var reader: IncidentSnapshotReader
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

    // --- data helpers --------------------------------------------------------------------------------------------------

    private fun service(): Long =
        jdbc.queryForObject("INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, AdminApiTestSupport.unique("sla-svc-"))!!

    private fun policy(serviceId: Long, priority: String, response: Int, resolution: Int, pct: Int): Long = jdbc.queryForObject(
        "INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) VALUES (?, ?, ?, ?, ?) RETURNING id",
        Long::class.java, serviceId, priority, response, resolution, pct
    )!!

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    private fun incident(reporter: Long, serviceId: Long, priority: String, assignee: Long? = null): String {
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            """INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, assigned_at, category_id, service_id, priority, created_at, updated_at)
               VALUES (?, 'Printer offline', 'Nothing prints', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, if (assignee == null) "CLASSIFIED" else "ASSIGNED", reporter, assignee, ts(assignee?.let { base }),
            category, serviceId, priority, ts(base), ts(base)
        )
        return id
    }

    /** Moves an incident the way P3 would, writing the columns its CHECKs require. */
    private fun move(id: String, state: String, minute: Long, assignee: Long?, firstResponse: Long? = null, resolved: Long? = null) {
        jdbc.update(
            "UPDATE incidents SET state = ?, assignee_id = ?, assigned_at = COALESCE(assigned_at, ?), first_response_at = ?, resolved_at = ?, updated_at = ? WHERE id = ?",
            state, assignee, ts(at(minute)), ts(firstResponse?.let(::at)), ts(resolved?.let(::at)), ts(at(minute)), id
        )
    }

    private fun sla(id: String): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM incident_sla WHERE incident_id = ?", id)
    private fun instant(v: Any?): Instant? = (v as java.sql.Timestamp?)?.toInstant()

    private fun auditActions(id: String): List<String> = jdbc.queryForList(
        "SELECT action FROM audit_events WHERE entity_type = 'INCIDENT_SLA' AND entity_id = ? ORDER BY id", String::class.java, id
    )

    private fun getJson(path: String, caller: Caller?, expect: Int): JsonNode {
        val req = get(path).apply { if (caller != null) header("Authorization", caller.bearer) }
        val response = mvc.perform(req).andReturn().response
        assertEquals(expect, response.status, "GET $path -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    // --- monitor -------------------------------------------------------------------------------------------------------

    @Test fun `the monitor starts tracking, warns and breaches, auditing each step once as SLA_MONITOR`() {
        val reporter = t.caller(Role.EMPLOYEE)
        val svc = service()
        val policyId = policy(svc, "P2", response = 10, resolution = 60, pct = 50)
        val id = incident(reporter.id, svc, "P2")

        assertFalse(monitor.runAt(at(1)).skipped)
        sla(id).let {
            assertEquals("RUNNING", it["status"]); assertEquals("SERVICE", it["policy_scope"]); assertEquals(policyId, it["policy_id"])
            assertEquals(at(10), instant(it["response_due_at"])); assertEquals(at(60), instant(it["resolution_due_at"]))
            assertEquals(base, instant(it["clock_start"]))
        }

        monitor.runAt(at(6))
        assertEquals("WARNING", sla(id)["response_status"])
        monitor.runAt(at(7)) // nothing new
        monitor.runAt(at(11))
        sla(id).let { assertEquals("BREACHED", it["status"]); assertEquals(at(10), instant(it["response_breached_at"])) }

        assertEquals(listOf("SLA_STARTED", "SLA_WARNING_RAISED", "SLA_BREACHED"), auditActions(id))
        val actors = jdbc.queryForList(
            "SELECT DISTINCT actor_type || ':' || actor_name FROM audit_events WHERE entity_type = 'INCIDENT_SLA' AND entity_id = ?", String::class.java, id
        )
        assertEquals(listOf("SYSTEM:SLA_MONITOR"), actors)
    }

    @Test fun `pauses move the resolution deadline, resolving meets it and verifying completes tracking`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        val svc = service()
        policy(svc, "P3", response = 30, resolution = 120, pct = 80)
        val id = incident(reporter.id, svc, "P3", assignee = tech.id)

        move(id, "ACKNOWLEDGED", 5, tech.id, firstResponse = 5)
        move(id, "IN_PROGRESS", 6, tech.id, firstResponse = 5)
        monitor.runAt(at(7))
        assertEquals("MET", sla(id)["response_status"])

        move(id, "PENDING_INFO", 10, tech.id, firstResponse = 5)
        monitor.runAt(at(11))
        sla(id).let { assertEquals("PAUSED", it["resolution_status"]); assertEquals(at(10), instant(it["paused_since"])) }

        move(id, "IN_PROGRESS", 40, tech.id, firstResponse = 5)
        monitor.runAt(at(41))
        sla(id).let {
            assertNull(it["paused_since"]); assertEquals(30 * 60L, it["paused_seconds"])
            assertEquals(at(150), instant(it["resolution_due_at"]))
        }

        move(id, "RESOLVED", 60, tech.id, firstResponse = 5, resolved = 60)
        monitor.runAt(at(61))
        assertEquals("MET", sla(id)["status"])

        move(id, "VERIFIED", 70, tech.id, firstResponse = 5, resolved = 60)
        monitor.runAt(at(71))
        assertEquals(true, sla(id)["complete"])
        assertTrue(reader.findTracked(10_000).none { it.incidentId == id }, "a complete SLA is no longer evaluated")

        assertEquals(listOf("SLA_STARTED", "SLA_MET", "SLA_PAUSED", "SLA_RESUMED", "SLA_MET"), auditActions(id))
    }

    @Test fun `with no applicable policy the SLA is NO_POLICY, reported once, and starts when a policy appears`() {
        val reporter = t.caller(Role.EMPLOYEE)
        val svc = service()
        jdbc.update("UPDATE sla_policies SET active = FALSE WHERE service_id IS NULL AND priority = 'P4'")
        try {
            val id = incident(reporter.id, svc, "P4")
            monitor.runAt(at(1))
            sla(id).let { assertEquals("NO_POLICY", it["status"]); assertEquals("MISSING_SLA_CONFIG", it["no_policy_reason"]); assertNull(it["policy_id"]) }
            monitor.runAt(at(2))
            assertEquals(listOf("SLA_POLICY_MISSING"), auditActions(id), "retried, not reported again")

            policy(svc, "P4", response = 60, resolution = 600, pct = 80)
            monitor.runAt(at(3))
            sla(id).let { assertEquals("RUNNING", it["status"]); assertNull(it["no_policy_reason"]); assertEquals(at(60), instant(it["response_due_at"])) }
            assertEquals(listOf("SLA_POLICY_MISSING", "SLA_STARTED"), auditActions(id))
        } finally {
            jdbc.update("UPDATE sla_policies SET active = TRUE WHERE service_id IS NULL AND priority = 'P4'")
        }
    }

    @Test fun `a run is skipped while another holds the monitor lock`() {
        val locked = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = thread {
            tx.execute {
                assertEquals(true, jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, SlaStore.MONITOR_LOCK_KEY))
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(locked.await(30, TimeUnit.SECONDS))
            assertTrue(monitor.runAt(at(1)).skipped)
        } finally {
            release.countDown(); holder.join(30_000)
        }
        assertFalse(monitor.runAt(at(1)).skipped, "the lock is released at commit")
    }

    // --- API -----------------------------------------------------------------------------------------------------------

    @Test fun `the SLA of one incident is visible to the people who may see the incident`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN)
        val stranger = t.caller(Role.EMPLOYEE); val admin = t.caller(Role.ADMIN)
        val svc = service()
        policy(svc, "P2", response = 10, resolution = 60, pct = 50)
        val id = incident(reporter.id, svc, "P2", assignee = tech.id)
        val path = "/api/incidents/$id/sla"

        assertEquals("PENDING", getJson(path, reporter, 200)["status"].asText(), "before the first evaluation")
        monitor.runAt(at(1))

        val body = getJson(path, reporter, 200)
        assertEquals("RUNNING", body["status"].asText()); assertEquals("P2", body["priority"].asText())
        assertEquals("SERVICE", body["policy"]["scope"].asText())
        assertEquals(10, body["response"]["targetMinutes"].asInt())
        assertEquals(at(10), Instant.parse(body["response"]["dueAt"].asText()))
        assertFalse(body["evaluatedAt"].isNull)

        getJson(path, tech, 200); getJson(path, admin, 200)
        assertEquals("FORBIDDEN", getJson(path, stranger, 403)["code"].asText())
        getJson(path, null, 401)
        getJson("/api/incidents/INCNOSUCH/sla", admin, 404)
    }

    @Test fun `the SLA queue shows admins everything and technicians their own incidents`() {
        val reporter = t.caller(Role.EMPLOYEE); val tech = t.caller(Role.TECHNICIAN); val otherTech = t.caller(Role.TECHNICIAN)
        val admin = t.caller(Role.ADMIN)
        val svc = service()
        policy(svc, "P2", response = 10, resolution = 60, pct = 50)
        val id = incident(reporter.id, svc, "P2", assignee = tech.id)
        monitor.runAt(at(11)) // response breached

        fun ids(caller: Caller, query: String = "") =
            getJson("/api/sla/incidents?size=100$query", caller, 200)["items"].map { it["incidentId"].asText() }

        assertTrue(id in ids(admin)); assertTrue(id in ids(admin, "&status=BREACHED")); assertTrue(id in ids(admin, "&status=breached&status=WARNING"))
        assertFalse(id in ids(admin, "&status=RUNNING"))
        assertTrue(id in ids(tech)); assertFalse(id in ids(otherTech))
        getJson("/api/sla/incidents", reporter, 403)
        getJson("/api/sla/incidents", null, 401)
        assertEquals("INVALID_INPUT", getJson("/api/sla/incidents?status=LATE", admin, 400)["code"].asText())
        getJson("/api/sla/incidents?size=0", admin, 400)
    }
}
