package com.itsm.feedback

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Row of `incident_feedback` (V31). Internal to this module: callers see [Feedback]. */
@Entity
@Immutable
@Table(name = "incident_feedback")
class FeedbackEntity(
    @Id @Column(name = "incident_id") val incidentId: String = "",
    @Column(name = "reporter_id", nullable = false) val reporterId: Long = 0,
    @Column(nullable = false) val rating: Int = 0,
    @Column val comment: String? = null,
    @Column(name = "submitted_at", nullable = false) val submittedAt: Instant = Instant.EPOCH
)

/**
 * Stores feedback. [save] must INSERT: Spring Data's `save` would `merge` an entity with an assigned id, and for an
 * immutable entity that silently ignores a second submission instead of failing. `persist` + `flush` makes the
 * primary key on `incident_id` reject it right here (a 409 `DUPLICATE_RESOURCE` through the global handler).
 */
@Repository
class JpaFeedbackStore(@PersistenceContext private val em: EntityManager) : FeedbackStore {

    @Transactional(readOnly = true)
    override fun find(incidentId: String): Feedback? = em.find(FeedbackEntity::class.java, incidentId)?.let {
        Feedback(it.incidentId, it.reporterId, it.rating, it.comment, it.submittedAt)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun save(feedback: Feedback) {
        em.persist(FeedbackEntity(feedback.incidentId, feedback.reporterId, feedback.rating, feedback.comment, feedback.submittedAt))
        em.flush()
    }
}
