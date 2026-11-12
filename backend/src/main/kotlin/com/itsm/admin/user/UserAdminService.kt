package com.itsm.admin.user

import com.itsm.admin.support.AdvisoryLocks
import com.itsm.admin.support.CatalogText
import com.itsm.admin.support.SortParams
import com.itsm.admin.support.catalogConflict
import com.itsm.admin.support.staleVersion
import com.itsm.auth.PasswordRules
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
import com.itsm.user.EmailNormalizer
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Admin user management. Every mutation runs in ONE transaction that contains the user change, the `token_version` bump
 * (when security-relevant) and the audit event, so they commit or roll back together.
 *
 * Safety rules: an administrator cannot deactivate or demote THEMSELVES (another admin must do it), and the last active
 * ADMIN can never be deactivated or demoted. The "last admin" check runs under a transaction-scoped advisory lock shared
 * with the bootstrap command, so concurrent changes cannot both pass it.
 */
@Service
class UserAdminService(
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val audit: AuditPublisher,
    private val currentUser: CurrentUserProvider,
    private val locks: AdvisoryLocks
) {

    @Transactional(readOnly = true)
    fun list(page: PageQuery, search: String?, role: String?, active: Boolean?, sort: String?, dir: String?): PageResponse<AdminUserResponse> {
        requireAdmin()
        val roleFilter = role?.takeIf { it.isNotBlank() }?.let { parseRole(it, "role").name }
        val sorting = SortParams.of(sort, dir, SORTS, "email")
        val result = users.search(CatalogText.containsPattern(search), roleFilter, active, page.toPageable(sorting))
        return PageResponse.from(result) { toResponse(it) }
    }

    @Transactional(readOnly = true)
    fun get(id: Long): AdminUserResponse {
        requireAdmin()
        return toResponse(load(id))
    }

    @Transactional
    fun create(request: CreateUserRequest): AdminUserResponse {
        val actor = requireAdmin()
        val role = parseRole(request.role!!, "role")
        val email = EmailNormalizer.normalize(request.email!!)
        PasswordRules.require(request.password!!)
        if (users.existsByEmail(email)) throw DuplicateResource("Email is already registered")
        val saved = users.saveAndFlush(
            UserEntity(email = email, password = encoder.encode(request.password), name = request.name!!.trim(), role = role.name)
        )
        publish(actor, "USER_CREATED", saved, null, role.name, "Created a ${role.name} account", mapOf("role" to role.name))
        return toResponse(saved)
    }

    @Transactional
    fun update(id: Long, request: UpdateUserRequest): AdminUserResponse {
        val actor = requireAdmin()
        val user = load(id)
        checkVersion(user, request.version!!)
        val email = EmailNormalizer.normalize(request.email!!)
        val name = request.name!!.trim()
        val changed = buildList {
            if (email != user.email) add("email")
            if (name != user.name) add("name")
        }
        if (changed.isEmpty()) return toResponse(user)
        if ("email" in changed && users.existsByEmail(email)) throw DuplicateResource("Email is already registered")
        user.email = email
        user.name = name
        touch(user)
        val saved = users.saveAndFlush(user)
        // Values are personal data and stay out of the audit trail: only the field names are recorded.
        publish(actor, "USER_UPDATED", saved, null, null, "Updated user details (${changed.joinToString(", ")})", mapOf("changedFields" to changed))
        return toResponse(saved)
    }

    @Transactional
    fun changeRole(id: Long, request: ChangeRoleRequest): AdminUserResponse {
        val actor = requireAdmin()
        val newRole = parseRole(request.role!!, "role")
        locks.lock(AdvisoryLocks.ADMIN_ACCOUNTS)
        val user = load(id)
        checkVersion(user, request.version!!)
        val oldRole = Role.fromStored(user.role)
        if (oldRole == newRole) return toResponse(user)
        if (oldRole == Role.ADMIN) {
            if (user.id == actor.id) throw catalogConflict("You cannot change your own administrator role; ask another administrator.")
            requireOtherActiveAdmin(user)
        }
        user.role = newRole.name
        user.tokenVersion++
        touch(user)
        val saved = users.saveAndFlush(user)
        publish(
            actor, "USER_ROLE_CHANGED", saved, oldRole.name, newRole.name, "Changed role from ${oldRole.name} to ${newRole.name}",
            mapOf("roleBefore" to oldRole.name, "roleAfter" to newRole.name, "sessionsRevoked" to true)
        )
        return toResponse(saved)
    }

    @Transactional
    fun setActive(id: Long, version: Long, active: Boolean): AdminUserResponse {
        val actor = requireAdmin()
        locks.lock(AdvisoryLocks.ADMIN_ACCOUNTS)
        val user = load(id)
        checkVersion(user, version)
        if (user.active == active) return toResponse(user)
        if (!active) {
            if (user.id == actor.id) throw catalogConflict("You cannot deactivate your own account; ask another administrator.")
            if (Role.fromStored(user.role) == Role.ADMIN) requireOtherActiveAdmin(user)
        }
        user.active = active
        user.tokenVersion++   // deactivation kills old tokens; the bump on reactivation guarantees none is ever revived
        touch(user)
        val saved = users.saveAndFlush(user)
        val (before, after) = if (active) "INACTIVE" to "ACTIVE" else "ACTIVE" to "INACTIVE"
        publish(
            actor, if (active) "USER_ACTIVATED" else "USER_DEACTIVATED", saved, before, after,
            if (active) "Activated the account" else "Deactivated the account",
            mapOf("activeBefore" to !active, "activeAfter" to active, "sessionsRevoked" to true)
        )
        return toResponse(saved)
    }

    @Transactional
    fun resetPassword(id: Long, password: String): AdminUserResponse {
        val actor = requireAdmin()
        PasswordRules.require(password)
        val user = load(id)
        user.password = encoder.encode(password)
        user.tokenVersion++
        touch(user)
        val saved = users.saveAndFlush(user)
        publish(actor, "USER_PASSWORD_RESET", saved, null, null, "Reset the password", mapOf("sessionsRevoked" to true))
        return toResponse(saved)
    }

    @Transactional
    fun revokeSessions(id: Long): AdminUserResponse {
        val actor = requireAdmin()
        val user = load(id)
        user.tokenVersion++
        touch(user)
        val saved = users.saveAndFlush(user)
        publish(actor, "USER_SESSIONS_REVOKED", saved, null, null, "Revoked all sessions", mapOf("sessionsRevoked" to true))
        return toResponse(saved)
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun requireAdmin(): CurrentUser = currentUser.current().also { it.requirePermission(Permission.USER_MANAGE) }

    private fun load(id: Long): UserEntity = users.findById(id).orElseThrow { ResourceNotFound("User", id) }

    private fun checkVersion(user: UserEntity, version: Long) {
        if (user.version != version) throw staleVersion("user")
    }

    private fun touch(user: UserEntity) { user.updatedAt = Instant.now() }

    /** Caller holds [AdvisoryLocks.ADMIN_ACCOUNTS]. [user] is an active ADMIN about to stop being one. */
    private fun requireOtherActiveAdmin(user: UserEntity) {
        if (!user.active) return
        if (users.countByRoleAndActiveTrue(Role.ADMIN.name) <= 1) {
            throw catalogConflict("This is the last active administrator and cannot be demoted or deactivated.")
        }
    }

    private fun parseRole(value: String, field: String): Role =
        Role.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw InvalidInput("Invalid role", listOf(FieldError(field, "must be one of ${Role.entries.map { it.name }}")))

    private fun publish(
        actor: CurrentUser, action: String, target: UserEntity, before: String?, after: String?,
        description: String, metadata: Map<String, Any>
    ) = audit.publish(
        AuditableEvent(
            actor = AuditActor.of(actor), action = action, entityType = ENTITY_TYPE, entityId = target.id.toString(),
            previousState = before, newState = after, description = description,
            metadata = metadata + ("targetUserId" to target.id)
        )
    )

    private fun toResponse(u: UserEntity) = AdminUserResponse(
        id = u.id, email = u.email, name = u.name, role = Role.fromStored(u.role).name, active = u.active,
        version = u.version, createdAt = u.createdAt.toString(), updatedAt = u.updatedAt
    )

    companion object {
        const val ENTITY_TYPE = "USER"
        private val SORTS = mapOf("email" to "email", "name" to "name", "role" to "role", "createdAt" to "createdAt", "active" to "active")
    }
}
