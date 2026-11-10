package com.itsm.admin.sla

import com.itsm.admin.itservice.ServiceRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

/*
 * API shapes for /api/admin/sla-policies. Priority is the shared code "P1".."P4". `serviceId` null/omitted creates the
 * global policy for that priority.
 */

data class CreateSlaPolicyRequest(
    /** Omit or null for the global default of [priority]. */
    val serviceId: Long? = null,
    @field:NotBlank
    val priority: String? = null,
    @field:NotNull
    val responseMinutes: Int? = null,
    @field:NotNull
    val resolutionMinutes: Int? = null,
    /** Percentage of the target time after which P4 warns. Default 80. */
    val warningThresholdPct: Int? = null
)

/** Service and priority identify a policy and cannot change. `version` = the one last read. */
data class UpdateSlaPolicyRequest(
    @field:NotNull
    val responseMinutes: Int? = null,
    @field:NotNull
    val resolutionMinutes: Int? = null,
    /** Omit or null to keep the current value. */
    val warningThresholdPct: Int? = null,
    @field:NotNull
    val version: Long? = null
)

data class SlaPolicyResponse(
    val id: Long,
    /** null for a global policy. */
    val service: ServiceRef?,
    val priority: String,
    val responseMinutes: Int,
    val resolutionMinutes: Int,
    val warningThresholdPct: Int,
    val active: Boolean,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** What P4 would receive for a service and priority, and which scope supplied it. */
data class EffectiveSlaResponse(
    val serviceId: Long,
    val priority: String,
    /** SERVICE if a service-specific policy applies, GLOBAL if the default does. */
    val scope: String,
    val policyId: Long,
    val responseMinutes: Int,
    val resolutionMinutes: Int,
    val warningThresholdPct: Int
)
