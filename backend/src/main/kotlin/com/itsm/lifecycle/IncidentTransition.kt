package com.itsm.lifecycle

import com.itsm.common.security.Permission
import com.itsm.lifecycle.IncidentState.*

/**
 * Every legal move of the incident lifecycle, as data. The state machine is this table:
 * adding or changing a rule is one line here, and the tests iterate over the whole table.
 *
 * @property from states this action may start from
 * @property to the single state it ends in
 * @property permission what the caller must hold (ownership, e.g. "the reporter" or "the assignee",
 *   is checked by the service that applies the transition, through `IncidentAccessPolicy`)
 * @property auditAction the audit `action` to publish for it (UPPER_SNAKE_CASE, see the integration guide)
 * @property requiresReason true when the caller must give a reason, so history explains why
 */
enum class IncidentTransition(
    val from: Set<IncidentState>,
    val to: IncidentState,
    val permission: Permission,
    val auditAction: String,
    val requiresReason: Boolean = false
) {
    /** NEW -> CLASSIFIED. Done by intake (P2) once category and priority are set. */
    CLASSIFY(setOf(NEW), CLASSIFIED, Permission.INCIDENT_CREATE, "INCIDENT_CLASSIFIED"),

    ASSIGN(setOf(CLASSIFIED), ASSIGNED, Permission.INCIDENT_ASSIGN, "INCIDENT_ASSIGNED"),

    /** Hand an active incident to a different technician. */
    REASSIGN(setOf(ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO), ASSIGNED,
        Permission.INCIDENT_ASSIGN, "INCIDENT_REASSIGNED", requiresReason = true),

    ACKNOWLEDGE(setOf(ASSIGNED), ACKNOWLEDGED, Permission.INCIDENT_WORK, "INCIDENT_ACKNOWLEDGED"),

    START_WORK(setOf(ACKNOWLEDGED), IN_PROGRESS, Permission.INCIDENT_WORK, "INCIDENT_STATUS_CHANGED"),

    REQUEST_INFO(setOf(IN_PROGRESS), PENDING_INFO, Permission.INCIDENT_WORK, "INCIDENT_STATUS_CHANGED",
        requiresReason = true),

    /** The reporter answered; work resumes. The answer is the reason: an empty answer would help nobody. */
    PROVIDE_INFO(setOf(PENDING_INFO), IN_PROGRESS, Permission.INCIDENT_VIEW_OWN, "INCIDENT_STATUS_CHANGED",
        requiresReason = true),

    RESOLVE(setOf(IN_PROGRESS), RESOLVED, Permission.INCIDENT_WORK, "INCIDENT_RESOLVED", requiresReason = true),

    /** The reporter confirms the fix. */
    VERIFY(setOf(RESOLVED), VERIFIED, Permission.INCIDENT_VERIFY_OWN, "INCIDENT_VERIFIED"),

    /** The reporter says it is not fixed; the incident goes back to the technician. */
    REOPEN(setOf(RESOLVED), IN_PROGRESS, Permission.INCIDENT_VERIFY_OWN, "INCIDENT_STATUS_CHANGED",
        requiresReason = true),

    CLOSE(setOf(VERIFIED), CLOSED, Permission.INCIDENT_CLOSE, "INCIDENT_CLOSED");

    fun allowsFrom(state: IncidentState): Boolean = state in from
}
