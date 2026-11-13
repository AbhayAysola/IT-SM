package com.itsm.intake

import com.itsm.common.model.Impact
import com.itsm.common.model.Priority
import com.itsm.common.model.Urgency
import com.itsm.common.spi.PriorityAssessment
import com.itsm.common.spi.PriorityPolicy
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * Fallback [PriorityPolicy] active only when P4's CatalogPriorityPolicy is not on the classpath.
 * Uses the standard ITIL impact × urgency matrix with no service-criticality adjustment.
 * Remove once P4's module is merged and their implementation is the primary bean.
 */
@Component
@ConditionalOnMissingBean(PriorityPolicy::class)
class MatrixPriorityPolicy : PriorityPolicy {

    override fun assess(serviceId: Long, impact: Impact, urgency: Urgency): PriorityAssessment {
        val priority = matrix(impact, urgency)
        return PriorityAssessment(
            priority = priority,
            basePriority = priority,
            impact = impact,
            effectiveImpact = impact,
            urgency = urgency,
            businessCriticality = 3,
            dependentServiceCount = 0,
            reasons = listOf("${impact.name} impact and ${urgency.name} urgency → ${priority.name}")
        )
    }

    companion object {
        fun matrix(impact: Impact, urgency: Urgency): Priority = when (impact) {
            Impact.HIGH   -> when (urgency) { Urgency.HIGH -> Priority.P1; Urgency.MEDIUM -> Priority.P2; Urgency.LOW -> Priority.P3 }
            Impact.MEDIUM -> when (urgency) { Urgency.HIGH -> Priority.P2; Urgency.MEDIUM -> Priority.P3; Urgency.LOW -> Priority.P4 }
            Impact.LOW    -> when (urgency) { Urgency.HIGH -> Priority.P3; Urgency.MEDIUM -> Priority.P4; Urgency.LOW -> Priority.P4 }
        }
    }
}
