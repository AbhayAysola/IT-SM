package com.itsm.assignment

import com.itsm.common.spi.AssignmentSettingsView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Chooses a technician for an incident. Pure: it takes the settings and the candidate snapshots as
 * arguments and touches no database, so the caller (the assignment service, written once P2's incident
 * storage and P1's providers exist) decides where the data comes from and applies the result inside its own
 * `@Transactional` method, publishing `INCIDENT_ASSIGNED` with `strategy = decision.strategyKey`.
 *
 * Order of work: (1) filter by [EligibilityPolicy]; (2) pick the strategy for the category (a per-category
 * override beats the global setting); (3) rank; (4) the first of the ranking wins unless the strategy is
 * manual. Recommendations are always computed from the eligible set.
 */
@Component
class AssignmentEngine(strategies: List<AssignmentStrategy>, private val recommender: TechnicianRecommender) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byKey: Map<String, AssignmentStrategy> = run {
        val duplicates = strategies.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate assignment strategy keys: $duplicates" }
        strategies.forEach {
            require(StrategyKeys.FORMAT.matches(it.key)) { "Strategy key '${it.key}' must be UPPER_SNAKE_CASE" }
        }
        val registered = strategies.associateBy { it.key }
        // The default is seeded in the database by P1, so the application must not start without it.
        require(StrategyKeys.LOAD_BASED in registered) { "The default strategy ${StrategyKeys.LOAD_BASED} must be registered" }
        registered
    }

    fun registeredKeys(): Set<String> = byKey.keys

    /**
     * @param candidates every technician the caller considers; the engine does the filtering, so pass the
     *   technicians for the incident's category from `UserDirectory.listAvailableTechnicians` along with
     *   their workload.
     */
    fun decide(
        settings: AssignmentSettingsView,
        request: AssignmentRequest,
        candidates: List<Candidate>
    ): AssignmentDecision {
        val configured = settings.strategyFor(request.categoryId)
        val strategy = byKey[configured]
        // A stale or hand-edited key must not stop incidents being assigned: use the default and say so.
        val effective = strategy ?: byKey.getValue(StrategyKeys.LOAD_BASED)
        val fellBackFrom = configured.takeIf { strategy == null }
        if (fellBackFrom != null) {
            log.warn("Assignment strategy '{}' is not registered; falling back to {}", fellBackFrom.take(50), effective.key)
        }

        val max = settings.defaultMaxWorkload
        val eligible = EligibilityPolicy.eligible(candidates, request, max)
        val recommendations = recommender.recommend(eligible, request, max)
        val ranked = effective.rank(eligible, request, max)

        val outcome = when {
            ranked.isEmpty() -> AssignmentOutcome.NO_ELIGIBLE_TECHNICIAN
            !effective.autoAssigns -> AssignmentOutcome.MANUAL_REQUIRED
            else -> AssignmentOutcome.ASSIGNED
        }
        return AssignmentDecision(
            outcome = outcome,
            strategyKey = effective.key,
            chosen = if (outcome == AssignmentOutcome.ASSIGNED) ranked.first().technician else null,
            recommendations = recommendations,
            fellBackFrom = fellBackFrom
        )
    }

    companion object {
        const val RECOMMENDATION_LIMIT = 5
    }
}
