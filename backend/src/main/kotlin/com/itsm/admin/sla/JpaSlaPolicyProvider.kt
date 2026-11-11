package com.itsm.admin.sla

import com.itsm.admin.itservice.ItServiceRepository
import com.itsm.common.error.MissingSlaConfiguration
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Priority
import com.itsm.common.spi.SlaPolicyProvider
import com.itsm.common.spi.SlaPolicyView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * P1's implementation of the shared [SlaPolicyProvider] SPI, called by P4 during incident processing.
 *
 * Resolution, in order:
 *  1. the ACTIVE policy specific to (serviceId, priority);
 *  2. otherwise the ACTIVE global policy for the priority (an inactive service-specific policy therefore never
 *     hides an active global one, and an inactive policy is never returned);
 *  3. otherwise [MissingSlaConfiguration] (HTTP 422). No duration is ever invented: not every priority is
 *     guaranteed a global policy, because an administrator may deactivate one.
 * An unknown [serviceId] throws [ServiceNotFound]. An INACTIVE service is still "known": historical incidents on a
 * retired service keep resolving against its active policies.
 *
 * Cost: two small queries (service existence, then at most two candidate rows). Read-only; it joins the caller's
 * transaction when there is one.
 *
 * Transactions. Spring marks the transaction rollback-only for any RuntimeException that leaves a `@Transactional` bean,
 * and when this bean has joined the caller's transaction that mark is on the CALLER's transaction: the caller may catch
 * the exception and carry on, yet its own commit then fails with `UnexpectedRollbackException`. [MissingSlaConfiguration]
 * and [ServiceNotFound] are answers, not failures (the contract tells P4 it may catch them and degrade), and by the time
 * they are thrown every query has already returned normally, so nothing is wrong with the transaction. They are therefore
 * excluded from the rollback rule below. Every other exception, above all a data-access failure from the queries, keeps
 * the default behaviour: it propagates unchanged and still dooms the transaction, so a swallowed database error can never
 * commit as success.
 */
@Component
@Transactional(readOnly = true, noRollbackFor = [MissingSlaConfiguration::class, ServiceNotFound::class])
class JpaSlaPolicyProvider(
    private val policies: SlaPolicyRepository,
    private val services: ItServiceRepository
) : SlaPolicyProvider {

    override fun resolve(serviceId: Long, priority: Priority): SlaPolicyView {
        if (!services.existsById(serviceId)) throw ServiceNotFound(serviceId)
        val candidates = policies.findEffectiveCandidates(serviceId, priority)
        val chosen = candidates.firstOrNull { it.serviceId == serviceId }
            ?: candidates.firstOrNull { it.serviceId == null }
            ?: throw MissingSlaConfiguration(serviceId, priority)
        return SlaPolicyView(
            id = chosen.requireId(), serviceId = chosen.serviceId, priority = chosen.priority,
            responseMinutes = chosen.responseMinutes, resolutionMinutes = chosen.resolutionMinutes,
            warningThresholdPct = chosen.warningThresholdPct
        )
    }
}
