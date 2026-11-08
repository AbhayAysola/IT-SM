package com.itsm.common.security

import com.itsm.common.model.Role
import java.util.Collections
import java.util.EnumMap

/**
 * Fine-grained permissions. Use them in `@PreAuthorize("hasAuthority('USER_MANAGE')")`.
 * Prefer permissions over raw roles so a rule survives role re-shuffling.
 * New permissions are added by P1 via PR so the role mapping stays in one place.
 */
enum class Permission {
    INCIDENT_CREATE,
    INCIDENT_VIEW_OWN,
    INCIDENT_VIEW_ASSIGNED,
    INCIDENT_VIEW_ALL,
    INCIDENT_ASSIGN,
    INCIDENT_WORK,          // acknowledge / investigate / resolve
    INCIDENT_VERIFY_OWN,    // reporter confirms the fix + feedback
    INCIDENT_CLOSE,
    USER_MANAGE,
    CATALOG_MANAGE,         // categories + IT services
    SLA_CONFIGURE,
    ASSIGNMENT_CONFIGURE,
    AUDIT_VIEW,
    ANALYTICS_VIEW
}

/** Single source of truth for role -> permissions. Immutable. */
object RolePermissions {
    private val grants: Map<Role, Set<Permission>> = EnumMap<Role, Set<Permission>>(Role::class.java).apply {
        put(Role.EMPLOYEE, setOf(
            Permission.INCIDENT_CREATE, Permission.INCIDENT_VIEW_OWN, Permission.INCIDENT_VERIFY_OWN
        ).readOnly())
        put(Role.TECHNICIAN, setOf(
            Permission.INCIDENT_CREATE, Permission.INCIDENT_VIEW_OWN, Permission.INCIDENT_VIEW_ASSIGNED,
            Permission.INCIDENT_WORK
        ).readOnly())
        put(Role.ADMIN, Permission.entries.toSet().readOnly())
    }.let { Collections.unmodifiableMap(it) }

    fun of(role: Role): Set<Permission> = grants.getValue(role)

    private fun <T> Set<T>.readOnly(): Set<T> = Collections.unmodifiableSet(LinkedHashSet(this))
}
