package com.itsm.admin.assignment

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

/*
 * API shapes for /api/admin/assignment-settings. They carry exactly what the shared AssignmentSettingsView carries:
 * the active strategy key, the default max workload and per-category strategy overrides. Nothing else is stored.
 */

data class OverrideRequest(
    val categoryId: Long? = null,
    val strategy: String? = null
)

/** Replaces the whole configuration. `version` must be the one last read (optimistic locking). */
data class UpdateAssignmentSettingsRequest(
    @field:NotBlank
    val activeStrategy: String? = null,
    @field:NotNull
    val defaultMaxWorkload: Int? = null,
    /** The complete set of overrides: categories not listed have no override. Omit or send [] for none. */
    val categoryOverrides: List<OverrideRequest?>? = null,
    @field:NotNull
    val version: Long? = null
)

data class OverrideResponse(
    val categoryId: Long,
    val categoryName: String,
    val categoryActive: Boolean,
    val strategy: String
)

data class AssignmentSettingsResponse(
    val activeStrategy: String,
    val defaultMaxWorkload: Int,
    /** Sorted by category name. */
    val categoryOverrides: List<OverrideResponse>,
    /**
     * Strategy keys currently registered by P3's AssignmentStrategyCatalog, sorted; null when that catalog is not
     * available (P3 has not merged yet). Informational: writes are validated against the catalog, not this list.
     */
    val availableStrategies: List<String>?,
    val version: Long,
    val updatedAt: Instant,
    /** User id of the last editor; null for the seeded default. */
    val updatedByUserId: Long?
)
