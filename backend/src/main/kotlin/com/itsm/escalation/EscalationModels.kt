package com.itsm.escalation

import java.time.Instant

/** How far up an escalation goes (docs/architecture/p4.md section 3). */
enum class EscalationTier {
    /** The first manager in the assignee's reporting line (P1's `escalationChain`), or the admins if there is none. */
    TEAM_LEAD,
    /** The second manager in the reporting line, or the admins if the line is shorter. */
    NEXT_LEVEL,
    /** Every active administrator (system admin and IT manager are both ADMIN). */
    ADMINS
}

/** Why an incident escalates. Each fires at most once per incident (a UNIQUE constraint backs this). */
enum class EscalationTrigger(val tier: EscalationTier, val description: String) {
    RESPONSE_BREACHED(EscalationTier.TEAM_LEAD, "Response SLA breached"),
    RESOLUTION_BREACHED(EscalationTier.TEAM_LEAD, "Resolution SLA breached"),
    /** Still open at `secondLevelPct` of the resolution target. */
    RESOLUTION_OVERDUE(EscalationTier.NEXT_LEVEL, "Resolution far past its target"),
    /** Still open at `adminLevelPct` of the resolution target. */
    RESOLUTION_CRITICAL(EscalationTier.ADMINS, "Resolution critically past its target"),
    /** Nobody assigned `unassignedMinutes` after it was reported (also covers P3's NO_ELIGIBLE_TECHNICIAN outcome). */
    UNASSIGNED(EscalationTier.ADMINS, "Not assigned in time")
}

/** When escalation fires. One stored row; administrators edit it. */
data class EscalationSettings(
    val unassignedMinutes: Int,
    val secondLevelPct: Int,
    val adminLevelPct: Int,
    val version: Long = 0,
    val updatedAt: Instant? = null,
    val updatedByUserId: Long? = null
) {
    companion object {
        val UNASSIGNED_RANGE = 1..10_080          // one minute to one week
        val PCT_RANGE = 101..1000

        /** Field errors for proposed values, keyed by API field name; empty when valid. Mirrors V41's CHECKs. */
        fun validate(unassignedMinutes: Int, secondLevelPct: Int, adminLevelPct: Int): Map<String, String> = buildMap {
            if (unassignedMinutes !in UNASSIGNED_RANGE) put("unassignedMinutes", "must be between 1 and 10080 minutes (one week)")
            if (secondLevelPct !in PCT_RANGE) put("secondLevelPct", "must be between 101 and 1000")
            if (adminLevelPct !in PCT_RANGE) put("adminLevelPct", "must be between 101 and 1000")
            else if (secondLevelPct in PCT_RANGE && adminLevelPct <= secondLevelPct) put("adminLevelPct", "must be greater than secondLevelPct")
        }
    }
}

/**
 * What the policy needs about one open incident: its state and assignee, and the SLA facts the monitor recorded. The SLA
 * fields are null until the SLA monitor has evaluated the incident (or when no SLA policy applies).
 */
data class EscalationCandidate(
    val incidentId: String,
    val state: String,
    val assigneeId: Long?,
    val createdAt: Instant,
    val responseBreachedAt: Instant? = null,
    val resolutionBreachedAt: Instant? = null,
    val clockStart: Instant? = null,
    val resolutionMinutes: Int? = null,
    val pausedSeconds: Long = 0,
    val pausedSince: Instant? = null,
    val resolutionMetAt: Instant? = null
) {
    val open: Boolean get() = state in OPEN_STATES

    companion object {
        /** Someone still has to act. NEW cannot occur (intake creates CLASSIFIED); RESOLVED waits on the reporter. */
        val OPEN_STATES = setOf("CLASSIFIED", "ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS", "PENDING_INFO")
    }
}

/** One recorded escalation. */
data class Escalation(
    val id: Long,
    val incidentId: String,
    val trigger: EscalationTrigger,
    val tier: EscalationTier,
    val targetUserIds: List<Long>,
    val assigneeId: Long?,
    val createdAt: Instant
)
