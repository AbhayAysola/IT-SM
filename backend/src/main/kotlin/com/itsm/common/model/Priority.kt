package com.itsm.common.model

import com.itsm.common.error.InvalidInput

/**
 * Incident priority. This enum is only the shared vocabulary; HOW a priority is
 * calculated (impact x urgency x criticality ...) is owned by the priority module (P4).
 */
enum class Priority(val label: String, val rank: Int) {
    P1("Critical", 1),
    P2("High", 2),
    P3("Medium", 3),
    P4("Low", 4);

    /** Lower rank number = more urgent. */
    fun isMoreUrgentThan(other: Priority): Boolean = rank < other.rank

    companion object {
        fun fromCode(code: String): Priority =
            entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) }
                ?: throw InvalidInput("Unknown priority '${code.take(10)}'. Expected one of P1, P2, P3, P4.")
    }
}
