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
     * Strategy keys currently registered by P3's AssignmentStrategyCatalog, sorted. Non-null exactly when
     * [strategyCatalogStatus] is AVAILABLE (and then possibly empty: a catalog that registers nothing). null when the
     * catalog is ABSENT or FAILED, which must never be read as "no strategies exist". Informational: writes are
     * validated against the catalog, not this list.
     */
    val availableStrategies: List<String>?,
    /**
     * AVAILABLE: the catalog answered. ABSENT: no catalog bean exists (P3 not deployed); writes answer 503.
     * FAILED: a catalog exists but threw while being asked; writes fail with a generic 500/503. Neither ABSENT nor FAILED
     * allows a write. Never carries an exception message.
     */
    val strategyCatalogStatus: StrategyCatalogStatus,
    val version: Long,
    val updatedAt: Instant,
    /** User id of the last editor; null for the seeded default. */
    val updatedByUserId: Long?
)
