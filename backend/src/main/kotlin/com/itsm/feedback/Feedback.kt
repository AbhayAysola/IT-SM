package com.itsm.feedback

import com.itsm.common.error.ErrorCode
import com.itsm.common.error.ItsmException
import java.time.Instant

/**
 * The reporter's verdict on how their incident was handled. One per incident, immutable once recorded.
 *
 * The text is never copied into audit metadata or logs (it may name people); the audit event
 * `INCIDENT_FEEDBACK_RECORDED` carries only the rating.
 */
data class Feedback(
    val incidentId: String,
    /** The reporter. Feedback is the reporter's own voice: nobody else, admins included, can submit it. */
    val reporterId: Long,
    val rating: Int,
    val comment: String?,
    val submittedAt: Instant
) {
    init {
        require(Regex("^[A-Za-z0-9_-]{1,30}$").matches(incidentId)) { "invalid incident id" }
        require(reporterId > 0) { "reporterId must be positive" }
        require(rating in MIN_RATING..MAX_RATING) { "rating must be $MIN_RATING..$MAX_RATING" }
        require(comment == null || (comment.isNotBlank() && comment.length <= MAX_COMMENT_LENGTH)) { "invalid comment" }
    }

    companion object {
        const val MIN_RATING = 1
        const val MAX_RATING = 5
        const val MAX_COMMENT_LENGTH = 1000
    }
}

/** 409 `CONFLICT`: feedback is not accepted in the incident's current state. */
class FeedbackNotAllowed(state: String) :
    ItsmException(ErrorCode.CONFLICT, "Feedback can only be given once the fix has been confirmed (the incident is $state)")

/**
 * Storage contract. The implementation arrives with incident storage.
 *
 * Required: a **unique constraint on the incident id** in the database. [FeedbackPolicy.submit] rejects a second
 * submission it can see, but two submissions racing in separate transactions can both pass that check; the
 * constraint is what stops the second, and the global handler already turns it into 409 `DUPLICATE_RESOURCE`.
 * Call [save] inside the `@Transactional` method that publishes `INCIDENT_FEEDBACK_RECORDED`.
 */
interface FeedbackStore {
    fun find(incidentId: String): Feedback?
    fun save(feedback: Feedback)
}
