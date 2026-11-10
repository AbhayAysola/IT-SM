package com.itsm.admin.sla

import com.itsm.admin.itservice.ItServiceRepository
import com.itsm.admin.itservice.ServiceRef
import com.itsm.admin.support.CatalogText
import com.itsm.admin.support.SortParams
import com.itsm.admin.support.catalogConflict
import com.itsm.admin.support.staleVersion
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Priority
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.ServiceCatalog
import com.itsm.common.spi.SlaPolicyProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * SLA policy administration (configuration only; P4 owns timers, breach detection and escalation).
 * Every successful mutation publishes one audit event in its transaction; rejected requests and no-ops publish none.
 */
@Service
class SlaPolicyAdminService(
    private val policies: SlaPolicyRepository,
    private val services: ItServiceRepository,
    private val serviceCatalog: ServiceCatalog,
    private val provider: SlaPolicyProvider,
    private val audit: AuditPublisher,
    private val currentUser: CurrentUserProvider
) {

    @Transactional(readOnly = true)
    fun list(
        query: PageQuery, search: String?, active: Boolean?, priority: String?, serviceId: Long?, global: Boolean?,
        sort: String?, dir: String?
    ): PageResponse<SlaPolicyResponse> {
        val pageable = query.toPageable(SortParams.of(sort, dir, SORTS, default = "priority"))
        val page = policies.search(
            active, priority?.takeIf { it.isNotBlank() }?.let(SlaRules::priority), serviceId, global,
            CatalogText.containsPattern(search), pageable
        )
        return PageResponse(toResponses(page.content), page.number, page.size, page.totalElements, page.totalPages)
    }

    @Transactional(readOnly = true)
    fun get(id: Long): SlaPolicyResponse = toResponses(listOf(load(id))).single()

    /** Preview of exactly what [SlaPolicyProvider] returns to P4. Throws what the provider throws (404 / 422). */
    @Transactional(readOnly = true)
    fun effective(serviceId: Long, priority: String): EffectiveSlaResponse {
        val p = SlaRules.priority(priority)
        val view = provider.resolve(serviceId, p)
        return EffectiveSlaResponse(
            serviceId = serviceId, priority = p.name, scope = if (view.serviceId == null) "GLOBAL" else "SERVICE",
            policyId = view.id, responseMinutes = view.responseMinutes, resolutionMinutes = view.resolutionMinutes,
            warningThresholdPct = view.warningThresholdPct
        )
    }

    @Transactional
    fun create(request: CreateSlaPolicyRequest): SlaPolicyResponse {
        val priority = SlaRules.priority(request.priority!!)
        val response = request.responseMinutes!!
        val resolution = request.resolutionMinutes!!
        val warning = request.warningThresholdPct ?: SlaRules.DEFAULT_WARNING_PCT
        SlaRules.targets(response, resolution)
        SlaRules.warningPct(warning)

        // A new policy is active, so the service (when given) must exist and be active: 404 / 400 from the catalog.
        request.serviceId?.let { serviceCatalog.requireActive(it) }
        requireScopeFree(request.serviceId, priority, excludeId = -1)

        val saved = policies.saveAndFlush(SlaPolicyEntity(request.serviceId, priority, response, resolution, warning, true))
        publish(
            saved, "SLA_POLICY_CREATED", "Created ${priority.name} SLA policy for ${scopeLabel(saved)}", newState = STATE_ACTIVE,
            metadata = buildMap {
                put("priority", priority.name)
                saved.serviceId?.let { put("serviceId", it) }
                put("global", saved.serviceId == null)
                put("responseMinutes", response)
                put("resolutionMinutes", resolution)
                put("warningThresholdPct", warning)
            }
        )
        return get(saved.requireId())
    }

    @Transactional
    fun update(id: Long, request: UpdateSlaPolicyRequest): SlaPolicyResponse {
        val policy = load(id)
        if (request.version != policy.version) throw staleVersion("SLA policy")

        val response = request.responseMinutes!!
        val resolution = request.resolutionMinutes!!
        val warning = request.warningThresholdPct ?: policy.warningThresholdPct
        SlaRules.targets(response, resolution)
        SlaRules.warningPct(warning)

        val changed = buildList {
            if (response != policy.responseMinutes) add("responseMinutes")
            if (resolution != policy.resolutionMinutes) add("resolutionMinutes")
            if (warning != policy.warningThresholdPct) add("warningThresholdPct")
        }
        if (changed.isEmpty()) return get(id)

        val before = Triple(policy.responseMinutes, policy.resolutionMinutes, policy.warningThresholdPct)
        policy.responseMinutes = response
        policy.resolutionMinutes = resolution
        policy.warningThresholdPct = warning
        val saved = policies.saveAndFlush(policy)

        publish(
            saved, "SLA_POLICY_UPDATED", "Updated ${saved.priority.name} SLA policy for ${scopeLabel(saved)}",
            metadata = buildMap {
                put("changedFields", changed)
                put("priority", saved.priority.name)
                saved.serviceId?.let { put("serviceId", it) }
                if ("responseMinutes" in changed) { put("responseMinutesBefore", before.first); put("responseMinutesAfter", response) }
                if ("resolutionMinutes" in changed) { put("resolutionMinutesBefore", before.second); put("resolutionMinutesAfter", resolution) }
                if ("warningThresholdPct" in changed) { put("warningThresholdPctBefore", before.third); put("warningThresholdPctAfter", warning) }
            }
        )
        return get(id)
    }

    @Transactional
    fun activate(id: Long): SlaPolicyResponse {
        val policy = load(id)
        if (policy.active) return get(id)
        requireScopeFree(policy.serviceId, policy.priority, excludeId = id)

        policy.active = true
        val saved = policies.saveAndFlush(policy)
        publish(saved, "SLA_POLICY_ACTIVATED", "Activated ${saved.priority.name} SLA policy for ${scopeLabel(saved)}",
            previousState = STATE_INACTIVE, newState = STATE_ACTIVE, metadata = scopeMetadata(saved))
        return get(id)
    }

    /** Soft delete: the row stays for history and simply stops being an effective policy. */
    @Transactional
    fun deactivate(id: Long): SlaPolicyResponse {
        val policy = load(id)
        if (!policy.active) return get(id)

        policy.active = false
        val saved = policies.saveAndFlush(policy)
        publish(saved, "SLA_POLICY_DEACTIVATED", "Deactivated ${saved.priority.name} SLA policy for ${scopeLabel(saved)}",
            previousState = STATE_ACTIVE, newState = STATE_INACTIVE, metadata = scopeMetadata(saved))
        return get(id)
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun load(id: Long): SlaPolicyEntity = policies.findById(id).orElseThrow { ResourceNotFound("SLA policy", id) }

    /** Friendly pre-check; the partial unique indexes of V13 are the authoritative guard (also under races). */
    private fun requireScopeFree(serviceId: Long?, priority: Priority, excludeId: Long) {
        if (policies.findActiveInScope(serviceId, priority, excludeId).isNotEmpty()) {
            val scope = if (serviceId == null) "the global default" else "this service"
            throw DuplicateResource("An active ${priority.name} SLA policy already exists for $scope. Edit or deactivate it first.")
        }
    }

    private fun scopeLabel(policy: SlaPolicyEntity): String {
        val id = policy.serviceId ?: return "the global default"
        val name = services.summariesOf(listOf(id)).firstOrNull()?.get(1) as String?
        return name?.let { "'$it'" } ?: "service $id"
    }

    private fun scopeMetadata(policy: SlaPolicyEntity): Map<String, Any> = buildMap {
        put("priority", policy.priority.name)
        policy.serviceId?.let { put("serviceId", it) }
        put("global", policy.serviceId == null)
    }

    /** Maps a page with one extra query for every service name involved. */
    private fun toResponses(entities: List<SlaPolicyEntity>): List<SlaPolicyResponse> {
        if (entities.isEmpty()) return emptyList()
        val serviceIds = entities.mapNotNull { it.serviceId }.toSet()
        val refs: Map<Long, ServiceRef> =
            if (serviceIds.isEmpty()) emptyMap()
            else services.summariesOf(serviceIds).associate { r -> (r[0] as Long) to ServiceRef(r[0] as Long, r[1] as String, r[2] as Boolean) }
        return entities.map { p ->
            SlaPolicyResponse(
                id = p.requireId(), service = p.serviceId?.let { refs[it] ?: throw ServiceNotFound(it) }, priority = p.priority.name,
                responseMinutes = p.responseMinutes, resolutionMinutes = p.resolutionMinutes,
                warningThresholdPct = p.warningThresholdPct, active = p.active, version = p.version,
                createdAt = p.createdAt, updatedAt = p.updatedAt
            )
        }
    }

    private fun publish(
        policy: SlaPolicyEntity, action: String, description: String,
        previousState: String? = null, newState: String? = null, metadata: Map<String, Any> = emptyMap()
    ) = audit.publish(
        AuditableEvent(
            actor = AuditActor.of(currentUser.current()), action = action, entityType = ENTITY_TYPE,
            entityId = policy.requireId().toString(), previousState = previousState, newState = newState,
            description = description, metadata = metadata
        )
    )

    companion object {
        const val ENTITY_TYPE = "SLA_POLICY"
        private const val STATE_ACTIVE = "ACTIVE"
        private const val STATE_INACTIVE = "INACTIVE"
        private val SORTS = mapOf(
            "priority" to "priority", "responseMinutes" to "responseMinutes", "resolutionMinutes" to "resolutionMinutes",
            "updatedAt" to "updatedAt", "createdAt" to "createdAt"
        )
    }
}
