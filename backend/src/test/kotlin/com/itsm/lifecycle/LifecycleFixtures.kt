package com.itsm.lifecycle

import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.AssignmentSettingsView
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserDirectory
import com.itsm.common.spi.UserSummary
import com.itsm.feedback.Feedback
import com.itsm.feedback.FeedbackStore
import com.itsm.investigation.InvestigationEntry
import com.itsm.investigation.InvestigationLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal val NOW: Instant = Instant.parse("2026-11-11T10:00:00Z")
internal val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

internal const val REPORTER = 1L
internal const val TECH_A = 10L
internal const val TECH_B = 11L
internal const val ADMIN = 99L
internal const val CATEGORY = 7L

internal val reporter = CurrentUser(REPORTER, "rita@campus.example", Role.EMPLOYEE)
internal val techA = CurrentUser(TECH_A, "a@campus.example", Role.TECHNICIAN)
internal val techB = CurrentUser(TECH_B, "b@campus.example", Role.TECHNICIAN)
internal val admin = CurrentUser(ADMIN, "admin@campus.example", Role.ADMIN)
internal val stranger = CurrentUser(500, "eve@campus.example", Role.EMPLOYEE)

internal fun record(
    id: String = "INC1",
    state: IncidentState,
    assignee: Long? = if (state in setOf(IncidentState.NEW, IncidentState.CLASSIFIED)) null else TECH_A,
    resolvedAt: Instant? = if (state in setOf(IncidentState.RESOLVED, IncidentState.VERIFIED, IncidentState.CLOSED)) NOW.minusSeconds(60) else null
) = IncidentRecord(
    id = id, state = state, reporterId = REPORTER, assigneeId = assignee, categoryId = CATEGORY, serviceId = 3,
    priority = Priority.P3, assignedAt = assignee?.let { NOW.minusSeconds(3600) }, firstResponseAt = null,
    resolvedAt = resolvedAt, closedAt = null
)

internal class FakeIncidentStore(vararg initial: IncidentRecord) : IncidentStore {
    val rows = initial.associateBy { it.id }.toMutableMap()
    val lockedTechnicians = mutableListOf<Long>()
    var saves = 0
    /** Counts handed out before the real ones, to simulate figures that went stale between deciding and locking. */
    val staleCounts = ArrayDeque<Map<Long, Int>>()
    var similar: List<ResolvedIncident> = emptyList()

    override fun find(incidentId: String) = rows[incidentId]
    override fun lock(incidentId: String) = rows[incidentId]
    override fun save(record: IncidentRecord) { saves++; rows[record.id] = record }
    override fun activeCounts(technicianIds: Collection<Long>): Map<Long, Int> =
        staleCounts.removeFirstOrNull()?.let { stale -> technicianIds.associateWith { stale[it] ?: 0 } }
            ?: technicianIds.associateWith { id -> rows.values.count { it.assigneeId == id && it.state.countsTowardWorkload } }
    override fun lastAssignedAt(technicianIds: Collection<Long>): Map<Long, Instant> =
        rows.values.filter { it.assigneeId in technicianIds && it.assignedAt != null }
            .groupBy { it.assigneeId!! }.mapValues { (_, v) -> v.maxOf { it.assignedAt!! } }
    override fun list(query: IncidentListQuery): IncidentPage {
        val search = query.search
        val mine = rows.values.filter { query.scope.all || it.reporterId == query.scope.userId || it.assigneeId == query.scope.userId }
            .filter { query.states.isEmpty() || it.state in query.states }
            .filter { search.isNullOrBlank() || it.id.contains(search, true) || it.title.contains(search, true) }
        return IncidentPage(mine.drop(query.offset.toInt()).take(query.limit).map { IncidentListRow(it, false) }, mine.size.toLong())
    }
    override fun stateCounts(scope: IncidentScope) =
        rows.values.filter { scope.all || it.reporterId == scope.userId || it.assigneeId == scope.userId }.groupingBy { it.state }.eachCount()
    override fun lockTechnician(technicianId: Long) { lockedTechnicians += technicianId }
    override fun similarResolved(categoryId: Long, serviceId: Long?, excludeIncidentId: String, limit: Int) = similar
}

internal class FakeLog : InvestigationLog {
    val entries = mutableListOf<InvestigationEntry>()
    override fun append(entry: InvestigationEntry) { entries += entry }
    override fun list(incidentId: String) = entries.filter { it.incidentId == incidentId }
}

internal class FakeFeedback : FeedbackStore {
    val saved = mutableMapOf<String, Feedback>()
    override fun find(incidentId: String) = saved[incidentId]
    override fun save(feedback: Feedback) { saved[feedback.incidentId] = feedback }
}

internal class RecordingAudit : AuditPublisher {
    val events = mutableListOf<AuditableEvent>()
    override fun publish(event: AuditableEvent) { events += event }
}

internal fun summary(id: Long, max: Int = 5, available: Boolean = true, skills: Map<Long, Int> = mapOf(CATEGORY to 3)) =
    TechnicianSummary(id, "Tech $id", "t$id@campus.example", available, max, 3, false, null, skills)

internal class FakeDirectory(vararg technicians: TechnicianSummary) : UserDirectory {
    val all = technicians.toList()
    override fun findById(userId: Long): UserSummary? = null
    override fun findTechnician(userId: Long) = all.firstOrNull { it.userId == userId }
    override fun listAvailableTechnicians(categoryId: Long?) =
        all.filter { it.available && (categoryId == null || categoryId in it.skills) }
    override fun escalationChain(technicianId: Long) = emptyList<UserSummary>()
    override fun listAdmins() = emptyList<UserSummary>()
}

internal class FakeSettings(var view: AssignmentSettingsView = AssignmentSettingsView("LOAD_BASED", 5, emptyMap())) : AssignmentSettingsProvider {
    override fun current() = view
}
