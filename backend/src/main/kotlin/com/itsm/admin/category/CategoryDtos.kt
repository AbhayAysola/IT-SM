package com.itsm.admin.category

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/*
 * API shapes for /api/admin/categories. Fields are nullable so that a missing field is reported as a
 * VALIDATION_FAILED field error instead of an unreadable-body error. Business rules that depend on normalisation
 * (length after trimming, keyword format, duplicates) are checked in CategoryAdminService.
 */

data class CreateCategoryRequest(
    /** Stable machine identifier, UPPER_SNAKE_CASE (e.g. `NETWORK`). Cannot be changed later. */
    @field:NotBlank @field:Size(max = 40)
    val code: String? = null,
    @field:NotBlank @field:Size(max = 60)
    val name: String? = null,
    @field:Size(max = 300)
    val description: String? = null,
    @field:NotNull @field:Size(min = 1, max = CategoryRules.MAX_KEYWORDS)
    val keywords: List<String?>? = null
)

/** Full replacement of the editable fields. `version` must be the one last read (optimistic locking). */
data class UpdateCategoryRequest(
    @field:NotBlank @field:Size(max = 60)
    val name: String? = null,
    @field:Size(max = 300)
    val description: String? = null,
    @field:NotNull @field:Size(min = 1, max = CategoryRules.MAX_KEYWORDS)
    val keywords: List<String?>? = null,
    @field:NotNull
    val version: Long? = null
)

data class CategoryResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    /** Normalised (lower-case, single-spaced), sorted alphabetically. */
    val keywords: List<String>,
    val active: Boolean,
    /** Send back unchanged in the next update. */
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

fun CategoryEntity.toResponse() = CategoryResponse(
    id = requireId(),
    code = code,
    name = name,
    description = description,
    keywords = keywords.sorted(),
    active = active,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt
)
