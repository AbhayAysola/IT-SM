package com.itsm.common.spi

import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import java.time.Duration

/*
 * Read-only views that cross module boundaries. Plain immutable data: NEVER expose JPA
 * entities through an SPI. Collections inside these views must be treated as read-only.
 */

data class UserSummary(
    val id: Long,
    val email: String,
    val name: String,
    val role: Role,
    val active: Boolean
)

data class TechnicianSummary(
    val userId: Long,
    val name: String,
    val email: String,
    val available: Boolean,
    val maxWorkload: Int,
    val experienceYears: Int,
    val isTeamLead: Boolean,
    val reportsToUserId: Long?,
    /** categoryId -> proficiency 1..5 */
    val skills: Map<Long, Int>
)

data class CategoryView(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    /** Lower-case keywords used for automatic categorisation. */
    val keywords: List<String>,
    val active: Boolean
)

data class ServiceView(
    val id: Long,
    val name: String,
    val description: String?,
    /** 1 (low) .. 5 (mission critical). */
    val businessCriticality: Int,
    val ownerTeam: String?,
    val active: Boolean,
    /** Ids of services this service depends on. */
    val dependsOnServiceIds: Set<Long>
)

data class SlaPolicyView(
    val id: Long,
    /** null = the global default policy. */
    val serviceId: Long?,
    val priority: Priority,
    val responseMinutes: Int,
    val resolutionMinutes: Int,
    /** Warn when this percentage of the target time has elapsed. */
    val warningThresholdPct: Int
) {
    val responseTarget: Duration get() = Duration.ofMinutes(responseMinutes.toLong())
    val resolutionTarget: Duration get() = Duration.ofMinutes(resolutionMinutes.toLong())
}

data class AssignmentSettingsView(
    /** Key of the active strategy; must be one of AssignmentStrategyCatalog.registeredKeys(). */
    val activeStrategy: String,
    val defaultMaxWorkload: Int,
    /** categoryId -> strategy key overriding [activeStrategy] for that category. */
    val categoryOverrides: Map<Long, String>
) {
    fun strategyFor(categoryId: Long?): String = categoryOverrides[categoryId] ?: activeStrategy
}
