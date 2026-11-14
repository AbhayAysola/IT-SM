package com.itsm.common.event

import com.itsm.common.model.Priority
import java.time.Instant

/*
 * Domain events about incidents (docs/architecture/p4.md section 6). Audit is a record, not a message bus: modules that
 * must REACT to an incident change (P4's SLA tracking, notifications, escalation) listen to these instead.
 *
 * Rules for publishers (P2, P3):
 *  - publish with Spring's ApplicationEventPublisher INSIDE the business transaction, right after the change is saved;
 *  - ids and codes only, never entities, text or personal data;
 *  - state names are IncidentState names as strings (that enum is P3's, so `common` cannot reference it).
 * Rules for listeners: react AFTER_COMMIT (a rolled-back change must not be acted on) in their own transaction, and
 * never throw back into the publisher: a listener's failure must not undo or fail someone else's change.
 */

/** Marker for every incident domain event. */
sealed interface IncidentEvent {
    val incidentId: String
    val occurredAt: Instant
}

/** P2: an incident was reported and classified (intake creates incidents directly in CLASSIFIED). */
data class IncidentClassified(
    override val incidentId: String,
    val reporterId: Long,
    val serviceId: Long,
    val categoryId: Long,
    val priority: Priority,
    override val occurredAt: Instant
) : IncidentEvent

/** P3: any lifecycle transition, including ASSIGN and REASSIGN. */
data class IncidentStateChanged(
    override val incidentId: String,
    val fromState: String,
    val toState: String,
    /** The IncidentTransition name, e.g. RESOLVE, REOPEN. */
    val transition: String,
    /** The user who made the move. */
    val actorId: Long,
    override val occurredAt: Instant
) : IncidentEvent

/** P3: an incident was assigned or reassigned to a technician. Published together with the matching [IncidentStateChanged]. */
data class IncidentAssigned(
    override val incidentId: String,
    val assigneeId: Long,
    val previousAssigneeId: Long?,
    val actorId: Long,
    override val occurredAt: Instant
) : IncidentEvent

/** P3: automatic assignment found nobody eligible; the incident stays as it was (CLASSIFIED for a first assignment). */
data class IncidentAssignmentFailed(
    override val incidentId: String,
    /** The AssignmentOutcome name, e.g. NO_ELIGIBLE_TECHNICIAN. */
    val reason: String,
    override val occurredAt: Instant
) : IncidentEvent
