package com.itsm.admin.itservice

import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ServiceNotFound
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
 *
 * Transactions. `requireActive` is an interface default method, but Kotlin compiles a public bridge for it into this class,
 * so the class-level `@Transactional` proxy wraps it and it joins the caller's transaction. Spring marks that transaction
 * rollback-only for any RuntimeException leaving the bean, so a caller that caught the exception would still fail at commit
 * with `UnexpectedRollbackException`. [ServiceNotFound] (unknown) and [InvalidInput] (inactive) are the documented
 * outcomes of `requireActive` (the caller is told it may catch them), thrown after the lookup returned normally, so they are
 * excluded from the rollback rule. Every other exception, above all a data-access failure, keeps the default behaviour and
 * still dooms the transaction.
 */
@Component
@Transactional(readOnly = true, noRollbackFor = [ServiceNotFound::class, InvalidInput::class])
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
