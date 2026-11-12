package com.itsm.admin.itservice

import com.itsm.admin.support.CatalogText
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput

/** Validation rules for IT services, aligned with the frontend's (features/admin/services/service-service.ts). */
object ItServiceRules {
    const val MIN_NAME = 3
    const val MAX_NAME = 60
    const val MAX_DEPENDENCIES = 50

    fun name(raw: String): String {
        val name = CatalogText.normalizeName(raw)
        if (name.length !in MIN_NAME..MAX_NAME) throw InvalidInput(
            "Invalid service name", listOf(FieldError("name", "must be $MIN_NAME-$MAX_NAME characters"))
        )
        return name
    }

    fun optional(raw: String?): String? = CatalogText.normalizeOptional(raw)

    /** Rejects null entries, duplicates and (when [selfId] is known) a dependency on the service itself. */
    fun dependencyIds(raw: List<Long?>?, selfId: Long?): Set<Long> {
        val ids = raw.orEmpty()
        if (ids.any { it == null }) throw InvalidInput(
            "Invalid dependencies", listOf(FieldError("dependsOnServiceIds", "must not contain null"))
        )
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw InvalidInput(
            "Duplicate dependencies", listOf(FieldError("dependsOnServiceIds", "listed more than once: ${duplicates.sortedBy { it }}"))
        )
        if (selfId != null && selfId in ids) throw InvalidInput(
            "A service cannot depend on itself", listOf(FieldError("dependsOnServiceIds", "must not contain the service itself"))
        )
        return ids.filterNotNull().toCollection(LinkedHashSet())
    }
}
