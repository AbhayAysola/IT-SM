package com.itsm.lifecycle

import com.itsm.assignment.AssignmentDecision
import com.itsm.assignment.AssignmentEngine
import com.itsm.assignment.AssignmentOutcome
import com.itsm.assignment.AssignmentRequest
import com.itsm.assignment.Candidate
import com.itsm.assignment.EligibilityPolicy
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Priority
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.Permission
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.AssignmentSettingsView
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserDirectory
import com.itsm.investigation.InvestigationLog
import com.itsm.investigation.InvestigationPolicy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** What an automatic assignment did. [incident] is unchanged unless the decision's outcome is `ASSIGNED`. */
data class AutoAssignment(val incident: IncidentRecord, val decision: AssignmentDecision)

/**
 * Assigns and reassigns. Candidates and settings come from P1's `UserDirectory` and `AssignmentSettingsProvider`;
 * until P1's branch merges no bean implements them, so they are looked up lazily and a call answers 503 rather than
 * the application failing to start.
 *
 * Same transaction rules as [IncidentLifecycleService]. Capacity is checked under a per-technician advisory lock, so
 * two assignments cannot both take a technician's last free slot; the loser gets 409 `WORKLOAD_EXCEEDED` and retries.
 * Lock order is always incident row first, then technician, so assignments cannot deadlock each other.
 */
@Service
class IncidentAssignmentService(
    private val incidents: IncidentStore,
    private val timeline: InvestigationLog,
    private val audit: AuditPublisher,
    private val engine: AssignmentEngine,
    private val directory: ObjectProvider<UserDirectory>,
    private val settingsProvider: ObjectProvider<AssignmentSettingsProvider>,
    private val clock: Clock
) {
    /**
     * Lets the configured strategy choose. From `CLASSIFIED` it assigns; from an active state it reassigns (a [reason]
     * is then required, and the current assignee is excluded). A `MANUAL` strategy or nobody eligible leaves the
     * incident as it is and says so in the decision, with recommendations for the admin.
     */
    @Transactional
    fun assignAutomatically(incidentId: String, reason: String?, user: CurrentUser): AutoAssignment {
        val record = IncidentAccess.requireVisible(incidents.lock(incidentId), incidentId, user)
        val transition = transitionFor(record)
        val result = IncidentStateMachine.apply(record.state, transition, user, reason)
        val decision = decide(record)
        if (decision.outcome != AssignmentOutcome.ASSIGNED) return AutoAssignment(record, decision)

        val chosen = decision.chosen!!
        requireCapacity(chosen)
        val metadata = mutableMapOf<String, Any>("strategy" to decision.strategyKey)
        decision.fellBackFrom?.let { metadata["fellBackFrom"] = it }
        return AutoAssignment(commit(record, result, chosen.userId, user, metadata), decision)
    }

    /** A person picks the technician. Validated, not filtered: unavailable or full is 409, skill is not required. */
    @Transactional
    fun assignTo(incidentId: String, technicianId: Long, reason: String?, user: CurrentUser): IncidentRecord {
        val record = IncidentAccess.requireVisible(incidents.lock(incidentId), incidentId, user)
        val transition = transitionFor(record)
        val result = IncidentStateMachine.apply(record.state, transition, user, reason)
        if (transition == IncidentTransition.REASSIGN && record.assigneeId == technicianId) {
            throw InvalidInput("The incident is already assigned to that technician")
        }
        val technician = directoryOrUnavailable().findTechnician(technicianId) ?: throw ResourceNotFound("Technician", technicianId)
        requireCapacity(technician)
        return commit(record, result, technicianId, user, mapOf("strategy" to MANUAL))
    }

    /** The ranked suggestions for an admin choosing by hand. Read-only; the current assignee is left out. */
    @Transactional(readOnly = true)
    fun recommendations(incidentId: String, user: CurrentUser): AssignmentDecision {
        val record = IncidentAccess.requireVisible(incidents.find(incidentId), incidentId, user)
        // The same gate as assigning, without needing the incident to be in a state where it is legal right now.
        user.requirePermission(Permission.INCIDENT_ASSIGN)
        return decide(record)
    }

    private fun transitionFor(record: IncidentRecord) =
        if (record.state == IncidentState.CLASSIFIED) IncidentTransition.ASSIGN else IncidentTransition.REASSIGN

    private fun decide(record: IncidentRecord): AssignmentDecision {
        val settings = settings()
        // Every available technician, not only the skilled ones: skill is a ranking factor, and SKILL_BASED drops the unskilled itself.
        val technicians = directoryOrUnavailable().listAvailableTechnicians(null)
        val ids = technicians.map { it.userId }
        val counts = incidents.activeCounts(ids)
        val last = incidents.lastAssignedAt(ids)
        val candidates = technicians.map { Candidate(it, counts[it.userId] ?: 0, last[it.userId]) }
        val exclude = setOfNotNull(record.assigneeId)
        return engine.decide(settings, AssignmentRequest(record.categoryId, record.priority ?: Priority.P3, exclude), candidates)
    }

    /** Re-reads the technician's load under the lock: the figures used to decide may be stale by now. */
    private fun requireCapacity(technician: TechnicianSummary) {
        incidents.lockTechnician(technician.userId)
        val active = incidents.activeCounts(listOf(technician.userId)).getValue(technician.userId)
        val candidate = Candidate(technician, active)
        EligibilityPolicy.requireCanTake(candidate, settings().defaultMaxWorkload)
    }

    private fun commit(
        record: IncidentRecord, result: IncidentStateMachine.Result, technicianId: Long, user: CurrentUser, metadata: Map<String, Any>
    ): IncidentRecord {
        val now = IncidentAccess.now(clock)
        val updated = record.copy(state = result.newState, assigneeId = technicianId, assignedAt = now, updatedAt = now)
        incidents.save(updated)
        InvestigationPolicy.entryFor(record.id, result, user, now)?.let(timeline::append)
        val audited = metadata + mapOf("technicianId" to technicianId) +
            (record.assigneeId?.let { mapOf("previousTechnicianId" to it) } ?: emptyMap())
        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(user), action = result.auditAction, entityType = "INCIDENT", entityId = record.id,
                incidentId = record.id, previousState = result.previousState.name, newState = result.newState.name,
                description = "Incident assigned to technician $technicianId", metadata = audited
            )
        )
        return updated
    }

    private fun settings(): AssignmentSettingsView = settingsProvider.ifAvailable?.current() ?: throw unavailable()
    private fun directoryOrUnavailable(): UserDirectory = directory.ifAvailable ?: throw unavailable()
    private fun unavailable() = ItsmException(ErrorCode.SERVICE_UNAVAILABLE, "Assignment is not available yet")

    private companion object {
        const val MANUAL = "MANUAL"
    }
}
