package com.itsm.escalation

import java.time.Duration
import java.time.Instant

/**
 * Which escalations are due for one incident (docs/architecture/p4.md section 3). Pure: no clock, database or beans.
 *
 * Only open incidents escalate (an incident waiting on the reporter to confirm a fix, or finished, does not). For an open
 * incident:
 * * **UNASSIGNED**: still `CLASSIFIED` `unassignedMinutes` after it was reported;
 * * **RESPONSE_BREACHED** / **RESOLUTION_BREACHED**: the SLA monitor recorded that clock's breach;
 * * **RESOLUTION_OVERDUE** / **RESOLUTION_CRITICAL**: the resolution clock has used `secondLevelPct` / `adminLevelPct` of
 *   its target. Paused time does not count, exactly as for the SLA deadline, and a paused clock is judged at the start
 *   of its pause.
 *
 * Triggers that already fired are never returned again, so a reassignment or a restart cannot repeat an escalation.
 * The result is ordered from the lowest tier up.
 */
object EscalationPolicy {

    fun due(c: EscalationCandidate, settings: EscalationSettings, alreadyFired: Set<EscalationTrigger>, now: Instant): List<EscalationTrigger> {
        if (!c.open) return emptyList()
        val due = buildList {
            if (c.responseBreachedAt != null) add(EscalationTrigger.RESPONSE_BREACHED)
            if (c.resolutionBreachedAt != null && c.resolutionMetAt == null) add(EscalationTrigger.RESOLUTION_BREACHED)
            if (reached(c, settings.secondLevelPct, now)) add(EscalationTrigger.RESOLUTION_OVERDUE)
            if (reached(c, settings.adminLevelPct, now)) add(EscalationTrigger.RESOLUTION_CRITICAL)
            if (c.state == "CLASSIFIED" && !c.createdAt.plus(Duration.ofMinutes(settings.unassignedMinutes.toLong())).isAfter(now)) {
                add(EscalationTrigger.UNASSIGNED)
            }
        }
        return due.filter { it !in alreadyFired }.sortedBy { it.tier.ordinal }
    }

    /** True when the open resolution clock has used [pct] % of its target, not counting pauses. */
    private fun reached(c: EscalationCandidate, pct: Int, now: Instant): Boolean {
        val start = c.clockStart ?: return false
        val minutes = c.resolutionMinutes ?: return false
        if (c.resolutionMetAt != null) return false
        val point = start.plusSeconds(minutes * 60L * pct / 100).plusSeconds(c.pausedSeconds)
        return !point.isAfter(c.pausedSince ?: now)
    }
}
