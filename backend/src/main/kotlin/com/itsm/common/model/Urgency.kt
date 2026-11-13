package com.itsm.common.model

import com.itsm.common.error.InvalidInput

/**
 * How soon an incident hurts the business. Shared vocabulary: P2 records it at intake/classification, P4's priority module
 * reads it. Declared from the most to the least urgent.
 */
enum class Urgency(val label: String) {
    /** Work has stopped and there is no workaround. */
    HIGH("Work stopped, no workaround"),
    /** Work is degraded but a workaround exists. */
    MEDIUM("Degraded, a workaround exists"),
    /** An inconvenience. */
    LOW("An inconvenience");

    companion object {
        fun fromCode(code: String): Urgency =
            entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) }
                ?: throw InvalidInput("Unknown urgency '${code.take(10)}'. Expected one of HIGH, MEDIUM, LOW.")
    }
}
