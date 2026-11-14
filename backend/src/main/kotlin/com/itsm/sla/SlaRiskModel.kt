package com.itsm.sla

import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** Which past incidents a forecast was based on. Stored, so the values are a contract with V43's CHECK. */
enum class RiskBasis {
    /** Same priority and category. */
    CATEGORY,
    /** Same priority only (too few with the same category). */
    PRIORITY
}

/** What the forecast knows about one open incident whose resolution clock is running. */
data class RiskSubject(
    /** Resolution-clock minutes already used: time since the clock started, minus pauses. */
    val elapsedMinutes: Long,
    val dueAt: Instant,
    /** Resolution-clock minutes past incidents of the same priority and category needed. */
    val sameCategory: List<Long>,
    /** The same, for every past incident of this priority. */
    val samePriority: List<Long>,
    /** The assignee's active incidents divided by their configured ceiling; null when unassigned or unknown. */
    val load: Double?
)

/** A forecast: when the incident will probably be resolved, and why. */
data class RiskForecast(
    val predictedFinishAt: Instant,
    /** The typical remaining minutes before the load adjustment. */
    val typicalRemainingMinutes: Long,
    val basis: RiskBasis,
    val sampleSize: Int,
    /** The load factor applied (1.0 = none). */
    val loadFactor: Double
) {
    fun lateFor(dueAt: Instant) = predictedFinishAt.isAfter(dueAt)
}

/**
 * Predictive SLA risk (docs/architecture/p4.md section 2): forecasts when an open incident will be resolved, from how long
 * similar incidents took, so P4 can warn **before** the fixed warning threshold when the deadline is unlikely to be met.
 * Pure: no clock, database or beans.
 *
 * 1. **Similar incidents:** resolved incidents with the same priority and category, or, with fewer than [MIN_SAMPLES] of
 *    those, the same priority. Fewer than [MIN_SAMPLES] either way: no forecast (P4 does not guess).
 * 2. **Remaining time:** among the similar incidents that took **longer** than this one has already run, the median of
 *    what they still needed after this point. An incident that has run longer than all but [MIN_LONGER] of them is
 *    unusual; it is assumed to need a typical incident's whole time again (the median of all of them).
 * 3. **Assignee load:** an assignee above their configured ceiling works through their queue more slowly, so the remaining
 *    time is multiplied by their load (active / ceiling), between 1 and [MAX_LOAD_FACTOR]. Under the ceiling, or nobody
 *    assigned: no adjustment.
 *
 * Paused time is excluded on both sides (history and this incident), and the forecast assumes no further pause.
 */
object SlaRiskModel {
    const val MIN_SAMPLES = 5
    const val MIN_LONGER = 3
    const val MAX_LOAD_FACTOR = 3.0

    fun forecast(s: RiskSubject, now: Instant): RiskForecast? {
        val (history, basis) = when {
            s.sameCategory.size >= MIN_SAMPLES -> s.sameCategory to RiskBasis.CATEGORY
            s.samePriority.size >= MIN_SAMPLES -> s.samePriority to RiskBasis.PRIORITY
            else -> return null
        }
        val longer = history.filter { it > s.elapsedMinutes }
        val typicalRemaining = if (longer.size >= MIN_LONGER) median(longer.map { it - s.elapsedMinutes }) else median(history)
        val factor = s.load?.let { min(MAX_LOAD_FACTOR, max(1.0, it)) } ?: 1.0
        val remaining = Math.round(typicalRemaining * factor)
        return RiskForecast(now.plusSeconds(remaining * 60), typicalRemaining, basis, history.size, factor)
    }

    /** The middle value; for an even count the lower of the two middle values, so it is always a real past duration. */
    fun median(values: List<Long>): Long {
        require(values.isNotEmpty()) { "median of nothing" }
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
