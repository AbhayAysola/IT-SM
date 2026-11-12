package com.itsm.lifecycle

import com.itsm.assignment.AssignmentOutcome
import com.itsm.common.error.ItsmException
import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.TechnicianWorkloadProvider
import com.itsm.common.spi.UserDirectory
import com.itsm.common.spi.UserSummary
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.investigation.InvestigationEntryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.itsm.lifecycle.IncidentState as S
import com.itsm.lifecycle.IncidentTransition as T

/**
 * V20 and V32 on a real PostgreSQL 16, with the real stores and services. P1's directory and settings are stubbed
 * (their branch is not on main); everything else is production code. Tables are append-only or shared, so each test
 * builds its own users and incidents. Skipped (not failed) when Docker is unavailable.
 */
@SpringBootTest
@Import(IncidentFlowIntegrationTest.P1Stubs::class)
@Testcontainers(disabledWithoutDocker = true)
class IncidentFlowIntegrationTest {

    class MutableDirectory : UserDirectory {
        @Volatile var technicians: List<TechnicianSummary> = emptyList()
        override fun findById(userId: Long): UserSummary? = null
        override fun findTechnician(userId: Long) = technicians.firstOrNull { it.userId == userId }
        override fun listAvailableTechnicians(categoryId: Long?) = technicians.filter { it.available }
        override fun escalationChain(technicianId: Long) = emptyList<UserSummary>()
        override fun listAdmins() = emptyList<UserSummary>()
    }

    class AuditCollector {
        val events = CopyOnWriteArrayList<AuditableEvent>()
        @EventListener fun on(event: AuditableEvent) { events += event }
    }

    @TestConfiguration
    class P1Stubs {
        // @Primary: once P1's real UserDirectory and AssignmentSettingsProvider are on the classpath these stubs must still win.
        @Bean @Primary fun directory() = MutableDirectory()
        @Bean @Primary fun settings(): AssignmentSettingsProvider = FakeSettings()
        @Bean fun auditCollector() = AuditCollector()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var store: JpaIncidentStore
    @Autowired lateinit var lifecycle: IncidentLifecycleService
    @Autowired lateinit var assignment: IncidentAssignmentService
    @Autowired lateinit var workload: TechnicianWorkloadProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var directory: MutableDirectory
    @Autowired lateinit var audit: AuditCollector

    private fun user(role: Role): CurrentUser {
        val email = "p3-${UUID.randomUUID()}@campus.example"
        val id = jdbc.queryForObject("INSERT INTO users (email, password, name, role) VALUES (?, 'x', 'Tester', ?) RETURNING id", Long::class.java, email, role.name)!!
        return CurrentUser(id, email, role)
    }

