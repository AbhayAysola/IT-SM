package com.itsm.assignment

import com.itsm.common.model.Priority
import com.itsm.common.spi.TechnicianSummary
import java.time.Instant

/** What is being assigned. Plain values, so the engine needs no incident entity (those belong to P2). */
data class AssignmentRequest(
    val categoryId: Long?,
    val priority: Priority,
    /** Technicians that must not be chosen, e.g. the current assignee when reassigning. */
    val excludeTechnicianIds: Set<Long> = emptySet()
)

/**
 * A technician plus the facts about them that are not part of the profile: the caller assembles these from
 * `UserDirectory` (profile) and workload/history data, and the engine stays pure and testable.
 *
 * @property activeCount incidents currently counting toward the technician's workload (see
 *   `IncidentState.countsTowardWorkload`)
 * @property lastAssignedAt when this technician last received an assignment; null = never. Round robin is
 *   derived from this value, so no rotation state is kept in memory.
 */
data class Candidate(
    val technician: TechnicianSummary,
    val activeCount: Int,
    val lastAssignedAt: Instant? = null
) {
    init {
        require(activeCount >= 0) { "activeCount must not be negative" }
    }

    val id: Long get() = technician.userId
}

/** A ranked suggestion with the reasons behind it, for the admin's "recommended technicians" list. */
data class Recommendation(
    val technician: TechnicianSummary,
    /** 0.0 .. 100.0, one decimal. Comparable only within one request. */
    val score: Double,
    /** Human-readable, client-safe explanations in plain language. */
    val reasons: List<String>
)

enum class AssignmentOutcome {
    /** A technician was chosen automatically. */
    ASSIGNED,
    /** The active strategy is `MANUAL`: an admin must choose; [AssignmentDecision.recommendations] help. */
    MANUAL_REQUIRED,
    /** Nobody is eligible (all unavailable, at capacity, excluded or lacking the skill). */
    NO_ELIGIBLE_TECHNICIAN
}

/**
 * The result of [AssignmentEngine.decide]. Becomes the audit metadata of `INCIDENT_ASSIGNED`
 * (`strategy`, plus `fellBackFrom` when set).
 */
data class AssignmentDecision(
    val outcome: AssignmentOutcome,
    /** The strategy that actually ran (after any fallback). */
    val strategyKey: String,
    /** Set only when [outcome] is ASSIGNED. */
    val chosen: TechnicianSummary?,
    /** Best first, at most [AssignmentEngine.RECOMMENDATION_LIMIT]; empty when nobody is eligible. */
    val recommendations: List<Recommendation>,
    /** The configured key that was not registered, when the engine fell back to the default. */
    val fellBackFrom: String? = null
)
