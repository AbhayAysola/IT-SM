package com.itsm.common.security

import com.itsm.common.error.AuthenticationFailed
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.model.Role
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** Immutable identity of the caller. Other modules depend on this, never on the user entity. */
data class CurrentUser(val id: Long, val email: String, val role: Role) {
    init {
        require(id > 0) { "CurrentUser id must be positive" }
    }

    val permissions: Set<Permission> get() = RolePermissions.of(role)
    val isAdmin: Boolean get() = role == Role.ADMIN

    fun has(permission: Permission): Boolean = permission in permissions

    fun requirePermission(permission: Permission) {
        if (!has(permission)) throw UnauthorizedOperation()
    }
}

interface CurrentUserProvider {
    /** The authenticated caller, or null if the request is anonymous / unauthenticated. */
    fun currentOrNull(): CurrentUser?

    /** The authenticated caller; throws [AuthenticationFailed] (401) if there is none. */
    fun current(): CurrentUser = currentOrNull() ?: throw AuthenticationFailed()
}

@Component
class SecurityContextCurrentUserProvider : CurrentUserProvider {
    override fun currentOrNull(): CurrentUser? {
        val auth = SecurityContextHolder.getContext().authentication ?: return null
        if (!auth.isAuthenticated || auth is AnonymousAuthenticationToken) return null
        val principal = auth.principal
        return (principal as? ItsmUserDetails)?.toCurrentUser()
            // An authenticated principal of another type is a wiring bug, not a client error.
            ?: throw IllegalStateException("Unsupported principal type: ${principal?.javaClass?.name}")
    }
}