    private fun incident(
        reporter: Long, state: S = S.CLASSIFIED, assignee: Long? = null, category: Long? = 7, service: Long? = null, priority: String? = "P3",
        assignedAt: Instant? = assignee?.let { Instant.now() }, resolvedAt: Instant? = null
    ): String {
        val id = "INC" + UUID.randomUUID().toString().take(8)
        // V21 added FK from incidents.service_id → it_services; non-NEW states require a real service row.
        val svcId = service ?: if (state != S.NEW) {
            jdbc.queryForObject("INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, "svc-$id")!!
        } else null
        jdbc.update(
            "INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, category_id, service_id, priority, assigned_at, resolved_at) " +
                "VALUES (?, 'Printer is down', 'Nothing prints', ?, ?, ?, ?, ?, ?, ?, ?)",
            id, state.name, reporter, assignee, category, svcId, priority,
            assignedAt?.let(java.sql.Timestamp::from), resolvedAt?.let(java.sql.Timestamp::from)
        )
        return id
    }

    private fun <T> inTx(block: () -> T): T = tx.execute { block() }!!
    private fun summary(id: Long, max: Int = 5) = TechnicianSummary(id, "Tech $id", "t$id@campus.example", true, max, 3, false, null, mapOf(7L to 3))

    // ---- schema
    @Test fun `the lifecycle's promises are enforced by the table itself`() {
        val r = user(Role.EMPLOYEE); val t = user(Role.TECHNICIAN)
        assertThrows<DataIntegrityViolationException>("assigned states need an assignee") { incident(r.id, S.ASSIGNED, assignee = null) }
        assertThrows<DataIntegrityViolationException>("an unassigned incident has no assignee") { incident(r.id, S.CLASSIFIED, assignee = t.id) }
        assertThrows<DataIntegrityViolationException>("classified needs a category") { incident(r.id, category = null) }
        assertThrows<DataIntegrityViolationException>("classified needs a priority") { incident(r.id, priority = null) }
        assertThrows<DataIntegrityViolationException>("bad priority") { incident(r.id, priority = "P9") }
        assertThrows<DataIntegrityViolationException>("resolved states need resolved_at") { incident(r.id, S.RESOLVED, assignee = t.id) }
        assertThrows<DataIntegrityViolationException>("a working incident has no resolved_at") { incident(r.id, S.IN_PROGRESS, assignee = t.id, resolvedAt = Instant.now()) }
        assertThrows<DataIntegrityViolationException>("closed needs closed_at") { incident(r.id, S.CLOSED, assignee = t.id, resolvedAt = Instant.now()) }
        assertThrows<DataIntegrityViolationException>("unknown reporter") { incident(Long.MAX_VALUE) }
        incident(r.id, S.NEW, category = null, service = null, priority = null) // a new incident needs none of them
    }

    @Test fun `ids are generated from the sequence and a new incident starts NEW`() {
        val r = user(Role.EMPLOYEE)
        val id = jdbc.queryForObject("INSERT INTO incidents (title, description, reporter_id) VALUES ('t', 'd', ?) RETURNING id", String::class.java, r.id)!!
        assertTrue(Regex("^INC\\d{4,}$").matches(id), id)
        assertEquals("NEW", jdbc.queryForObject("SELECT state FROM incidents WHERE id = ?", String::class.java, id))
    }

    // ---- store
    @Test fun `lock and save round-trip, and both demand a transaction`() {
        val r = user(Role.EMPLOYEE); val t = user(Role.TECHNICIAN); val id = incident(r.id)
        assertThrows<IllegalTransactionStateException> { store.lock(id) }
        val now = Instant.now()
        inTx {
            val locked = store.lock(id)!!
            store.save(locked.copy(state = S.ASSIGNED, assigneeId = t.id, assignedAt = now))
        }
        val read = store.find(id)!!
        assertEquals(S.ASSIGNED, read.state); assertEquals(t.id, read.assigneeId)
        assertEquals(now.toEpochMilli(), read.assignedAt!!.toEpochMilli())
        assertEquals(r.id, read.reporterId)
        assertNull(store.find("NOSUCH"))
    }

    @Test fun `a save that breaks a table rule fails inside the transaction`() {
        val r = user(Role.EMPLOYEE); val id = incident(r.id)
        assertThrows<DataIntegrityViolationException> {
            inTx { store.save(store.lock(id)!!.copy(state = S.ASSIGNED)) } // no assignee
        }
        assertEquals(S.CLASSIFIED, store.find(id)!!.state)
    }

    @Test fun `active counts use only the states that weigh on a technician, with zero for the idle`() {
        val r = user(Role.EMPLOYEE); val busy = user(Role.TECHNICIAN); val idle = user(Role.TECHNICIAN)
        for (s in listOf(S.ASSIGNED, S.ACKNOWLEDGED, S.IN_PROGRESS, S.PENDING_INFO)) incident(r.id, s, assignee = busy.id)
        incident(r.id, S.RESOLVED, assignee = busy.id, resolvedAt = Instant.now())
        assertEquals(mapOf(busy.id to 4, idle.id to 0), store.activeCounts(listOf(busy.id, idle.id)))
        assertEquals(4, workload.activeIncidentCount(busy.id)); assertEquals(0, workload.activeIncidentCount(idle.id))
        assertEquals(emptyMap<Long, Int>(), workload.activeIncidentCounts(emptyList()))
    }

    @Test fun `the last assignment time is the latest one per technician`() {
        val r = user(Role.EMPLOYEE); val a = user(Role.TECHNICIAN); val never = user(Role.TECHNICIAN)
        val older = Instant.parse("2026-11-01T09:00:00Z"); val newer = Instant.parse("2026-11-05T09:00:00Z")
        incident(r.id, S.ASSIGNED, assignee = a.id, assignedAt = older); incident(r.id, S.ASSIGNED, assignee = a.id, assignedAt = newer)
        val result = store.lastAssignedAt(listOf(a.id, never.id))
        assertEquals(mapOf(a.id to newer), result)
    }

    @Test fun `similar fixes need a recorded resolution, prefer the same service, then the newest`() {
        val r = user(Role.EMPLOYEE); val t = user(Role.TECHNICIAN)
        // V21 added FKs on category_id and service_id — seed real rows instead of using out-of-range IDs.
        // code must match ^[A-Z][A-Z0-9_]*$ (chk_category_code); name must be unique case-insensitively.
        val uid = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val category = jdbc.queryForObject("INSERT INTO incident_categories (code, name) VALUES (?, ?) RETURNING id", Long::class.java, "TEST_$uid", "Test $uid")!!
        val svcSame  = jdbc.queryForObject("INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, "svc-same-$uid")!!
        val svcOther = jdbc.queryForObject("INSERT INTO it_services (name, business_criticality) VALUES (?, 3) RETURNING id", Long::class.java, "svc-other-$uid")!!
        fun resolved(service: Long, at: Instant, fix: String?): String {
            val id = incident(r.id, S.RESOLVED, assignee = t.id, category = category, service = service, resolvedAt = at)
            if (fix != null) jdbc.update(
                "INSERT INTO investigation_entries (incident_id, author_id, entry_type, visibility, body, incident_state) VALUES (?, ?, 'RESOLUTION', 'REPORTER_VISIBLE', ?, 'IN_PROGRESS')", id, t.id, fix
            )
            return id
        }
        val oldSame = resolved(svcSame, Instant.parse("2026-10-01T00:00:00Z"), "restarted the spooler")
        val newOther = resolved(svcOther, Instant.parse("2026-11-01T00:00:00Z"), "replaced the cable")
        resolved(svcSame, Instant.parse("2026-11-02T00:00:00Z"), null) // no recorded fix: not useful as precedent
        val current = incident(r.id, S.IN_PROGRESS, assignee = t.id, category = category, service = svcSame)
        val found = store.similarResolved(category, svcSame, current, 5)
        assertEquals(listOf(oldSame, newOther), found.map { it.incidentId })
        assertEquals(listOf(true, false), found.map { it.sameService })
        assertEquals("restarted the spooler", found[0].resolution)
        assertEquals(1, store.similarResolved(category, svcSame, current, 1).size)
        assertTrue(store.similarResolved(category + 1, null, current, 5).isEmpty())
    }

    @Test fun `the list is scoped, filtered, searched, paged and ordered by urgency against the real table`() {
        val r = user(Role.EMPLOYEE); val other = user(Role.EMPLOYEE); val t = user(Role.TECHNICIAN)
        fun titled(reporter: Long, title: String, priority: String, state: S = S.CLASSIFIED, assignee: Long? = null): String {
            val id = incident(reporter, state, assignee = assignee, priority = priority)
            jdbc.update("UPDATE incidents SET title = ? WHERE id = ?", title, id); return id
        }
        val low = titled(r.id, "Printer 100% broken", "P4"); val high = titled(r.id, "VPN down", "P1")
        val assigned = titled(other.id, "Monitor flickers", "P2", S.ASSIGNED, t.id); titled(other.id, "Not mine", "P3")
        val scope = IncidentScope(r.id, false)
        fun ids(q: IncidentListQuery) = store.list(q).rows.map { it.record.id }

        assertEquals(listOf(high, low), ids(IncidentListQuery(scope, emptySet(), null, 0, 20)), "mine only, most urgent first")
        assertEquals(listOf(low), ids(IncidentListQuery(scope, emptySet(), "100%", 0, 20)), "a % in the search is literal")
        assertEquals(emptyList<String>(), ids(IncidentListQuery(scope, emptySet(), "%", 0, 20).copy(search = "zzz")))
        assertEquals(listOf(high), ids(IncidentListQuery(scope, emptySet(), "vpn", 0, 20)), "case-insensitive")
        assertEquals(listOf(low), ids(IncidentListQuery(scope, emptySet(), null, 1, 1)), "second page")
        assertEquals(2L, store.list(IncidentListQuery(scope, emptySet(), null, 0, 1)).total)
        assertEquals(listOf(assigned), ids(IncidentListQuery(IncidentScope(t.id, false), emptySet(), null, 0, 20)), "an assignee sees theirs")
        assertEquals(mapOf(S.CLASSIFIED to 2), store.stateCounts(scope))
        assertEquals(listOf(assigned), ids(IncidentListQuery(scope.copy(all = true), setOf(S.ASSIGNED), "flicker", 0, 20)).filter { it == assigned })
        // a search containing SQL is just text
        assertEquals(emptyList<String>(), ids(IncidentListQuery(scope, emptySet(), "'; DROP TABLE incidents; --", 0, 20)))
    }

    @Test fun `the list says whether feedback was given`() {
        val r = user(Role.EMPLOYEE); val t = user(Role.TECHNICIAN)
        val rated = incident(r.id, S.VERIFIED, assignee = t.id, resolvedAt = Instant.now()); val unrated = incident(r.id, S.VERIFIED, assignee = t.id, resolvedAt = Instant.now())
        jdbc.update("INSERT INTO incident_feedback (incident_id, reporter_id, rating) VALUES (?, ?, 4)", rated, r.id)
        val rows = store.list(IncidentListQuery(IncidentScope(r.id, false), emptySet(), null, 0, 20)).rows.associate { it.record.id to it.hasFeedback }
        assertEquals(mapOf(rated to true, unrated to false), rows)
    }

    // ---- the whole flow, through the real services
    @Test fun `an incident goes from classified to closed with a consistent row, timeline, workload and audit trail`() {
        val reporter = user(Role.EMPLOYEE); val tech = user(Role.TECHNICIAN); val admin = user(Role.ADMIN)
        directory.technicians = listOf(summary(tech.id))
        val id = incident(reporter.id)
        val before = audit.events.size

        val auto = assignment.assignAutomatically(id, null, admin)
        assertEquals(AssignmentOutcome.ASSIGNED, auto.decision.outcome); assertEquals(tech.id, auto.incident.assigneeId)
        assertEquals(1, workload.activeIncidentCount(tech.id))

        lifecycle.transition(id, T.ACKNOWLEDGE, null, tech)
        lifecycle.transition(id, T.START_WORK, null, tech)
        lifecycle.transition(id, T.REQUEST_INFO, "Which floor is the printer on?", tech)
        lifecycle.transition(id, T.PROVIDE_INFO, "Second floor", reporter)
        lifecycle.addNote(id, InvestigationEntryType.NOTE, "toner low, spooler wedged", null, tech)
        lifecycle.transition(id, T.RESOLVE, "Restarted the spooler and replaced the toner", tech)
        assertEquals(0, workload.activeIncidentCount(tech.id), "a resolved incident no longer weighs on the technician")
        lifecycle.transition(id, T.VERIFY, null, reporter)
        lifecycle.submitFeedback(id, 5, "Fast", reporter)
        val closed = lifecycle.transition(id, T.CLOSE, null, admin)

        assertEquals(S.CLOSED, closed.state)
        val row = store.find(id)!!
        assertEquals(closed, row)
        assertTrue(row.firstResponseAt != null && row.resolvedAt != null && row.closedAt != null)
        assertEquals(5, lifecycle.feedback(id, reporter)!!.rating)

        // the reporter sees the conversation and the fix, never the technician's working note
        assertEquals(
            listOf(InvestigationEntryType.INFO_REQUEST, InvestigationEntryType.INFO_RESPONSE, InvestigationEntryType.RESOLUTION),
            lifecycle.timeline(id, reporter).map { it.type }
        )
        assertEquals(4, lifecycle.timeline(id, tech).size)

        assertEquals(
            listOf(
                "INCIDENT_ASSIGNED", "INCIDENT_ACKNOWLEDGED", "INCIDENT_STATUS_CHANGED", "INCIDENT_STATUS_CHANGED", "INCIDENT_STATUS_CHANGED",
                "INCIDENT_INVESTIGATION_ENTRY_ADDED", "INCIDENT_RESOLVED", "INCIDENT_VERIFIED", "INCIDENT_FEEDBACK_RECORDED", "INCIDENT_CLOSED"
            ),
            audit.events.drop(before).filter { it.incidentId == id }.map { it.action }
        )
        // closed incidents are finished: nothing further is legal
        assertThrows<ItsmException> { lifecycle.transition(id, T.CLOSE, null, admin) }
    }

    @Test fun `the state change and its timeline entry commit or roll back together`() {
        val reporter = user(Role.EMPLOYEE); val tech = user(Role.TECHNICIAN)
        val id = incident(reporter.id, S.IN_PROGRESS, assignee = tech.id)
        // the services join the caller's transaction, so a failure later in that transaction undoes both writes
        assertThrows<IllegalStateException> {
            inTx { lifecycle.transition(id, T.RESOLVE, "Restarted the service", tech); error("boom") }
        }
        val row = store.find(id)!!
        assertEquals(S.IN_PROGRESS, row.state); assertNull(row.resolvedAt)
        assertTrue(lifecycle.timeline(id, tech).isEmpty())
    }

    @Test fun `concurrent assignments cannot overfill a technician`() {
        val reporter = user(Role.EMPLOYEE); val tech = user(Role.TECHNICIAN); val admin = user(Role.ADMIN)
        directory.technicians = listOf(summary(tech.id, max = 1))
        val ids = (1..6).map { incident(reporter.id) }
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = ids.map { id -> pool.submit<Result<AssignmentOutcome>> { runCatching { assignment.assignAutomatically(id, null, admin).decision.outcome } } }
                .map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.getOrNull() == AssignmentOutcome.ASSIGNED }, "results: $results")
            assertTrue(results.all { it.isSuccess || it.exceptionOrNull() is ItsmException }, "only business conflicts are expected: $results")
        } finally { pool.shutdownNow() }
        assertEquals(1, workload.activeIncidentCount(tech.id))
        assertEquals(1, ids.count { store.find(it)!!.state == S.ASSIGNED })
    }
}
