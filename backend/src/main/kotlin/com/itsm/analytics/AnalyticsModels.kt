package com.itsm.analytics

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Met and breached counts of one group of SLAs, for both clocks. */
data class SlaCounts(
    val tracked: Long,
    val responseMet: Long,
    val responseBreached: Long,
    val resolutionMet: Long,
    val resolutionBreached: Long
) {
    /** Percentage of decided response clocks that were met (one decimal), null when none is decided yet. */
    val responseCompliancePct: Double? get() = pct(responseMet, responseBreached)
    val resolutionCompliancePct: Double? get() = pct(resolutionMet, resolutionBreached)

    companion object {
        val ZERO = SlaCounts(0, 0, 0, 0, 0)

        fun pct(met: Long, breached: Long): Double? {
            val decided = met + breached
            if (decided == 0L) return null
            return BigDecimal(met * 100).divide(BigDecimal(decided), 1, RoundingMode.HALF_UP).toDouble()
        }
    }
}

data class SlaGroup(val id: String?, val name: String, val counts: SlaCounts)

/** Mean and 90th percentile of a duration, in minutes, over [count] incidents; null values when there are none. */
data class DurationStats(val count: Long, val meanMinutes: Double?, val p90Minutes: Double?)

data class DayPoint(val date: LocalDate, val reported: Long, val resolved: Long, val breaches: Long, val escalations: Long)

data class TechnicianRow(
    val userId: Long,
    val name: String,
    val maxWorkload: Int,
    val activeIncidents: Int,
    val resolved: Long,
    val ratings: Long,
    val averageRating: Double?
)
