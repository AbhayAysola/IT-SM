package com.itsm.lifecycle

import com.itsm.common.error.InvalidInput
import com.itsm.common.error.InvalidStateTransition
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.security.CurrentUser

/**
 * Strict, stateless validation of incident lifecycle moves. No database, no Spring: the service that
 * changes an incident calls [apply] first and publishes the audit event only if it returns.
 *
 * ```
 * NEW -> CLASSIFIED -> ASSIGNED -> ACKNOWLEDGED -> IN_PROGRESS <-> PENDING_INFO
 *                         ^                            |  ^
 *                         +-- REASSIGN (any active)    v  | REOPEN
 *                                                  RESOLVED -> VERIFIED -> CLOSED
 * ```
 */
object IncidentStateMachine {

    /** The outcome of a validated move; carries what the caller needs for the audit event. */
    data class Result(
        val transition: IncidentTransition,
        val previousState: IncidentState,
        val newState: IncidentState,
        val reason: String?
    ) {
        val auditAction: String get() = transition.auditAction
    }

    /** Transitions that could be attempted from [state], ignoring who is asking. */
    fun transitionsFrom(state: IncidentState): List<IncidentTransition> =
        IncidentTransition.entries.filter { it.allowsFrom(state) }

    /** Same, narrowed to what [user] is permitted to do. Use it to decide which buttons to show. */
    fun availableTo(state: IncidentState, user: CurrentUser): List<IncidentTransition> =
        transitionsFrom(state).filter { it.permission in user.permissions }

    fun canApply(transition: IncidentTransition, state: IncidentState): Boolean = transition.allowsFrom(state)

    /**
     * Validates [transition] on an incident currently in [state] for [user].
     *
     * @throws InvalidStateTransition (409) when the move is not legal from [state]; checked first so a
     *   caller learns the incident has moved on whatever their permissions
     * @throws UnauthorizedOperation (403) when [user] lacks the permission for it
     * @throws InvalidInput (400) when a reason is required and missing, or longer than [MAX_REASON_LENGTH]
     */
    fun apply(
        state: IncidentState,
        transition: IncidentTransition,
        user: CurrentUser,
        reason: String? = null
    ): Result {
        if (!transition.allowsFrom(state)) {
            throw InvalidStateTransition("Incident", state.name, transition.to.name)
        }
        if (transition.permission !in user.permissions) {
            throw UnauthorizedOperation("You are not allowed to ${transition.name.lowercase().replace('_', ' ')} an incident")
        }
        val cleaned = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (transition.requiresReason && cleaned == null) {
            throw InvalidInput("A reason is required to ${transition.name.lowercase().replace('_', ' ')} an incident")
        }
        if (cleaned != null && cleaned.length > MAX_REASON_LENGTH) {
            throw InvalidInput("The reason must be at most $MAX_REASON_LENGTH characters")
        }
        return Result(transition, state, transition.to, cleaned)
    }

    const val MAX_REASON_LENGTH = 1000
}
