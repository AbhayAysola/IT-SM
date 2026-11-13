package com.itsm.priority

import com.itsm.common.model.Impact
import com.itsm.common.model.Priority
import com.itsm.common.model.Urgency
import com.itsm.common.spi.PriorityAssessment

/** Everything the calculation needs, already looked up. Plain data, so the calculator needs no beans. */
data class PriorityInputs(
    val impact: Impact,
    val urgency: Urgency,
    val serviceName: String,
    /** 1 (low) .. 5 (mission critical). */
    val businessCriticality: Int,
    /** Active services that depend on this one, directly or transitively. */
    val dependentServiceCount: Int
) {
    init {
        require(businessCriticality in 1..5) { "businessCriticality must be 1..5, was $businessCriticality" }
        require(dependentServiceCount >= 0) { "dependentServiceCount must not be negative" }
    }
}

/**
 * Incident priority, as documented in docs/architecture/p4.md section 1:
 *
 * 1. **Effective impact**: the reporter's impact, raised one level (at most HIGH) when [WIDE_IMPACT_DEPENDENTS] or more
 *    active services depend on the incident's service.
 * 2. **Base priority** from the ITIL impact x urgency matrix ([matrix]) using the effective impact.
 * 3. **Criticality adjustment**, at most one level and never past P1 or P4: criticality [RAISE_CRITICALITY] makes it one
 *    level more urgent, [LOWER_CRITICALITY] one level less urgent.
 *
 * The thresholds are placeholders agreed in the design doc, not from the course spec; they are only here.
 */
class PriorityCalculator {

    fun calculate(inputs: PriorityInputs): PriorityAssessment {
        val reasons = mutableListOf<String>()
        val basePriority = matrix(inputs.impact, inputs.urgency)
        reasons += "${inputs.impact.name} impact and ${inputs.urgency.name} urgency give ${basePriority.name} (${basePriority.label})"

        val effectiveImpact =
            if (inputs.dependentServiceCount >= WIDE_IMPACT_DEPENDENTS && inputs.impact != Impact.HIGH) {
                inputs.impact.raised().also {
                    reasons += "Impact raised from ${inputs.impact.name} to ${it.name}: " +
                        "${inputs.dependentServiceCount} services depend on ${inputs.serviceName}"
                }
            } else {
                inputs.impact
            }

        val matrixPriority = matrix(effectiveImpact, inputs.urgency)
        val priority = when (inputs.businessCriticality) {
            RAISE_CRITICALITY -> moreUrgent(matrixPriority)
            LOWER_CRITICALITY -> lessUrgent(matrixPriority)
            else -> matrixPriority
        }
        if (priority != matrixPriority) {
            val direction = if (priority.isMoreUrgentThan(matrixPriority)) "Raised" else "Lowered"
            reasons += "$direction from ${matrixPriority.name} to ${priority.name}: " +
                "${inputs.serviceName} has business criticality ${inputs.businessCriticality}"
        }

        return PriorityAssessment(
            priority = priority,
            basePriority = basePriority,
            impact = inputs.impact,
            effectiveImpact = effectiveImpact,
            urgency = inputs.urgency,
            businessCriticality = inputs.businessCriticality,
            dependentServiceCount = inputs.dependentServiceCount,
            reasons = reasons.toList()
        )
    }

    companion object {
        /** This many active dependent services (direct or transitive) widen the impact by one level. */
        const val WIDE_IMPACT_DEPENDENTS = 3

        /** Mission-critical services: one level more urgent. */
        const val RAISE_CRITICALITY = 5

        /** Low-criticality services: one level less urgent. */
        const val LOWER_CRITICALITY = 1

        /** The ITIL impact x urgency matrix, folded onto four priorities. */
        fun matrix(impact: Impact, urgency: Urgency): Priority = when (impact) {
            Impact.HIGH -> when (urgency) { Urgency.HIGH -> Priority.P1; Urgency.MEDIUM -> Priority.P2; Urgency.LOW -> Priority.P3 }
            Impact.MEDIUM -> when (urgency) { Urgency.HIGH -> Priority.P2; Urgency.MEDIUM -> Priority.P3; Urgency.LOW -> Priority.P4 }
            Impact.LOW -> when (urgency) { Urgency.HIGH -> Priority.P3; Urgency.MEDIUM -> Priority.P4; Urgency.LOW -> Priority.P4 }
        }

        private fun moreUrgent(p: Priority): Priority = Priority.entries.firstOrNull { it.rank == p.rank - 1 } ?: p
        private fun lessUrgent(p: Priority): Priority = Priority.entries.firstOrNull { it.rank == p.rank + 1 } ?: p
    }
}
