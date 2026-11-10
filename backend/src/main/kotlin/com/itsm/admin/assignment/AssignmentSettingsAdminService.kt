package com.itsm.admin.assignment

import com.itsm.admin.category.CategoryRepository
import com.itsm.admin.support.staleVersion
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.spi.AssignmentStrategyCatalog
import com.itsm.common.spi.AuditPublisher
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Assignment configuration (strategy key, default workload, per-category overrides). Configuration only: P3 owns the
 * strategies, the algorithms and the workload calculation.
 *
 * Strategy keys are validated against P3's [AssignmentStrategyCatalog]. That bean does not exist until P3 merges, so
 * it is looked up lazily: the application starts without it, reads work, and a WRITE answers 503 instead of
 * accepting a key nobody has verified. Once the bean exists no code change is needed.
 */
@Service
class AssignmentSettingsAdminService(
    private val settings: AssignmentSettingsRepository,
    private val categories: CategoryRepository,
    private val strategyCatalog: ObjectProvider<AssignmentStrategyCatalog>,
    private val audit: AuditPublisher,
    private val currentUser: CurrentUserProvider
) {

    @Transactional(readOnly = true)
    fun get(): AssignmentSettingsResponse = toResponse(load())

    @Transactional
    fun update(request: UpdateAssignmentSettingsRequest): AssignmentSettingsResponse {
        val current = load()
        if (request.version != current.version) throw staleVersion("assignment configuration")

        val strategy = AssignmentSettingsRules.strategyKey(request.activeStrategy!!, "activeStrategy")
        val workload = request.defaultMaxWorkload!!
        AssignmentSettingsRules.workload(workload)
        val overrides = AssignmentSettingsRules.overrides(request.categoryOverrides)

        requireRegistered(strategy, overrides)
        requireCategoriesExist(overrides.keys)

        val changed = buildList {
            if (strategy != current.activeStrategy) add("activeStrategy")
            if (workload != current.defaultMaxWorkload) add("defaultMaxWorkload")
            if (overrides != current.categoryOverrides) add("categoryOverrides")
        }
        if (changed.isEmpty()) return toResponse(current)

        val before = Snapshot(current.activeStrategy, current.defaultMaxWorkload, LinkedHashMap(current.categoryOverrides))
        current.activeStrategy = strategy
        current.defaultMaxWorkload = workload
        current.categoryOverrides.keys.retainAll(overrides.keys)
        current.categoryOverrides.putAll(overrides)
        current.updatedBy = currentUser.current().id
        current.updatedAt = Instant.now()
        val saved = settings.saveAndFlush(current)

        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(currentUser.current()),
                action = "ASSIGNMENT_SETTINGS_UPDATED",
                entityType = ENTITY_TYPE,
                entityId = AssignmentSettingsEntity.SINGLETON_ID.toString(),
                description = "Updated assignment settings (${changed.joinToString(", ")})",
                metadata = auditMetadata(changed, before, saved)
            )
        )
        return toResponse(saved)
    }

    // --- rules -------------------------------------------------------------------------------------------------------

    /** 503 without P3's catalog (never trust an unverified key); 400 for a key the catalog does not know. */
    private fun requireRegistered(strategy: String, overrides: Map<Long, String>) {
        val catalog = strategyCatalog.getIfAvailable() ?: throw ItsmException(
            ErrorCode.SERVICE_UNAVAILABLE,
            "Assignment settings cannot be changed right now: the assignment strategy catalog is not available, " +
                "so strategy keys cannot be verified. Try again later."
        )
        val registered = catalog.registeredKeys()
        val errors = buildList {
            if (strategy !in registered) add(FieldError("activeStrategy", "'$strategy' is not a registered strategy"))
            overrides.entries.forEachIndexed { i, (_, key) ->
                if (key !in registered) add(FieldError("categoryOverrides[$i].strategy", "'$key' is not a registered strategy"))
            }
        }
        if (errors.isNotEmpty()) throw InvalidInput("Unregistered assignment strategy. Registered: ${registered.sorted()}", errors)
    }

    private fun requireCategoriesExist(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val found = categories.findAllById(ids).mapNotNull { it.id }.toSet()
        val missing = ids - found
        if (missing.isNotEmpty()) throw InvalidInput(
            "Unknown category", listOf(FieldError("categoryOverrides", "categories do not exist: ${missing.sorted()}"))
        )
    }

    // --- mapping ---------------------------------------------------------------------------------------------------

    private data class Snapshot(val strategy: String, val workload: Int, val overrides: Map<Long, String>)

    private fun load(): AssignmentSettingsEntity = settings.findById(AssignmentSettingsEntity.SINGLETON_ID)
        .orElseThrow { IllegalStateException("The assignment settings row is missing (migration V14 seeds it)") }

    private fun toResponse(s: AssignmentSettingsEntity): AssignmentSettingsResponse {
        val names = if (s.categoryOverrides.isEmpty()) emptyMap()
        else categories.findAllById(s.categoryOverrides.keys).associateBy { it.id }
        val overrides = s.categoryOverrides.map { (categoryId, strategy) ->
            val c = names[categoryId]
            OverrideResponse(categoryId, c?.name ?: "Unknown category", c?.active ?: false, strategy)
        }.sortedBy { it.categoryName.lowercase() }
        return AssignmentSettingsResponse(
            activeStrategy = s.activeStrategy, defaultMaxWorkload = s.defaultMaxWorkload, categoryOverrides = overrides,
            availableStrategies = strategyCatalog.getIfAvailable()?.registeredKeys()?.sorted(),
            version = s.version, updatedAt = s.updatedAt, updatedByUserId = s.updatedBy
        )
    }

    private fun auditMetadata(changed: List<String>, before: Snapshot, after: AssignmentSettingsEntity): Map<String, Any> = buildMap {
        put("changedFields", changed)
        if ("activeStrategy" in changed) { put("activeStrategyBefore", before.strategy); put("activeStrategyAfter", after.activeStrategy) }
        if ("defaultMaxWorkload" in changed) {
            put("defaultMaxWorkloadBefore", before.workload); put("defaultMaxWorkloadAfter", after.defaultMaxWorkload)
        }
        if ("categoryOverrides" in changed) {
            val added = after.categoryOverrides.keys - before.overrides.keys
            val removed = before.overrides.keys - after.categoryOverrides.keys
            val retargeted = after.categoryOverrides.filter { (k, v) -> before.overrides[k] != null && before.overrides[k] != v }.keys
            fun ids(key: String, set: Set<Long>) { if (set.isNotEmpty()) put(key, set.sorted().take(AuditableEvent.MAX_LIST)) }
            ids("overridesAddedCategoryIds", added)
            ids("overridesRemovedCategoryIds", removed)
            ids("overridesChangedCategoryIds", retargeted)
            put("overrideCountBefore", before.overrides.size)
            put("overrideCountAfter", after.categoryOverrides.size)
        }
    }

    companion object {
        const val ENTITY_TYPE = "ASSIGNMENT_SETTINGS"
    }
}
