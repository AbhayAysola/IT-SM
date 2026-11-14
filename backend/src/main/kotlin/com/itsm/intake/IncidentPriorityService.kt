package com.itsm.intake

import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.event.IncidentPriorityChanged
import com.itsm.common.model.Priority
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.AuditPublisher
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** `POST /api/incidents/{id}/priority`: the new priority and why (kept in the audit trail). */
data class PriorityChangeRequest(
    @field:NotBlank val priority: String = "",
    @field:NotBlank @field:Size(max = PriorityChangeRequest.MAX_REASON) val reason: String = ""
) {
    companion object {
        const val MAX_REASON = 500
    }
}

data class PriorityChangeDto(val incidentId: String, val priority: String, val previousPriority: String, val changedAt: Instant)

/**
 * Changes an open incident's priority after intake (docs/architecture/p4.md section 1, "Changing a priority"). P2 owns
 * `incidents.priority`, so the write is here; P4 reacts to the [IncidentPriorityChanged] event (SLA deadlines, the
 * assignee's notification). Only incidents still being worked on can change: once resolved, the priority is history.
 */
@Service
class IncidentPriorityService(
    private val jdbc: JdbcTemplate,
    private val audit: AuditPublisher,
    private val events: ApplicationEventPublisher,
    private val clock: Clock
) {

    @Transactional
    fun change(incidentId: String, request: PriorityChangeRequest, user: CurrentUser): PriorityChangeDto {
        val target = Priority.fromCode(request.priority)
        val reason = request.reason.trim()
        if (reason.isEmpty()) throw InvalidInput("Say why the priority changes")
        if (reason.length > PriorityChangeRequest.MAX_REASON) throw InvalidInput("The reason is too long (at most ${PriorityChangeRequest.MAX_REASON} characters)")

        val (state, current) = jdbc.query(
            "SELECT state, priority FROM incidents WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString("state") to rs.getString("priority") }, incidentId
        ).firstOrNull() ?: throw ResourceNotFound("Incident", incidentId)
        if (state !in CHANGEABLE_STATES || current == null) {
            throw InvalidInput("The priority can only change while the incident is being worked on (not $state)")
        }
        val from = Priority.valueOf(current)
        if (from == target) throw InvalidInput("The incident is already ${target.name}")

        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        jdbc.update("UPDATE incidents SET priority = ?, updated_at = ? WHERE id = ?", target.name, now.atOffset(ZoneOffset.UTC), incidentId)
        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(user), action = "INCIDENT_PRIORITY_CHANGED", entityType = "INCIDENT",
                entityId = incidentId, incidentId = incidentId,
                description = "Priority changed from ${from.name} to ${target.name}",
                metadata = mapOf("previousPriority" to from.name, "priority" to target.name, "reason" to reason)
            )
        )
        events.publishEvent(IncidentPriorityChanged(incidentId, from, target, user.id, now))
        return PriorityChangeDto(incidentId, target.name, from.name, now)
    }

    private companion object {
        /** Classified and not yet resolved: the incident is still waiting for or getting work. */
        val CHANGEABLE_STATES = setOf("CLASSIFIED", "ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS", "PENDING_INFO")
    }
}
