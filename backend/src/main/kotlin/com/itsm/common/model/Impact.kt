package com.itsm.common.model

import com.itsm.common.error.InvalidInput

/**
 * How many people an incident affects. Shared vocabulary: P2 records it at intake/classification, P4's priority module
 * reads it. Declared from the widest to the narrowest impact.
 */
enum class Impact(val label: String) {
    /** The whole organisation, or many users. */
    HIGH("Organisation-wide or many users"),
    /** A department or a team. */
    MEDIUM("A department or team"),
    /** One person. */
    LOW("A single user");

    /** One level wider, at most [HIGH]. */
    fun raised(): Impact = if (this == HIGH) HIGH else entries[ordinal - 1]

    companion object {
        fun fromCode(code: String): Impact =
            entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) }
                ?: throw InvalidInput("Unknown impact '${code.take(10)}'. Expected one of HIGH, MEDIUM, LOW.")
    }
}
