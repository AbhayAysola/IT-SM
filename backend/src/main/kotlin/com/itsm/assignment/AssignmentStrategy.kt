package com.itsm.assignment

/**
 * One way of choosing a technician (Strategy pattern). Implement it as a Spring `@Component` and it is
 * picked up by [AssignmentEngine] and listed by `RegisteredAssignmentStrategyCatalog`, so an admin can
 * select it: a new strategy is a new class and no other change.
 *
 * Contract for [rank]:
 *  * it receives only candidates that already passed [EligibilityPolicy]; it must not re-filter on
 *    availability or capacity;
 *  * it may drop candidates it considers unsuitable (e.g. no skill) but must never add any;
 *  * it must be deterministic: equal inputs give the same order, so end every comparator with the technician id;
 *  * it must be stateless and thread-safe.
 */
interface AssignmentStrategy {
    /** UPPER_SNAKE_CASE; this is what the admin stores in the assignment settings. */
    val key: String

    /** False for strategies that only recommend and leave the choice to a person. */
    val autoAssigns: Boolean get() = true

    /**
     * Best first. [defaultMaxWorkload] is the system-wide limit used for technicians who have none of their
     * own (see [EligibilityPolicy.effectiveMax]); only strategies that compare load need it.
     */
    fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int): List<Candidate>
}

/** Keys of the built-in strategies. Admin settings reference these strings. */
object StrategyKeys {
    /** Seeded as the default by P1's migration `V15`, so it must always stay registered. */
    const val LOAD_BASED = "LOAD_BASED"
    const val SKILL_BASED = "SKILL_BASED"
    const val ROUND_ROBIN = "ROUND_ROBIN"
    const val MANUAL = "MANUAL"

    val FORMAT = Regex("^[A-Z][A-Z0-9_]*$")
}
