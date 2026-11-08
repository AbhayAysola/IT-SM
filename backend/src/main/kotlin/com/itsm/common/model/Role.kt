package com.itsm.common.model

/**
 * The three primary roles. Team leads are NOT a role: they are technicians whose
 * technician profile is flagged `isTeamLead` / has reporting relationships.
 */
enum class Role {
    EMPLOYEE,
    TECHNICIAN,
    ADMIN;

    /** Spring Security authority string, e.g. `ROLE_ADMIN` (use with `hasRole('ADMIN')`). */
    val authority: String get() = "ROLE_$name"

    companion object {
        /** The boilerplate `users.role` column defaulted to `USER`; those rows are employees. */
        private const val LEGACY_USER = "USER"

        /**
         * Maps a persisted role string to a [Role]. An unknown value means corrupted data,
         * not bad client input, so it is an [IllegalStateException] (-> 500), not a 4xx.
         */
        fun fromStored(value: String): Role {
            val normalized = value.trim().uppercase()
            if (normalized == LEGACY_USER) return EMPLOYEE
            return entries.firstOrNull { it.name == normalized }
                ?: throw IllegalStateException("Unknown stored role value")
        }
    }
}
