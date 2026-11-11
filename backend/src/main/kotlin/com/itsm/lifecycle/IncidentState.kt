package com.itsm.lifecycle

/**
 * Lifecycle states of an incident (P3 owns this enum).
 *
 * Other modules never see this type: states cross module boundaries as plain UPPER_SNAKE_CASE
 * strings (the [name]), as in `InvalidStateTransition` and the audit `previousState`/`newState`.
 *
 * Which moves are legal is NOT decided here; see [IncidentTransition] and [IncidentStateMachine].
 */
enum class IncidentState(
    /** True while a technician is on the hook for the incident, so it counts toward their workload. */
    val countsTowardWorkload: Boolean = false,
    /** True when no further transition is possible. */
    val terminal: Boolean = false
) {
    /** Reported, not yet categorised. */
    NEW,
    /** Category and priority known, waiting for a technician. */
    CLASSIFIED,
    /** A technician has been chosen but has not accepted the work yet. */
    ASSIGNED(countsTowardWorkload = true),
    /** The technician has accepted the incident. The response SLA is met here. */
    ACKNOWLEDGED(countsTowardWorkload = true),
    /** Investigation is under way. */
    IN_PROGRESS(countsTowardWorkload = true),
    /** Waiting for the reporter to supply more information. */
    PENDING_INFO(countsTowardWorkload = true),
    /** The technician says it is fixed; the reporter must confirm. */
    RESOLVED,
    /** The reporter confirmed the fix. */
    VERIFIED,
    /** Finished. Terminal. */
    CLOSED(terminal = true);

    companion object {
        /** Parses a stored/crossing-module state name. Unknown values are a data error, not a client error. */
        fun fromName(name: String): IncidentState =
            entries.firstOrNull { it.name == name.trim().uppercase() }
                ?: error("Unknown incident state '${name.take(30)}'")
    }
}
