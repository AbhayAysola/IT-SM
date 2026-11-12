package com.itsm.admin.technician

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput

/** Input rules. The database CHECKs repeat the numeric bounds as the last line of defence. */
object TechnicianRules {
    const val MIN_WORKLOAD = 1
    const val MAX_WORKLOAD = 100
    const val MAX_EXPERIENCE_YEARS = 60
    const val MAX_SKILLS = 50
    const val MIN_PROFICIENCY = 1
    const val MAX_PROFICIENCY = 5

    fun details(maxWorkload: Int, experienceYears: Int) {
        val errors = buildList {
            if (maxWorkload !in MIN_WORKLOAD..MAX_WORKLOAD) add(FieldError("maxWorkload", "must be between $MIN_WORKLOAD and $MAX_WORKLOAD"))
            if (experienceYears !in 0..MAX_EXPERIENCE_YEARS) add(FieldError("experienceYears", "must be between 0 and $MAX_EXPERIENCE_YEARS"))
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid technician details", errors)
    }

    /** categoryId -> proficiency. Duplicates are rejected, never merged. */
    fun skills(input: List<SkillInput>?): Map<Long, Int> {
        val items = input.orEmpty()
        val errors = mutableListOf<FieldError>()
        if (items.size > MAX_SKILLS) errors += FieldError("skills", "at most $MAX_SKILLS skills")
        val result = LinkedHashMap<Long, Int>()
        items.forEachIndexed { i, s ->
            val category = s.categoryId
            val level = s.proficiency
            if (category == null || category <= 0) errors += FieldError("skills[$i].categoryId", "must be a positive category id")
            if (level == null || level !in MIN_PROFICIENCY..MAX_PROFICIENCY) {
                errors += FieldError("skills[$i].proficiency", "must be between $MIN_PROFICIENCY and $MAX_PROFICIENCY")
            }
            if (category != null && level != null && category > 0 && level in MIN_PROFICIENCY..MAX_PROFICIENCY) {
                if (result.put(category, level) != null) errors += FieldError("skills[$i].categoryId", "category $category is listed more than once")
            }
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid skills", errors)
        return result
    }
}
