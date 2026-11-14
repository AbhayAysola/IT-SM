package com.itsm.lifecycle

import com.itsm.common.error.InvalidInput
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.Permission
import com.itsm.common.spi.AuditPublisher
import com.itsm.feedback.Feedback
import com.itsm.feedback.FeedbackPolicy
import com.itsm.feedback.FeedbackStore
import com.itsm.investigation.InvestigationEntry
import com.itsm.investigation.InvestigationEntryType
import com.itsm.investigation.InvestigationLog
import com.itsm.investigation.InvestigationPolicy
import com.itsm.investigation.Visibility
import com.itsm.common.event.IncidentStateChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class IncidentListResult(val rows: List<IncidentListRow>, val total: Long, val stateCounts: Map<IncidentState, Int>)

/**
 * Applies the lifecycle after the technician starts work: acknowledge, investigate, ask, resolve, verify, reopen,
 * close, plus notes and feedback. Assigning is [IncidentAssignmentService].
 *
 * Every write method is one transaction that changes the incident, appends the timeline entry and publishes the
 * audit event together. Order of checks: the incident exists and the caller may see it (404), then the rules in
 * [IncidentStateMachine] (409, 403, 400), then ownership (403: only the assignee works, only the reporter answers).
 */
@Service
class IncidentLifecycleService(
    private val incidents: IncidentStore,
    private val timeline: InvestigationLog,
    private val feedbacks: FeedbackStore,
    private val audit: AuditPublisher,
    private val clock: Clock,
    /** Domain events for modules that react to changes (P4); a no-op where none is wanted (unit tests). */
    private val events: ApplicationEventPublisher = ApplicationEventPublisher {}
) {
    /** Moves that start from a person's action here. Classify is intake's (P2); assign and reassign are the assignment service's. */
    private val handled = setOf(
        IncidentTransition.ACKNOWLEDGE, IncidentTransition.START_WORK, IncidentTransition.REQUEST_INFO,
        IncidentTransition.RESOLVE, IncidentTransition.PROVIDE_INFO, IncidentTransition.VERIFY,
        IncidentTransition.REOPEN, IncidentTransition.CLOSE
    )
    private val assigneeActs = setOf(
        IncidentTransition.ACKNOWLEDGE, IncidentTransition.START_WORK, IncidentTransition.REQUEST_INFO, IncidentTransition.RESOLVE
    )
    private val reporterActs = setOf(IncidentTransition.PROVIDE_INFO, IncidentTransition.VERIFY, IncidentTransition.REOPEN)

    @Transactional(readOnly = true)
    fun get(incidentId: String, user: CurrentUser): IncidentRecord =
        IncidentAccess.requireVisible(incidents.find(incidentId), incidentId, user)

    /**
     * One page of the caller's incidents (an admin's: all), filtered by [states] and [search], most urgent first, with
     * the count per state for the whole scope. Nothing here reveals an incident the caller could not open.
     */
    @Transactional(readOnly = true)
    fun list(user: CurrentUser, states: Set<IncidentState>, search: String?, page: Int, size: Int): IncidentListResult {
        val scope = IncidentScope(user.id, user.isAdmin)
        val result = incidents.list(IncidentListQuery(scope, states, search?.take(MAX_SEARCH), page.toLong() * size, size))
        return IncidentListResult(result.rows, result.total, incidents.stateCounts(scope))
    }

    /** What [user] can do right now: the state machine's moves narrowed by ownership. Drives which buttons a UI shows. */
    @Transactional(readOnly = true)
    fun availableTransitions(incidentId: String, user: CurrentUser): List<IncidentTransition> {
        return availableTransitions(get(incidentId, user), user)
    }

    /** Same, for a record the caller already holds (e.g. the one a move just returned). */
    fun availableTransitions(record: IncidentRecord, user: CurrentUser): List<IncidentTransition> =
        IncidentStateMachine.availableTo(record.state, user).filter { it in handled && ownedBy(it, record, user) }

    @Transactional
    fun transition(incidentId: String, transition: IncidentTransition, reason: String?, user: CurrentUser): IncidentRecord {
        if (transition !in handled) throw InvalidInput("${transition.name} is not done through this operation")
        val record = IncidentAccess.requireVisible(incidents.lock(incidentId), incidentId, user)
        val result = IncidentStateMachine.apply(record.state, transition, user, reason)
        when (transition) {
            in assigneeActs -> IncidentAccess.requireAssignee(record, user)
            in reporterActs -> IncidentAccess.requireReporter(record, user)
            else -> Unit
        }
        val now = IncidentAccess.now(clock)
        val updated = record.copy(state = result.newState, updatedAt = now).withTimestamps(transition, now)
        incidents.save(updated)
        InvestigationPolicy.entryFor(incidentId, result, user, now)?.let(timeline::append)
        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(user), action = result.auditAction, entityType = ENTITY_TYPE, entityId = incidentId,
                incidentId = incidentId, previousState = result.previousState.name, newState = result.newState.name,
                description = "Incident moved from ${result.previousState.name} to ${result.newState.name}",
                // Never the reason text: it can name people. Whether one was given is enough for the trail.
                metadata = mapOf("transition" to transition.name, "reasonGiven" to (result.reason != null))
            )
        )
        events.publishEvent(IncidentStateChanged(incidentId, result.previousState.name, result.newState.name, transition.name, user.id, now))
        return updated
    }

    @Transactional
    fun addNote(incidentId: String, type: InvestigationEntryType, body: String, visibility: Visibility?, user: CurrentUser): InvestigationEntry {
        val record = IncidentAccess.requireVisible(incidents.lock(incidentId), incidentId, user)
        val entry = InvestigationPolicy.newManualEntry(incidentId, record.state, user, type, body, visibility, IncidentAccess.now(clock))
        IncidentAccess.requireAssignee(record, user)
        timeline.append(entry)
        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(user), action = type.auditAction, entityType = ENTITY_TYPE, entityId = incidentId,
                incidentId = incidentId, description = "An investigation entry was added",
                metadata = mapOf("entryType" to type.name, "visibility" to entry.visibility.name) // never the text
            )
        )
        return entry
    }

    /** The timeline as [user] may read it: the reporter never receives internal entries. */
    @Transactional(readOnly = true)
    fun timeline(incidentId: String, user: CurrentUser): List<InvestigationEntry> {
        IncidentAccess.requireVisible(incidents.find(incidentId), incidentId, user)
        return InvestigationPolicy.visibleTo(user, timeline.list(incidentId))
    }

    @Transactional
    fun submitFeedback(incidentId: String, rating: Int, comment: String?, user: CurrentUser): Feedback {
        val record = IncidentAccess.requireVisible(incidents.lock(incidentId), incidentId, user)
        val feedback = FeedbackPolicy.submit(
            incidentId, record.state, record.reporterId, user, feedbacks.find(incidentId), rating, comment, IncidentAccess.now(clock)
        )
        feedbacks.save(feedback)
        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(user), action = "INCIDENT_FEEDBACK_RECORDED", entityType = ENTITY_TYPE, entityId = incidentId,
                incidentId = incidentId, description = "Feedback was recorded", metadata = mapOf("rating" to feedback.rating)
            )
        )
        return feedback
    }

    @Transactional(readOnly = true)
    fun feedback(incidentId: String, user: CurrentUser): Feedback? {
        IncidentAccess.requireVisible(incidents.find(incidentId), incidentId, user)
        return feedbacks.find(incidentId)
    }

    /** Past fixes in the same category, for a technician deciding how to start. Needs the work permission. */
    @Transactional(readOnly = true)
    fun similarResolved(incidentId: String, user: CurrentUser, limit: Int = 5): List<ResolvedIncident> {
        user.requirePermission(Permission.INCIDENT_WORK)
        val record = IncidentAccess.requireVisible(incidents.find(incidentId), incidentId, user)
        val category = record.categoryId ?: return emptyList()
        return incidents.similarResolved(category, record.serviceId, incidentId, limit.coerceIn(1, 20))
    }

    private fun ownedBy(transition: IncidentTransition, record: IncidentRecord, user: CurrentUser) = when (transition) {
        in assigneeActs -> user.isAdmin || record.assigneeId == user.id
        in reporterActs -> user.isAdmin || record.reporterId == user.id
        else -> true
    }

    /** The timestamps the V20 CHECKs tie to the state: `resolved_at` while the fix stands, `closed_at` once closed. */
    private fun IncidentRecord.withTimestamps(transition: IncidentTransition, now: Instant): IncidentRecord = when (transition) {
        IncidentTransition.ACKNOWLEDGE -> copy(firstResponseAt = firstResponseAt ?: now)
        IncidentTransition.RESOLVE -> copy(resolvedAt = now)
        IncidentTransition.REOPEN -> copy(resolvedAt = null)
        IncidentTransition.CLOSE -> copy(closedAt = now)
        else -> this
    }

    private companion object {
        const val ENTITY_TYPE = "INCIDENT"
        const val MAX_SEARCH = 100
    }
}
