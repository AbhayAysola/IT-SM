package com.itsm.common.spi

import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Priority
import com.itsm.common.security.CurrentUser

/*
 * Cross-module contracts. Rule: a module may depend on `com.itsm.common.*` and on NOTHING
 * inside another module. "Provided by" says who writes the implementation bean.
 */

/**
 * Provided by P1 (access). Users and technician profiles.
 *
 * Until B6 lands, `findTechnician` is null and `listAvailableTechnicians` / `escalationChain` are empty because there is
 * NO profile data, not because there are no technicians (see `JpaUserDirectory.TECHNICIAN_PROFILES_AVAILABLE`).
 */
interface UserDirectory {
    fun findById(userId: Long): UserSummary?
    fun requireById(userId: Long): UserSummary = findById(userId) ?: throw ResourceNotFound("User", userId)

    fun findTechnician(userId: Long): TechnicianSummary?

    /** Active technicians flagged available; if [categoryId] is given, only those with a skill for it. */
    fun listAvailableTechnicians(categoryId: Long? = null): List<TechnicianSummary>

    /** Reporting chain above a technician, nearest team lead first. Excludes the technician. */
    fun escalationChain(technicianId: Long): List<UserSummary>

    /** Active administrators: the final escalation tier (also acts as IT manager). */
    fun listAdmins(): List<UserSummary>
}

/** Provided by P1 (admin). Dynamic incident categories. */
interface CategoryCatalog {
    fun findById(categoryId: Long): CategoryView?
    fun findByCode(code: String): CategoryView?
    fun listActive(): List<CategoryView>

    /** Returns the category if it exists and is active. 404 if unknown, 400 if deactivated. */
    fun requireActive(categoryId: Long): CategoryView {
        val category = findById(categoryId) ?: throw ResourceNotFound("Category", categoryId)
        if (!category.active) throw InvalidInput("Category '${category.code}' is no longer active")
        return category
    }
}

/** Provided by P1 (admin). IT services and their dependency graph. */
interface ServiceCatalog {
    fun findById(serviceId: Long): ServiceView?
    fun listActive(): List<ServiceView>

    /** Services that depend on [serviceId] (reverse edges) — useful for correlation / root cause. */
    fun dependentsOf(serviceId: Long): Set<Long>

    fun requireActive(serviceId: Long): ServiceView {
        val service = findById(serviceId) ?: throw ServiceNotFound(serviceId)
        if (!service.active) throw InvalidInput("Service '${service.name}' is no longer active")
        return service
    }
}

/** Provided by P1 (admin), consumed by P4. Service-specific policy first, then the global default. */
interface SlaPolicyProvider {
    /**
     * @throws com.itsm.common.error.ServiceNotFound if [serviceId] is unknown
     * @throws com.itsm.common.error.MissingSlaConfiguration if no service-specific nor default policy exists
     */
    fun resolve(serviceId: Long, priority: Priority): SlaPolicyView
}

/** Provided by P1 (admin), consumed by P3. */
interface AssignmentSettingsProvider {
    fun current(): AssignmentSettingsView
}

/**
 * Provided by P1. Publish an audit event. MUST be called inside the business transaction
 * (enforced: calling it without one throws IllegalTransactionStateException).
 *
 * Events are persisted by `AuditEventPersistenceListener` (`@TransactionalEventListener(BEFORE_COMMIT)`)
 * into the append-only `audit_events` table, inside this same transaction: the audit row and the
 * business change commit or roll back together, and a failed audit insert fails the business action.
 */
interface AuditPublisher {
    fun publish(event: AuditableEvent)
}

/** Provided by P3 (sole owner). Workload calculation is P3's domain; P1 and P4 only consume it. */
interface TechnicianWorkloadProvider {
    fun activeIncidentCount(technicianId: Long): Int
    fun activeIncidentCounts(technicianIds: Collection<Long>): Map<Long, Int>
}

/** Provided by P3 (sole owner). Lets admin configuration validate a strategy key. */
interface AssignmentStrategyCatalog {
    /** UPPER_SNAKE_CASE keys of all registered strategies, e.g. SKILL_BASED, LOAD_BASED. */
    fun registeredKeys(): Set<String>
    fun isRegistered(key: String): Boolean = key in registeredKeys()
}

/**
 * Provided by P2 ONLY (P2 owns incident data). Everyone else injects this interface and must
 * not re-implement incident visibility rules.
 *
 * Contract: throw [ResourceNotFound] if the incident does not exist; otherwise return whether
 * [user] may view it (reporter, assigned technician, or admin).
 */
interface IncidentAccessPolicy {
    fun canView(user: CurrentUser, incidentId: String): Boolean

    fun requireView(user: CurrentUser, incidentId: String) {
        if (!canView(user, incidentId)) throw UnauthorizedOperation("You are not allowed to access this incident")
    }
}
