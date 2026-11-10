package com.itsm.admin.itservice

import com.itsm.common.spi.ServiceCatalog
import com.itsm.common.spi.ServiceView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.Collections

/**
 * P1's implementation of the shared [ServiceCatalog] SPI. Returns immutable [ServiceView]s, never entities.
 *
 * Semantics (the interface leaves these open; documented in docs/architecture/integration-guide.md):
 *  - [findById] returns active AND inactive services (historical incidents stay displayable); validate a choice
 *    for new work with `requireActive`.
 *  - `dependsOnServiceIds` and [dependentsOf] are DIRECT edges only. Walk them for transitive impact.
 *  - [dependentsOf] returns every direct dependent, active or inactive (check `findById(id)?.active` if needed),
 *    and an empty set for an unknown id.
 */
@Component
@Transactional(readOnly = true)
class JpaServiceCatalog(private val services: ItServiceRepository) : ServiceCatalog {

    override fun findById(serviceId: Long): ServiceView? = services.findById(serviceId).orElse(null)?.toView()

    override fun listActive(): List<ServiceView> = services.findAllByActiveTrueOrderByNameAsc().map { it.toView() }

    override fun dependentsOf(serviceId: Long): Set<Long> =
        Collections.unmodifiableSet(services.dependentIdsOf(serviceId).toCollection(LinkedHashSet()))

    private fun ItServiceEntity.toView() = ServiceView(
        id = requireId(),
        name = name,
        description = description,
        businessCriticality = businessCriticality,
        ownerTeam = ownerTeam,
        active = active,
        dependsOnServiceIds = Collections.unmodifiableSet(dependsOn.toCollection(LinkedHashSet()))
    )
}
