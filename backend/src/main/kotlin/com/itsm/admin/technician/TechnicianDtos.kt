package com.itsm.admin.technician

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.time.Instant

/*
 * API shapes for /api/admin/technicians. The path id is the USER id (a profile is 1:1 with a user). `version` is the
 * optimistic-lock value last read. Skills are incident categories by id with a 1..5 proficiency.
 * `available` is an administrator-set flag (leave, off shift); it is NOT workload and does NOT mean "assignable".
 */

data class SkillInput(
    @field:NotNull val categoryId: Long? = null,
    @field:NotNull val proficiency: Int? = null
)

data class CreateTechnicianRequest(
    @field:NotNull val userId: Long? = null,
    @field:NotNull val maxWorkload: Int? = null,
    @field:NotNull val experienceYears: Int? = null,
    @field:NotNull val available: Boolean? = null,
    @param:JsonProperty("isTeamLead") val isTeamLead: Boolean? = false,
    val reportsToUserId: Long? = null,
    @field:Valid val skills: List<SkillInput>? = null
)

/** Replaces the detail fields. `reportsToUserId` null clears the reporting line. Skills and active state have their own calls. */
data class UpdateTechnicianRequest(
    @field:NotNull val maxWorkload: Int? = null,
    @field:NotNull val experienceYears: Int? = null,
    @field:NotNull val available: Boolean? = null,
    @param:JsonProperty("isTeamLead") @field:NotNull val isTeamLead: Boolean? = null,
    val reportsToUserId: Long? = null,
    @field:NotNull val version: Long? = null
)

/** Replaces the whole skill set. An empty list removes every skill. */
data class UpdateSkillsRequest(
    @field:NotNull @field:Valid val skills: List<SkillInput>? = null,
    @field:NotNull val version: Long? = null
)

data class TechnicianVersionRequest(@field:NotNull val version: Long? = null)

data class SkillResponse(
    val categoryId: Long,
    val categoryCode: String,
    val categoryName: String,
    val categoryActive: Boolean,
    val proficiency: Int
)

data class TechnicianResponse(
    val userId: Long,
    val name: String,
    val email: String,
    /** The canonical user's current role and account state (read-only here: user administration owns them). */
    val userRole: String,
    val userActive: Boolean,
    val active: Boolean,
    val available: Boolean,
    val maxWorkload: Int,
    val experienceYears: Int,
    @get:JsonProperty("isTeamLead") val isTeamLead: Boolean,
    val reportsToUserId: Long?,
    val skills: List<SkillResponse>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)
