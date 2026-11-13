package com.itsm.priority

import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Impact
import com.itsm.common.model.Urgency
import com.itsm.common.spi.PriorityAssessment
import com.itsm.common.spi.PriorityPolicy
import com.itsm.common.spi.ServiceCatalog
import com.itsm.common.spi.ServiceView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * P4's [PriorityPolicy]: looks the service up in P1's [ServiceCatalog] and hands plain inputs to [PriorityCalculator].
 *
 * Dependents are counted **transitively through active services**, from one `listActive()` call (each active service
 * lists what it depends on, so the reverse graph is built in memory): two catalog reads per assessment whatever the size
 * of the graph. An inactive service is not running, so it neither counts nor carries impact through to what depends on
 * it. The incident's own service is never counted, and a dependency cycle cannot loop.
 *
 * Transactions. Both reads share one read-only transaction (the caller's, if any). [ServiceNotFound] is the documented
 * answer for an unknown service, so it does not mark the caller's transaction rollback-only; any other exception, above
 * all a data-access failure, keeps the default behaviour.
 */
@Component
@Transactional(readOnly = true, noRollbackFor = [ServiceNotFound::class])
class CatalogPriorityPolicy(private val services: ServiceCatalog) : PriorityPolicy {

    private val calculator = PriorityCalculator()

    override fun assess(serviceId: Long, impact: Impact, urgency: Urgency): PriorityAssessment {
        val service = services.findById(serviceId) ?: throw ServiceNotFound(serviceId)
        return calculator.calculate(
            PriorityInputs(
                impact = impact,
                urgency = urgency,
                serviceName = service.name,
                businessCriticality = service.businessCriticality,
                dependentServiceCount = transitiveActiveDependents(serviceId, services.listActive()).size
            )
        )
    }

    companion object {
        /** Ids of active services that depend on [serviceId], directly or through other active services. */
        fun transitiveActiveDependents(serviceId: Long, active: List<ServiceView>): Set<Long> {
            val dependentsOf = HashMap<Long, MutableList<Long>>()
            for (s in active) for (dependency in s.dependsOnServiceIds) dependentsOf.getOrPut(dependency) { mutableListOf() } += s.id

            val found = LinkedHashSet<Long>()
            val queue = ArrayDeque(listOf(serviceId))
            while (queue.isNotEmpty()) {
                for (dependent in dependentsOf[queue.removeFirst()].orEmpty()) {
                    if (dependent != serviceId && found.add(dependent)) queue += dependent
                }
            }
            return found
        }
    }
}
