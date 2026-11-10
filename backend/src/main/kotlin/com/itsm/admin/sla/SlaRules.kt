package com.itsm.admin.sla

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.model.Priority

/** Validation rules for SLA policies. They mirror the CHECK constraints of V13 and the frontend's limits. */
object SlaRules {
    const val MAX_RESOLUTION_MINUTES = 30 * 24 * 60
    const val DEFAULT_WARNING_PCT = 80
    const val MIN_WARNING_PCT = 1
    const val MAX_WARNING_PCT = 99

    /** Targets must be positive, resolution strictly longer than response, and at most 30 days. */
    fun targets(responseMinutes: Int, resolutionMinutes: Int) {
        val errors = buildList {
            if (responseMinutes < 1) add(FieldError("responseMinutes", "must be a positive number of minutes"))
            if (resolutionMinutes < 1) add(FieldError("resolutionMinutes", "must be a positive number of minutes"))
            else if (resolutionMinutes > MAX_RESOLUTION_MINUTES) {
                add(FieldError("resolutionMinutes", "must be at most $MAX_RESOLUTION_MINUTES minutes (30 days)"))
            }
            if (responseMinutes >= 1 && resolutionMinutes >= 1 && responseMinutes >= resolutionMinutes) {
                add(FieldError("resolutionMinutes", "must be longer than the response target"))
            }
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid SLA targets", errors)
    }

    fun warningPct(value: Int) {
        if (value !in MIN_WARNING_PCT..MAX_WARNING_PCT) throw InvalidInput(
            "Invalid warning threshold",
            listOf(FieldError("warningThresholdPct", "must be between $MIN_WARNING_PCT and $MAX_WARNING_PCT"))
        )
    }

    fun priority(raw: String): Priority = try {
        Priority.valueOf(raw.trim().uppercase())
    } catch (e: IllegalArgumentException) {
        throw InvalidInput("Unknown priority", listOf(FieldError("priority", "must be one of P1, P2, P3, P4")))
    }
}
