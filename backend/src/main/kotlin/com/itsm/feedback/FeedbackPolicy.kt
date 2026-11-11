package com.itsm.feedback

import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.Permission
import com.itsm.lifecycle.IncidentState
import java.time.Instant

/**
 * Rules for the post-resolution feedback flow:
 *
 * `RESOLVED --VERIFY--> VERIFIED --(feedback)--> ... --CLOSE--> CLOSED`
 *
 * Verifying and rating are separate acts. Verifying says "it is fixed"; the rating says how the handling went.
 * A reporter who is not satisfied with the fix does not leave feedback, they `REOPEN` at `RESOLVED`.
 * Feedback is optional: closing does not wait for it.
 */
object FeedbackPolicy {

    /** Feedback is accepted once the fix is confirmed, and stays open after closing (the reporter may reply later). */
    val ACCEPTING_STATES: Set<IncidentState> = setOf(IncidentState.VERIFIED, IncidentState.CLOSED)

    /**
     * Builds the feedback to save, or throws.
     *
     * @param reporterId who reported the incident, from P2's data
     * @param alreadyGiven the existing feedback for this incident, if any
     * @throws UnauthorizedOperation (403) when [user] is not the reporter (an admin is not either), or lacks the permission
     * @throws FeedbackNotAllowed (409) when the incident is not VERIFIED or CLOSED
     * @throws DuplicateResource (409) when feedback was already given (it is final)
     * @throws InvalidInput (400) for a rating outside 1..5 or an over-long comment
     *
     * Order: who may, then state, then duplicate, then input. Authorisation first so a stranger learns nothing
     * about the incident; the input check last so a valid request is never rejected for a mere typo before
     * the state and duplicate questions are settled.
     */
    fun submit(
        incidentId: String,
        state: IncidentState,
        reporterId: Long,
        user: CurrentUser,
        alreadyGiven: Feedback?,
        rating: Int,
        comment: String?,
        now: Instant
    ): Feedback {
        if (user.id != reporterId || Permission.INCIDENT_VERIFY_OWN !in user.permissions) {
            throw UnauthorizedOperation("Only the person who reported the incident can give feedback on it")
        }
        if (state !in ACCEPTING_STATES) throw FeedbackNotAllowed(state.name)
        if (alreadyGiven != null) throw DuplicateResource("Feedback has already been given for this incident")

        val errors = mutableListOf<FieldError>()
        if (rating !in Feedback.MIN_RATING..Feedback.MAX_RATING) {
            errors += FieldError("rating", "must be between ${Feedback.MIN_RATING} and ${Feedback.MAX_RATING}")
        }
        val text = comment?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > Feedback.MAX_COMMENT_LENGTH) {
            errors += FieldError("comment", "must be at most ${Feedback.MAX_COMMENT_LENGTH} characters")
        }
        if (errors.isNotEmpty()) throw InvalidInput("Please correct the highlighted fields", errors)

        return Feedback(incidentId, reporterId, rating, text, now)
    }
}
