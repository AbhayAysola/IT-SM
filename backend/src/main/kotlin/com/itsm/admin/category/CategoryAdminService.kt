package com.itsm.admin.category

import com.itsm.admin.support.AdvisoryLocks
import com.itsm.admin.support.CatalogText
import com.itsm.admin.support.SortParams
import com.itsm.admin.support.catalogConflict
import com.itsm.admin.support.staleVersion
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.spi.AuditPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Category administration. Every successful mutation publishes exactly one audit event inside its transaction, so
 * a failed or rolled-back request leaves neither a change nor an audit row. No-op requests (activating an active
 * category, saving unchanged values) change nothing and publish nothing.
 */
@Service
class CategoryAdminService(
    private val categories: CategoryRepository,
    private val audit: AuditPublisher,
    private val currentUser: CurrentUserProvider,
    private val locks: AdvisoryLocks
) {

    @Transactional(readOnly = true)
    fun list(query: PageQuery, search: String?, active: Boolean?, sort: String?, dir: String?): PageResponse<CategoryResponse> {
        val pageable = query.toPageable(SortParams.of(sort, dir, SORTS, default = "name"))
        return PageResponse.from(categories.search(active, CatalogText.containsPattern(search), pageable)) { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun get(id: Long): CategoryResponse = load(id).toResponse()

    @Transactional
    fun create(request: CreateCategoryRequest): CategoryResponse {
        val code = CategoryRules.code(request.code!!)
        val name = CategoryRules.name(request.name!!)
        val description = CategoryRules.description(request.description)
        val keywords = CategoryRules.keywords(request.keywords!!)

        if (categories.existsByCode(code)) throw DuplicateResource("A category with code '$code' already exists")
        requireNameFree(name, excludeId = null)
        requireKeywordsFree(keywords, excludeId = null)

        val saved = categories.saveAndFlush(CategoryEntity(code, name, description, true, keywords.toMutableSet()))
        publish(
            saved, "CATEGORY_CREATED", "Created incident category '${saved.name}' (${saved.code})",
            newState = STATE_ACTIVE,
            metadata = mapOf("code" to saved.code, "name" to saved.name, "keywordCount" to saved.keywords.size)
        )
        return saved.toResponse()
    }

    @Transactional
    fun update(id: Long, request: UpdateCategoryRequest): CategoryResponse {
        val category = load(id)
        if (request.version != category.version) throw staleVersion("category")

        val name = CategoryRules.name(request.name!!)
        val description = CategoryRules.description(request.description)
        val keywords = CategoryRules.keywords(request.keywords!!)

        if (!name.equals(category.name, ignoreCase = true)) requireNameFree(name, excludeId = id)
        // An inactive category's keywords do not count; they are re-checked when it is activated.
        if (category.active) requireKeywordsFree(keywords, excludeId = id)

        val added = keywords - category.keywords
        val removed = category.keywords - keywords
        val changed = buildList {
            if (name != category.name) add("name")
            if (description != category.description) add("description")
            if (added.isNotEmpty() || removed.isNotEmpty()) add("keywords")
        }
        if (changed.isEmpty()) return category.toResponse()

        val previousName = category.name
        category.name = name
        category.description = description
        category.keywords.removeAll(removed)
        category.keywords.addAll(added)
        val saved = categories.saveAndFlush(category)

        val metadata = buildMap<String, Any> {
            put("changedFields", changed)
            if ("name" in changed) { put("nameBefore", previousName); put("nameAfter", name) }
            if (added.isNotEmpty()) put("keywordsAdded", added.sorted())
            if (removed.isNotEmpty()) put("keywordsRemoved", removed.sorted())
        }
        publish(saved, "CATEGORY_UPDATED", "Updated incident category '${saved.name}'", metadata = metadata)
        return saved.toResponse()
    }

    @Transactional
    fun activate(id: Long): CategoryResponse {
        locks.lock(AdvisoryLocks.CATEGORY_STATE)
        val category = load(id)
        if (category.active) return category.toResponse()
        requireKeywordsFree(category.keywords, excludeId = id)

        category.active = true
        val saved = categories.saveAndFlush(category)
        publish(saved, "CATEGORY_ACTIVATED", "Activated incident category '${saved.name}'",
            previousState = STATE_INACTIVE, newState = STATE_ACTIVE)
        return saved.toResponse()
    }

    /**
     * Soft delete. Incidents and other records keep referencing the row; it simply stops being offered for new
     * incidents and its keywords become available to other categories.
     */
    @Transactional
    fun deactivate(id: Long): CategoryResponse {
        // Serialise with other activations/deactivations so two requests cannot each see "another one is active".
        locks.lock(AdvisoryLocks.CATEGORY_STATE)
        val category = load(id)
        if (!category.active) return category.toResponse()
        if (categories.countByActiveTrue() <= 1) {
            throw catalogConflict("At least one category must stay active so new incidents can be classified.")
        }

        category.active = false
        val saved = categories.saveAndFlush(category)
        publish(saved, "CATEGORY_DEACTIVATED", "Deactivated incident category '${saved.name}'",
            previousState = STATE_ACTIVE, newState = STATE_INACTIVE)
        return saved.toResponse()
    }

    private fun load(id: Long): CategoryEntity = categories.findById(id).orElseThrow { ResourceNotFound("Category", id) }

    private fun requireNameFree(name: String, excludeId: Long?) {
        if (categories.existsByNameIgnoreCase(name, excludeId)) {
            throw DuplicateResource("A category named '$name' already exists")
        }
    }

    /** Friendly pre-check; the partial unique index on active keywords is the authoritative guard. */
    private fun requireKeywordsFree(keywords: Collection<String>, excludeId: Long?) {
        if (keywords.isEmpty()) return
        val clash = categories.findActiveKeywordClashes(keywords, excludeId ?: -1).firstOrNull() ?: return
        throw catalogConflict(
            "The keyword '${clash[1]}' is already used by the active category '${clash[0]}'. " +
                "Keywords must be unique across active categories."
        )
    }

    private fun publish(
        category: CategoryEntity,
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
            entityId = category.requireId().toString(),
            previousState = previousState,
            newState = newState,
            description = description,
            metadata = metadata
        )
    )

    companion object {
        const val ENTITY_TYPE = "CATEGORY"
        private const val STATE_ACTIVE = "ACTIVE"
        private const val STATE_INACTIVE = "INACTIVE"
        private val SORTS = mapOf("name" to "name", "code" to "code", "updatedAt" to "updatedAt", "createdAt" to "createdAt")
    }
}
