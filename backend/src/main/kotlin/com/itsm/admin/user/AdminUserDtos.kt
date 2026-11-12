package com.itsm.admin.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/*
 * API shapes for /api/admin/users. Roles are the canonical names EMPLOYEE | TECHNICIAN | ADMIN. Responses never contain
 * the password hash or the token version. `version` is the optimistic-lock value last read.
 */

data class CreateUserRequest(
    @field:NotBlank @field:Email @field:Size(max = 254)
    val email: String? = null,
    @field:NotBlank @field:Size(max = 100)
    val name: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 128)
    val password: String? = null,
    @field:NotBlank
    val role: String? = null
)

data class UpdateUserRequest(
    @field:NotBlank @field:Email @field:Size(max = 254)
    val email: String? = null,
    @field:NotBlank @field:Size(max = 100)
    val name: String? = null,
    @field:NotNull
    val version: Long? = null
)

data class ChangeRoleRequest(
    @field:NotBlank
    val role: String? = null,
    @field:NotNull
    val version: Long? = null
)

data class VersionRequest(
    @field:NotNull
    val version: Long? = null
)

data class ResetPasswordRequest(
    @field:NotBlank @field:Size(min = 8, max = 128)
    val password: String? = null
)

data class AdminUserResponse(
    val id: Long,
    val email: String,
    val name: String,
    val role: String,
    val active: Boolean,
    val version: Long,
    val createdAt: String,
    val updatedAt: Instant
)
