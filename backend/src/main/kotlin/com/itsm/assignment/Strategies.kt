package com.itsm.assignment

import org.springframework.stereotype.Component

/**
 * Compares `active / max` without floating point: a/b < c/d  <=>  a*d < c*b. Valid because every candidate
 * reaching a strategy passed [EligibilityPolicy], so each limit is positive.
 */
internal fun utilizationOrder(defaultMaxWorkload: Int): Comparator<Candidate> = Comparator { a, b ->
    val ma = EligibilityPolicy.effectiveMax(a, defaultMaxWorkload).toLong()
    val mb = EligibilityPolicy.effectiveMax(b, defaultMaxWorkload).toLong()
    (a.activeCount * mb).compareTo(b.activeCount * ma)
}

/**
 * Fewest incidents relative to capacity first, so 1 of 10 is preferred over 1 of 2.
 * Ties: fewer absolute incidents, then more experience, then lowest id.
 * This is the seeded default (`LOAD_BASED`).
 */
@Component
class LoadBasedStrategy : AssignmentStrategy {
    override val key = StrategyKeys.LOAD_BASED

    override fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int) =
        candidates.sortedWith(
            utilizationOrder(defaultMaxWorkload)
                .thenBy { it.activeCount }
                .thenByDescending { it.technician.experienceYears }
                .thenBy { it.id }
        )
}

/**
 * Best proficiency in the incident's category first. Technicians with no recorded skill in it are DROPPED
 * (not ranked last): skill-based means skilled. If nobody is skilled the engine reports
 * NO_ELIGIBLE_TECHNICIAN rather than silently handing the incident to someone unqualified.
 * With no category the skill criterion does not apply and the order is the load-based one.
 * Ties: lower utilisation, more experience, lowest id.
 */
@Component
class SkillBasedStrategy : AssignmentStrategy {
    override val key = StrategyKeys.SKILL_BASED

    override fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int): List<Candidate> {
        val category = request.categoryId
        fun proficiency(c: Candidate) = if (category == null) 0 else c.technician.skills[category] ?: 0
        val pool = if (category == null) candidates else candidates.filter { proficiency(it) >= MIN_PROFICIENCY }
        return pool.sortedWith(
            compareByDescending<Candidate> { proficiency(it) }
                .then(utilizationOrder(defaultMaxWorkload))
                .thenByDescending { it.technician.experienceYears }
                .thenBy { it.id }
        )
    }

    companion object { const val MIN_PROFICIENCY = 1 }
}

/**
 * Whoever waited longest since their last assignment goes first; never-assigned technicians go before
 * anyone who has been assigned. Ties: lowest id.
 *
 * Stateless on purpose: the rotation is derived from [Candidate.lastAssignedAt] (data in the database),
 * not from an in-memory pointer, so it survives restarts and works with several backend instances.
 * It ignores skill and load by design (those are the other strategies). Eligibility still applies, so a
 * technician who is unavailable or at capacity is skipped, not "owed" a turn.
 */
@Component
class RoundRobinStrategy : AssignmentStrategy {
    override val key = StrategyKeys.ROUND_ROBIN

    override fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int) =
        candidates.sortedWith(
            compareBy<Candidate> { it.lastAssignedAt != null } // false (never assigned) sorts first
                .thenBy { it.lastAssignedAt }
                .thenBy { it.id }
        )
}

/**
 * Never assigns on its own: an admin picks. [rank] is the load-based order, used only to present the
 * candidates sensibly. The engine adds scored recommendations.
 */
@Component
class ManualStrategy : AssignmentStrategy {
    override val key = StrategyKeys.MANUAL
    override val autoAssigns = false

    override fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int) =
        candidates.sortedWith(utilizationOrder(defaultMaxWorkload).thenBy { it.id })
}
