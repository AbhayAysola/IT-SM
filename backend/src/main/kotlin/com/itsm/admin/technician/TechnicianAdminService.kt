package com.itsm.admin.technician

import com.itsm.admin.support.AdvisoryLocks
import com.itsm.admin.support.CatalogText
import com.itsm.admin.support.catalogConflict
import com.itsm.admin.support.staleVersion
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.security.Permission
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.UserDirectory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Technician profile administration. Every mutation is ONE transaction holding the change and its audit event. Users are
 * checked through the [UserDirectory] SPI and categories through [CategoryCatalog] (no other module's repository); V16's
 * triggers and constraints are the authority behind these friendlier checks.
 *
 * A profile never changes a user's role (that stays in user administration) and `available` is only an administrator flag:
 * workload, recommendations and assignment belong to P3.
 */
@Service
class TechnicianAdminService(
    private val profiles: TechnicianProfileRepository,
    private val queries: TechnicianQueries,
    private val users: UserDirectory,
    private val categories: CategoryCatalog,
    private val audit: AuditPublisher,
    private val currentUser: CurrentUserProvider,
    private val locks: AdvisoryLocks
) {

    @Transactional(readOnly = true)
    fun list(
        page: PageQuery, search: String?, active: Boolean?, available: Boolean?, teamLead: Boolean?, categoryId: Long?,
        sort: String?, dir: String?
    ): PageResponse<TechnicianResponse> {
        requireAdmin()
        val key = sort?.trim()?.takeIf { it.isNotEmpty() } ?: "name"
        val column = SORTS[key] ?: throw InvalidInput("Unsupported sort", listOf(FieldError("sort", "must be one of ${SORTS.keys.sorted()}")))
        val direction = when (dir?.trim()?.lowercase()) {
            null, "", "asc" -> "asc"
            "desc" -> "desc"
            else -> throw InvalidInput("Unsupported sort direction", listOf(FieldError("dir", "must be asc or desc")))
        }
        return queries.page(page, CatalogText.containsPattern(search), active, available, teamLead, categoryId, "$column $direction")
    }

    @Transactional(readOnly = true)
    fun get(userId: Long): TechnicianResponse {
        requireAdmin()
        return queries.get(userId) ?: throw ResourceNotFound("Technician profile", userId)
    }

    @Transactional
    fun create(request: CreateTechnicianRequest): TechnicianResponse {
        val actor = requireAdmin()
        val userId = request.userId!!
        TechnicianRules.details(request.maxWorkload!!, request.experienceYears!!)
        val skills = TechnicianRules.skills(request.skills)
        locks.lock(AdvisoryLocks.TECHNICIAN_GRAPH)

        val user = users.findById(userId)
            ?: throw InvalidInput("Unknown user", listOf(FieldError("userId", "user $userId does not exist")))
        if (user.role != Role.TECHNICIAN) {
            throw catalogConflict("User $userId has role ${user.role}; only a TECHNICIAN can have a technician profile. Change the role in user administration first.")
        }
        if (!user.active) throw catalogConflict("User $userId is deactivated.")
        if (profiles.existsById(userId)) throw DuplicateResource("User $userId already has a technician profile")

        val teamLead = request.isTeamLead ?: false
        requireManager(userId, request.reportsToUserId)
        requireActiveCategories(skills.keys)

        val entity = TechnicianProfileEntity(
            userId = userId, maxWorkload = request.maxWorkload, experienceYears = request.experienceYears,
            available = request.available!!, teamLead = teamLead, reportsToUserId = request.reportsToUserId, skills = skills.toMutableMap()
        )
        profiles.saveAndFlush(entity)
        publish(
            actor, "TECHNICIAN_PROFILE_CREATED", userId, null, "ACTIVE", "Created a technician profile",
            buildMap {
                put("maxWorkload", entity.maxWorkload); put("experienceYears", entity.experienceYears)
                put("available", entity.available); put("teamLead", entity.teamLead)
                entity.reportsToUserId?.let { put("reportsToUserId", it) }
                put("skillCount", skills.size); put("skillCategoryIds", skills.keys.sorted().take(AuditableEvent.MAX_LIST))
            }
        )
        return queries.get(userId)!!
    }

    @Transactional
    fun update(userId: Long, request: UpdateTechnicianRequest): TechnicianResponse {
        val actor = requireAdmin()
        TechnicianRules.details(request.maxWorkload!!, request.experienceYears!!)
        locks.lock(AdvisoryLocks.TECHNICIAN_GRAPH)
        val p = load(userId)
        checkVersion(p, request.version!!)

        val newLead = request.isTeamLead!!
        val newManager = request.reportsToUserId
        val changed = buildList {
            if (request.maxWorkload != p.maxWorkload) add("maxWorkload")
            if (request.experienceYears != p.experienceYears) add("experienceYears")
            if (request.available != p.available) add("available")
            if (newLead != p.teamLead) add("teamLead")
            if (newManager != p.reportsToUserId) add("reportsToUserId")
        }
        if (changed.isEmpty()) return queries.get(userId)!!

        if ("reportsToUserId" in changed) requireManager(userId, newManager)
        if (!newLead && p.teamLead && queries.existsReportsTo(userId)) {
            throw catalogConflict("Other technicians still report to this team lead; move them first.")
        }
        val before = Snapshot(p.maxWorkload, p.experienceYears, p.available, p.teamLead, p.reportsToUserId)
        p.maxWorkload = request.maxWorkload; p.experienceYears = request.experienceYears; p.available = request.available!!
        p.teamLead = newLead; p.reportsToUserId = newManager
        p.touch()
        profiles.saveAndFlush(p)

        val detailFields = changed.filter { it != "reportsToUserId" }
        if (detailFields.isNotEmpty()) {
            publish(actor, "TECHNICIAN_PROFILE_UPDATED", userId, null, null, "Updated technician details (${detailFields.joinToString(", ")})", buildMap {
                put("changedFields", detailFields)
                if ("maxWorkload" in changed) { put("maxWorkloadBefore", before.maxWorkload); put("maxWorkloadAfter", p.maxWorkload) }
                if ("experienceYears" in changed) { put("experienceYearsBefore", before.experienceYears); put("experienceYearsAfter", p.experienceYears) }
                if ("available" in changed) { put("availableBefore", before.available); put("availableAfter", p.available) }
                if ("teamLead" in changed) { put("teamLeadBefore", before.teamLead); put("teamLeadAfter", p.teamLead) }
            })
        }
        if ("reportsToUserId" in changed) {
            publish(actor, "TECHNICIAN_REPORTING_CHANGED", userId, null, null, "Changed the reporting line", buildMap {
                before.reportsTo?.let { put("reportsToBefore", it) }
                p.reportsToUserId?.let { put("reportsToAfter", it) }
            })
        }
        return queries.get(userId)!!
    }

    @Transactional
    fun updateSkills(userId: Long, request: UpdateSkillsRequest): TechnicianResponse {
        val actor = requireAdmin()
        val wanted = TechnicianRules.skills(request.skills)
        val p = load(userId)
        checkVersion(p, request.version!!)

        val current = p.skills.toMap()
        val added = wanted.keys - current.keys
        val removed = current.keys - wanted.keys
        val retuned = wanted.filter { (k, v) -> current[k] != null && current[k] != v }.keys
        if (added.isEmpty() && removed.isEmpty() && retuned.isEmpty()) return queries.get(userId)!!

        requireActiveCategories(added)   // skills already held for a since-deactivated category may stay or be removed
        p.skills.keys.retainAll(wanted.keys)
        p.skills.putAll(wanted)
        p.touch()
        profiles.saveAndFlush(p)

        fun ids(set: Set<Long>) = set.sorted().take(AuditableEvent.MAX_LIST)
        publish(actor, "TECHNICIAN_SKILLS_CHANGED", userId, null, null, "Changed technician skills", buildMap {
            if (added.isNotEmpty()) put("addedCategoryIds", ids(added))
            if (removed.isNotEmpty()) put("removedCategoryIds", ids(removed))
            if (retuned.isNotEmpty()) put("changedCategoryIds", ids(retuned))
            put("skillCountBefore", current.size); put("skillCountAfter", wanted.size)
        })
        return queries.get(userId)!!
    }

    @Transactional
    fun setActive(userId: Long, version: Long, active: Boolean): TechnicianResponse {
        val actor = requireAdmin()
        locks.lock(AdvisoryLocks.TECHNICIAN_GRAPH)
        val p = load(userId)
        checkVersion(p, version)
        if (p.active == active) return queries.get(userId)!!

        if (active) {
            val user = users.findById(userId)
            if (user == null || user.role != Role.TECHNICIAN) {
                throw catalogConflict("The user is no longer a TECHNICIAN; restore the role in user administration before reactivating the profile.")
            }
            p.reportsToUserId?.let { manager ->
                val state = queries.managerState(manager)
                if (state == null || !state.first) throw catalogConflict("The manager's profile is inactive; change the reporting line first.")
            }
        } else if (profiles.countByReportsToUserIdAndActiveTrue(userId) > 0) {
            throw catalogConflict("Active technicians still report to this technician; move them before deactivating the profile.")
        }
        p.active = active
        p.touch()
        profiles.saveAndFlush(p)
        val (from, to) = if (active) "INACTIVE" to "ACTIVE" else "ACTIVE" to "INACTIVE"
        publish(actor, if (active) "TECHNICIAN_PROFILE_ACTIVATED" else "TECHNICIAN_PROFILE_DEACTIVATED", userId, from, to,
            if (active) "Activated the technician profile" else "Deactivated the technician profile",
            mapOf("activeBefore" to !active, "activeAfter" to active))
        return queries.get(userId)!!
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private data class Snapshot(val maxWorkload: Int, val experienceYears: Int, val available: Boolean, val teamLead: Boolean, val reportsTo: Long?)

    private fun requireAdmin(): CurrentUser = currentUser.current().also { it.requirePermission(Permission.USER_MANAGE) }

    private fun load(userId: Long): TechnicianProfileEntity = profiles.findById(userId).orElseThrow { ResourceNotFound("Technician profile", userId) }

    private fun checkVersion(p: TechnicianProfileEntity, version: Long) {
        if (p.version != version) throw staleVersion("technician profile")
    }

    /** Caller holds the graph lock. Friendly validation of a reporting line; V16 enforces the same rules. */
    private fun requireManager(userId: Long, managerId: Long?) {
        if (managerId == null) return
        fun bad(message: String): Nothing = throw InvalidInput("Invalid reporting line", listOf(FieldError("reportsToUserId", message)))
        if (managerId == userId) bad("a technician cannot report to themselves")
        val state = queries.managerState(managerId) ?: bad("user $managerId has no technician profile")
        if (!state.first) bad("the manager's technician profile is inactive")
        if (!state.second) bad("the manager is not flagged as a team lead")
        val managerUser = users.findById(managerId)
        if (managerUser == null || !managerUser.active || managerUser.role != Role.TECHNICIAN) bad("the manager's user account is inactive or not a TECHNICIAN")
        if (queries.wouldCycle(userId, managerId)) throw catalogConflict("That reporting line would create a cycle.")
    }

    private fun requireActiveCategories(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val errors = ids.sorted().mapNotNull { id ->
            val category = categories.findById(id)
            when {
                category == null -> FieldError("skills", "category $id does not exist")
                !category.active -> FieldError("skills", "category $id (${category.code}) is not active")
                else -> null
            }
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid skills", errors)
    }

    private fun publish(
        actor: CurrentUser, action: String, userId: Long, before: String?, after: String?, description: String, metadata: Map<String, Any>
    ) = audit.publish(
        AuditableEvent(
            actor = AuditActor.of(actor), action = action, entityType = ENTITY_TYPE, entityId = userId.toString(),
            previousState = before, newState = after, description = description, metadata = metadata + ("targetUserId" to userId)
        )
    )

    companion object {
        const val ENTITY_TYPE = "TECHNICIAN"
        private val SORTS = mapOf(
            "name" to "lower(u.name)", "email" to "lower(u.email)", "maxWorkload" to "p.max_workload",
            "experienceYears" to "p.experience_years", "createdAt" to "p.created_at"
        )
    }
}
