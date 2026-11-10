package com.itsm.admin.itservice

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/*
 * API shapes for /api/admin/services. Dependencies are a JSON array (not a set) so that a duplicate id is reported
 * as an error instead of being silently collapsed.
 */

data class CreateServiceRequest(
    @field:NotBlank @field:Size(max = 60)
    val name: String? = null,
    @field:Size(max = 300)
    val description: String? = null,
    /** 1 (low) .. 5 (mission critical). */
    @field:NotNull @field:Min(1) @field:Max(5)
    val businessCriticality: Int? = null,
    @field:Size(max = 100)
    val ownerTeam: String? = null,
    /** Ids of services this one directly depends on. Omitted = none. */
    @field:Size(max = ItServiceRules.MAX_DEPENDENCIES)
    val dependsOnServiceIds: List<Long?>? = null
)

/** Full replacement of the editable fields, including the dependency list. `version` = the one last read. */
data class UpdateServiceRequest(
    @field:NotBlank @field:Size(max = 60)
    val name: String? = null,
    @field:Size(max = 300)
    val description: String? = null,
    @field:NotNull @field:Min(1) @field:Max(5)
    val businessCriticality: Int? = null,
    @field:Size(max = 100)
    val ownerTeam: String? = null,
    @field:NotNull @field:Size(max = ItServiceRules.MAX_DEPENDENCIES)
    val dependsOnServiceIds: List<Long?>? = null,
    @field:NotNull
    val version: Long? = null
)

/** A reference to another service, labelled so a client needs no second request to display it. */
data class ServiceRef(val id: Long, val name: String, val active: Boolean)

data class ServiceResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val businessCriticality: Int,
    val ownerTeam: String?,
    val active: Boolean,
    /** Services this one directly depends on, sorted by name. */
    val dependsOn: List<ServiceRef>,
    /** Services that directly depend on this one (active and inactive), sorted by name. */
    val dependents: List<ServiceRef>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)
