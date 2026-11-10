package com.itsm.admin.itservice

import com.itsm.admin.support.AdvisoryLocks
import com.itsm.admin.support.CatalogText
import com.itsm.admin.support.SortParams
import com.itsm.admin.support.catalogConflict
import com.itsm.admin.support.staleVersion
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.spi.AuditPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * IT service administration.
 *
 * Invariants (all checked under the SERVICE_GRAPH advisory lock, which V12's cycle trigger also takes):
 *  - the dependency graph is acyclic (also enforced by the database trigger);
 *  - an ACTIVE service depends only on ACTIVE services, so: new or changed dependencies of an active service must be
 *    active, a service cannot be deactivated while an active service depends on it, and a service cannot be
 *    activated while one of its dependencies is inactive.
 *
 * Audit: create, field update, dependency change, activation and deactivation each publish one event inside the
 * transaction. An update that changes both fields and dependencies publishes IT_SERVICE_UPDATED and
 * IT_SERVICE_DEPENDENCIES_CHANGED. No-op requests publish nothing.
 */
@Service
class ItServiceAdminService(
    private val services: ItServiceRepository,
    private val audit: AuditPublisher,
    private val currentUser: CurrentUserProvider,
    private val locks: AdvisoryLocks
) {

    @Transactional(readOnly = true)
    fun list(
        query: PageQuery, search: String?, active: Boolean?, criticality: Int?, ownerTeam: String?, sort: String?, dir: String?
    ): PageResponse<ServiceResponse> {
        if (criticality != null && criticality !in 1..5) {
            throw InvalidInput("Invalid criticality filter", listOf(FieldError("criticality", "must be between 1 and 5")))
        }
        val pageable = query.toPageable(SortParams.of(sort, dir, SORTS, default = "name"))
        val page = services.search(active, criticality, CatalogText.normalizeOptional(ownerTeam)?.lowercase(), CatalogText.containsPattern(search), pageable)
        val responses = toResponses(page.content)
        return PageResponse(responses, page.number, page.size, page.totalElements, page.totalPages)
    }

    @Transactional(readOnly = true)
    fun get(id: Long): ServiceResponse = toResponses(listOf(load(id))).single()

    @Transactional
    fun create(request: CreateServiceRequest): ServiceResponse {
        val name = ItServiceRules.name(request.name!!)
        val dependencies = ItServiceRules.dependencyIds(request.dependsOnServiceIds, selfId = null)
        requireNameFree(name, excludeId = null)

        locks.lock(AdvisoryLocks.SERVICE_GRAPH)
        // A brand-new service has no dependents, so its edges cannot close a cycle.
        requireUsableDependencies(dependencies, dependentIsActive = true)

        val saved = services.saveAndFlush(
            ItServiceEntity(
                name = name,
                description = ItServiceRules.optional(request.description),
                businessCriticality = request.businessCriticality!!,
                ownerTeam = ItServiceRules.optional(request.ownerTeam),
                active = true,
                dependsOn = dependencies.toMutableSet()
            )
        )
        publish(
            saved, "IT_SERVICE_CREATED", "Created IT service '${saved.name}'", newState = STATE_ACTIVE,
            metadata = buildMap {
                put("name", saved.name)
                put("businessCriticality", saved.businessCriticality)
                saved.ownerTeam?.let { put("ownerTeam", it) }
                putIds("dependsOnServiceIds", "dependencyCount", dependencies)
            }
        )
        return get(saved.requireId())
    }

    @Transactional
    fun update(id: Long, request: UpdateServiceRequest): ServiceResponse {
        val service = load(id)
        if (request.version != service.version) throw staleVersion("IT service")

        val name = ItServiceRules.name(request.name!!)
        val description = ItServiceRules.optional(request.description)
        val criticality = request.businessCriticality!!
        val ownerTeam = ItServiceRules.optional(request.ownerTeam)
        val dependencies = ItServiceRules.dependencyIds(request.dependsOnServiceIds, selfId = id)
        if (!name.equals(service.name, ignoreCase = true)) requireNameFree(name, excludeId = id)

        val added = dependencies - service.dependsOn
        val removed = service.dependsOn - dependencies
        if (added.isNotEmpty()) {
            locks.lock(AdvisoryLocks.SERVICE_GRAPH)
            requireUsableDependencies(added, dependentIsActive = service.active)
            requireNoCycle(id, service.name, dependencies)
        }

        val before = Snapshot(service.name, service.description, service.businessCriticality, service.ownerTeam)
        val changed = buildList {
            if (name != before.name) add("name")
            if (description != before.description) add("description")
            if (criticality != before.businessCriticality) add("businessCriticality")
            if (ownerTeam != before.ownerTeam) add("ownerTeam")
        }
        if (changed.isEmpty() && added.isEmpty() && removed.isEmpty()) return get(id)

        service.name = name
        service.description = description
        service.businessCriticality = criticality
        service.ownerTeam = ownerTeam
        service.dependsOn.removeAll(removed)
        service.dependsOn.addAll(added)
        val saved = services.saveAndFlush(service)

        if (changed.isNotEmpty()) publish(
            saved, "IT_SERVICE_UPDATED", "Updated IT service '${saved.name}'",
            metadata = buildMap {
                put("changedFields", changed)
                if ("name" in changed) { put("nameBefore", before.name); put("nameAfter", name) }
                if ("businessCriticality" in changed) {
                    put("businessCriticalityBefore", before.businessCriticality); put("businessCriticalityAfter", criticality)
                }
                if ("ownerTeam" in changed) {
                    before.ownerTeam?.let { put("ownerTeamBefore", it) }; ownerTeam?.let { put("ownerTeamAfter", it) }
                }
            }
        )
        if (added.isNotEmpty() || removed.isNotEmpty()) publish(
            saved, "IT_SERVICE_DEPENDENCIES_CHANGED", "Changed the dependencies of IT service '${saved.name}'",
            metadata = buildMap {
                putIds("addedDependencyIds", "addedCount", added)
                putIds("removedDependencyIds", "removedCount", removed)
            }
        )
        return get(id)
    }

    @Transactional
    fun activate(id: Long): ServiceResponse {
        locks.lock(AdvisoryLocks.SERVICE_GRAPH)
        val service = load(id)
        if (service.active) return get(id)
        val inactive = summaries(service.dependsOn).values.filter { !it.active }.map { it.name }.sorted()
        if (inactive.isNotEmpty()) throw catalogConflict(
            "'${service.name}' depends on inactive ${inactive.joinToString(", ") { "'$it'" }}. " +
                "Reactivate those services or remove the dependencies first."
        )
        service.active = true
        val saved = services.saveAndFlush(service)
        publish(saved, "IT_SERVICE_ACTIVATED", "Activated IT service '${saved.name}'",
            previousState = STATE_INACTIVE, newState = STATE_ACTIVE)
        return get(id)
    }

    /** Soft delete; refused while an active service still depends on this one. */
    @Transactional
    fun deactivate(id: Long): ServiceResponse {
        locks.lock(AdvisoryLocks.SERVICE_GRAPH)
        val service = load(id)
        if (!service.active) return get(id)
        val dependents = services.activeDependentNames(id)
        if (dependents.isNotEmpty()) throw catalogConflict(
            "'${service.name}' is a dependency of active ${dependents.joinToString(", ") { "'$it'" }}. " +
                "Remove it from those services or deactivate them first."
        )
        service.active = false
        val saved = services.saveAndFlush(service)
        publish(saved, "IT_SERVICE_DEACTIVATED", "Deactivated IT service '${saved.name}'",
            previousState = STATE_ACTIVE, newState = STATE_INACTIVE)
        return get(id)
    }

    // --- rules -------------------------------------------------------------------------------------------------------

    private fun load(id: Long): ItServiceEntity = services.findById(id).orElseThrow { ServiceNotFound(id) }

    private fun requireNameFree(name: String, excludeId: Long?) {
        if (services.existsByNameIgnoreCase(name, excludeId)) throw DuplicateResource("An IT service named '$name' already exists")
    }

    /** Every id must exist (400 otherwise); if the dependent service is active they must all be active (409). */
    private fun requireUsableDependencies(ids: Set<Long>, dependentIsActive: Boolean) {
        if (ids.isEmpty()) return
        val found = summaries(ids)
        val missing = ids - found.keys
        if (missing.isNotEmpty()) throw InvalidInput(
            "Unknown dependency", listOf(FieldError("dependsOnServiceIds", "IT services do not exist: ${missing.sorted()}"))
        )
        if (dependentIsActive) {
            val inactive = found.values.filter { !it.active }.map { it.name }.sorted()
            if (inactive.isNotEmpty()) throw catalogConflict(
                "${inactive.joinToString(", ") { "'$it'" }} ${if (inactive.size == 1) "is" else "are"} inactive " +
                    "and cannot be used as a dependency of an active service."
            )
        }
    }

    /** Rejects the new dependency set if any dependency can already reach [selfId]. Reports the cycle path. */
    private fun requireNoCycle(selfId: Long, selfName: String, newDependencies: Set<Long>) {
        val graph = HashMap<Long, MutableSet<Long>>()
        for (edge in services.allEdges()) graph.getOrPut(edge[0] as Long) { mutableSetOf() } += edge[1] as Long
        graph[selfId] = newDependencies.toMutableSet()

        for (start in newDependencies) {
            val path = findPath(graph, start, selfId) ?: continue
            val names = summaries(path.toSet())
            val described = (listOf(selfName) + path.map { names[it]?.name ?: "#$it" }).joinToString(" -> ")
            throw catalogConflict("That would create a circular dependency: $described.")
        }
    }

    private fun findPath(graph: Map<Long, Set<Long>>, from: Long, to: Long): List<Long>? {
        val previous = HashMap<Long, Long>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == to) {
                val path = generateSequence(to) { previous[it] }.toList().asReversed()
                return path
            }
            for (next in graph[current].orEmpty()) if (seen.add(next)) { previous[next] = current; queue.addLast(next) }
        }
        return null
    }

    // --- mapping -----------------------------------------------------------------------------------------------------

    private data class Summary(val name: String, val active: Boolean)
    private data class Snapshot(val name: String, val description: String?, val businessCriticality: Int, val ownerTeam: String?)

    private fun summaries(ids: Collection<Long>): Map<Long, Summary> =
        if (ids.isEmpty()) emptyMap()
        else services.summariesOf(ids).associate { (it[0] as Long) to Summary(it[1] as String, it[2] as Boolean) }

    /** Maps a page with a fixed number of queries: dependencies are batch-loaded, dependents and labels in one query each. */
    private fun toResponses(entities: List<ItServiceEntity>): List<ServiceResponse> {
        if (entities.isEmpty()) return emptyList()
        val ids = entities.map { it.requireId() }
        val dependents = HashMap<Long, MutableList<Long>>()
        for (edge in services.dependentEdgesOf(ids)) dependents.getOrPut(edge[1] as Long) { mutableListOf() } += edge[0] as Long
        val labels = summaries(entities.flatMap { it.dependsOn } + dependents.values.flatten())

        fun refs(refIds: Collection<Long>) =
            refIds.mapNotNull { id -> labels[id]?.let { ServiceRef(id, it.name, it.active) } }.sortedBy { it.name.lowercase() }

        return entities.map { s ->
            ServiceResponse(
                id = s.requireId(),
                name = s.name,
                description = s.description,
                businessCriticality = s.businessCriticality,
                ownerTeam = s.ownerTeam,
                active = s.active,
                dependsOn = refs(s.dependsOn),
                dependents = refs(dependents[s.requireId()].orEmpty()),
                version = s.version,
                createdAt = s.createdAt,
                updatedAt = s.updatedAt
            )
        }
    }

    private fun publish(
        service: ItServiceEntity,
        action: String,
        description: String,
        previousState: String? = null,
        newState: String? = null,
        metadata: Map<String, Any> = emptyMap()
    ) = audit.publish(
        AuditableEvent(
            actor = AuditActor.of(currentUser.current()),
            action = action,
            entityType = ENTITY_TYPE,
            entityId = service.requireId().toString(),
            previousState = previousState,
            newState = newState,
            description = description,
            metadata = metadata
        )
    )

    /** Audit metadata lists are capped at 20 items: store the first 20 ids (sorted) plus the true count. */
    private fun MutableMap<String, Any>.putIds(listKey: String, countKey: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        put(listKey, ids.sorted().take(AuditableEvent.MAX_LIST))
        put(countKey, ids.size)
    }

    companion object {
        const val ENTITY_TYPE = "IT_SERVICE"
        private const val STATE_ACTIVE = "ACTIVE"
        private const val STATE_INACTIVE = "INACTIVE"
        private val SORTS = mapOf(
            "name" to "name", "businessCriticality" to "businessCriticality",
            "updatedAt" to "updatedAt", "createdAt" to "createdAt"
        )
    }
}
