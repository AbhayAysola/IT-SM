package com.itsm.assignment

import com.itsm.common.model.Priority
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

/**
 * Scores eligible technicians for the "recommended technicians" list shown to an admin and returned with
 * every [AssignmentDecision]. It is advice, independent of the configured strategy: the strategy decides
 * who is auto-assigned, this explains who looks best and why.
 *
 * Score (0..100) = 100 x ( wSkill x skill + wHeadroom x headroom + wExperience x experience ), where
 *  * skill      = proficiency / 5 in the incident's category (0 if unrecorded; 0.5, neutral, with no category)
 *  * headroom   = 1 - activeCount / effective max (free capacity)
 *  * experience = min(years, 10) / 10
 *
 * Weights depend on urgency, because a critical incident should go to someone who has done this before even
 * if they are busier, while a routine one should go where there is room:
 *
 * | Priority | skill | headroom | experience |
 * |---|---|---|---|
 * | P1, P2 | 0.45 | 0.20 | 0.35 |
 * | P3, P4 | 0.40 | 0.45 | 0.15 |
 *
 * Ties: higher skill, then lowest id. The numbers are a starting point, not a specification; they live in
 * one place ([weightsFor]) so they can be tuned and are covered by tests.
 */
@Component
class TechnicianRecommender {

    data class Weights(val skill: Double, val headroom: Double, val experience: Double) {
        init { require(kotlin.math.abs(skill + headroom + experience - 1.0) < 1e-9) { "weights must sum to 1" } }
    }

    fun weightsFor(priority: Priority): Weights = when (priority) {
        Priority.P1, Priority.P2 -> Weights(skill = 0.45, headroom = 0.20, experience = 0.35)
        Priority.P3, Priority.P4 -> Weights(skill = 0.40, headroom = 0.45, experience = 0.15)
    }

    /** [candidates] must already be eligible. Returns at most [limit], best first. */
    fun recommend(
        candidates: List<Candidate>,
        request: AssignmentRequest,
        defaultMaxWorkload: Int,
        limit: Int = AssignmentEngine.RECOMMENDATION_LIMIT
    ): List<Recommendation> {
        val w = weightsFor(request.priority)
        return candidates.map { c ->
            val max = EligibilityPolicy.effectiveMax(c, defaultMaxWorkload)
            val proficiency = request.categoryId?.let { c.technician.skills[it] ?: 0 }
            val skill = proficiency?.let { it / MAX_PROFICIENCY } ?: NEUTRAL_SKILL
            val headroom = (1.0 - c.activeCount.toDouble() / max).coerceIn(0.0, 1.0)
            val years = c.technician.experienceYears.coerceAtLeast(0)
            val experience = minOf(years, EXPERIENCE_CAP_YEARS) / EXPERIENCE_CAP_YEARS.toDouble()
            val score = 100.0 * (w.skill * skill + w.headroom * headroom + w.experience * experience)

            val reasons = buildList {
                when {
                    proficiency == null -> {}
                    proficiency > 0 -> add("Skill $proficiency of 5 in this category")
                    else -> add("No recorded skill in this category")
                }
                add("${c.activeCount} of $max active incidents")
                add(if (years == 1) "1 year of experience" else "$years years of experience")
            }
            Scored(Recommendation(c.technician, (score * 10).roundToInt() / 10.0, reasons), proficiency ?: 0)
        }
            .sortedWith(
                compareByDescending<Scored> { it.recommendation.score }
                    .thenByDescending { it.proficiency }
                    .thenBy { it.recommendation.technician.userId }
            )
            .take(limit)
            .map { it.recommendation }
    }

    private class Scored(val recommendation: Recommendation, val proficiency: Int)

    private companion object {
        const val MAX_PROFICIENCY = 5.0
        const val NEUTRAL_SKILL = 0.5
        const val EXPERIENCE_CAP_YEARS = 10
    }
}
