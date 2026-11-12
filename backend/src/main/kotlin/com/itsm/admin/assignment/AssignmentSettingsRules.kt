package com.itsm.admin.assignment

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput

/** Validation rules for assignment settings. They mirror the CHECK constraints of V14. */
object AssignmentSettingsRules {
    const val MIN_WORKLOAD = 1
    const val MAX_WORKLOAD = 100
    const val MAX_OVERRIDES = 100

    private val STRATEGY_KEY = Regex("^[A-Z][A-Z0-9_]{1,49}$")

    fun strategyKey(raw: String, field: String): String {
        val key = raw.trim()
        if (!STRATEGY_KEY.matches(key)) throw InvalidInput(
            "Invalid strategy key",
            listOf(FieldError(field, "must be UPPER_SNAKE_CASE: a letter, then letters, digits or _ (2-50 characters)"))
        )
        return key
    }

    fun workload(value: Int) {
        if (value !in MIN_WORKLOAD..MAX_WORKLOAD) throw InvalidInput(
            "Invalid default workload",
            listOf(FieldError("defaultMaxWorkload", "must be between $MIN_WORKLOAD and $MAX_WORKLOAD"))
        )
    }

    /** categoryId -> strategy key. Rejects null entries, duplicate categories, bad keys and oversized lists. */
    fun overrides(raw: List<OverrideRequest?>?): Map<Long, String> {
        val items = raw.orEmpty()
        if (items.size > MAX_OVERRIDES) throw InvalidInput(
            "Too many overrides", listOf(FieldError("categoryOverrides", "at most $MAX_OVERRIDES overrides"))
        )
        val result = LinkedHashMap<Long, String>()
        val duplicates = sortedSetOf<Long>()
        items.forEachIndexed { i, item ->
            val categoryId = item?.categoryId
            val strategy = item?.strategy
            if (categoryId == null || strategy == null) throw InvalidInput(
                "Incomplete override",
                listOf(FieldError("categoryOverrides[$i]", "categoryId and strategy are required"))
            )
            val key = strategyKey(strategy, "categoryOverrides[$i].strategy")
            if (result.put(categoryId, key) != null) duplicates += categoryId
        }
        if (duplicates.isNotEmpty()) throw InvalidInput(
            "Duplicate category overrides",
            listOf(FieldError("categoryOverrides", "more than one override for categories $duplicates"))
        )
        return result
    }
}
