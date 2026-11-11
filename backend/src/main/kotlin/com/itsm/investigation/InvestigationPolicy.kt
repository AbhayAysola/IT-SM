package com.itsm.investigation

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.InvalidStateTransition
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.Permission
import com.itsm.lifecycle.IncidentState
import com.itsm.lifecycle.IncidentStateMachine
import com.itsm.lifecycle.IncidentTransition
import java.time.Instant

/** The rules for who may write and read the investigation timeline. Stateless; no storage. */
object InvestigationPolicy {

    /**
     * Builds a technician's own entry (note, finding or action taken).
     *
     * @throws InvalidInput (400) for a type that is only created by a transition, or a blank or over-long body
     * @throws InvalidStateTransition (409) when the incident is not in a state where work is recorded
     *   (before acknowledgement, or after verification: a closed incident's history is final)
     * @throws UnauthorizedOperation (403) without the permission for that type
     *
     * Order: input shape first, then state, then permission. A caller who may not write at all learns that
     * only after the request is well formed; the state check precedes permission as in the state machine.
     */
    fun newManualEntry(
        incidentId: String,
        state: IncidentState,
        author: CurrentUser,
        type: InvestigationEntryType,
        body: String,
        visibility: Visibility? = null,
        now: Instant
    ): InvestigationEntry {
        if (!type.manual) throw InvalidInput("Entries of type ${type.name} are created by changing the incident's status")
        val text = cleanBody(body)
        if (state !in type.statesAllowed) throw InvalidStateTransition("Incident", state.name, "ADD_${type.name}")
        if (type.permission !in author.permissions) throw UnauthorizedOperation("You are not allowed to add investigation notes")
        return InvestigationEntry(incidentId, author.id, type, visibility ?: type.defaultVisibility, text, state, now)
    }

    /**
     * The timeline entry a successful lifecycle move produces, or null when the move records nothing
     * (assignment, acknowledgement, start, verify, close, classify: the audit trail already holds those).
     *
     * [IncidentStateMachine.apply] has already validated the move and demanded the reason where required, so the
     * body is the reason. `entryState` is the state the incident was in when the move was made, which is what a
     * reader of the timeline expects to see beside it.
     */
    fun entryFor(incidentId: String, result: IncidentStateMachine.Result, author: CurrentUser, now: Instant): InvestigationEntry? {
        val type = when (result.transition) {
            IncidentTransition.REQUEST_INFO -> InvestigationEntryType.INFO_REQUEST
            IncidentTransition.PROVIDE_INFO -> InvestigationEntryType.INFO_RESPONSE
            IncidentTransition.RESOLVE -> InvestigationEntryType.RESOLUTION
            IncidentTransition.REOPEN -> InvestigationEntryType.REOPEN_REASON
            IncidentTransition.REASSIGN -> InvestigationEntryType.REASSIGNMENT
            else -> return null
        }
        val reason = result.reason ?: return null
        return InvestigationEntry(incidentId, author.id, type, type.defaultVisibility, reason, result.previousState, now)
    }

    /** True when [user] may read [entry]. Whether they may open the incident at all is P2's `IncidentAccessPolicy`. */
    fun canSee(user: CurrentUser, entry: InvestigationEntry): Boolean =
        entry.visibility == Visibility.REPORTER_VISIBLE || Permission.INCIDENT_WORK in user.permissions

    /** [entries] narrowed to what [user] may read, order kept. */
    fun visibleTo(user: CurrentUser, entries: List<InvestigationEntry>): List<InvestigationEntry> =
        entries.filter { canSee(user, it) }

    private fun cleanBody(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) throw InvalidInput("Write something first", listOf(FieldError("body", "must not be blank")))
        if (text.length > InvestigationEntry.MAX_BODY_LENGTH) {
            throw InvalidInput(
                "The note must be at most ${InvestigationEntry.MAX_BODY_LENGTH} characters",
                listOf(FieldError("body", "must be at most ${InvestigationEntry.MAX_BODY_LENGTH} characters"))
            )
        }
        return text
    }
}
