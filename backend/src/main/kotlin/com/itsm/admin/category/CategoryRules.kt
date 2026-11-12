package com.itsm.admin.category

import com.itsm.admin.support.CatalogText
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput

/**
 * Validation and normalisation rules for categories. Kept in one place so the API, the tests and the
 * documentation agree. They match the frontend's rules (features/admin/services/category-service.ts) where those
 * exist, except that `description` is optional because the CategoryCatalog contract allows it to be null.
 */
object CategoryRules {
    const val MIN_NAME = 3
    const val MAX_NAME = 60
    const val MAX_DESCRIPTION = 300
    const val MAX_KEYWORDS = 15
    const val MIN_KEYWORD = 2
    const val MAX_KEYWORD = 30

    private val CODE = Regex("^[A-Z][A-Z0-9_]{0,39}$")
    private val KEYWORD = Regex("^[a-z0-9][a-z0-9 '&./+-]*$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The keyword normalisation rule: trim, lower-case (locale-independent), collapse runs of whitespace to one
     * space. Two keywords are "the same" exactly when their normalised forms are equal. The database CHECK rejects
     * anything not already in this form.
     */
    fun normalizeKeyword(raw: String): String = raw.trim().lowercase().replace(WHITESPACE, " ")

    fun code(raw: String): String {
        val code = raw.trim()
        if (!CODE.matches(code)) throw InvalidInput(
            "Invalid category code",
            listOf(FieldError("code", "must be UPPER_SNAKE_CASE: a letter, then letters, digits or _ (max 40)"))
        )
        return code
    }

    fun name(raw: String): String {
        val name = CatalogText.normalizeName(raw)
        if (name.length !in MIN_NAME..MAX_NAME) throw InvalidInput(
            "Invalid category name", listOf(FieldError("name", "must be $MIN_NAME-$MAX_NAME characters"))
        )
        return name
    }

    fun description(raw: String?): String? = CatalogText.normalizeOptional(raw)

    /** Normalises every keyword; rejects blanks, bad characters, bad lengths and duplicates after normalisation. */
    fun keywords(raw: List<String?>): Set<String> {
        val errors = mutableListOf<FieldError>()
        val normalized = raw.mapIndexed { i, value ->
            val k = normalizeKeyword(value ?: "")
            when {
                k.length !in MIN_KEYWORD..MAX_KEYWORD ->
                    errors += FieldError("keywords[$i]", "must be $MIN_KEYWORD-$MAX_KEYWORD characters")
                !KEYWORD.matches(k) ->
                    errors += FieldError("keywords[$i]", "may only contain letters, digits, spaces and ' & . / + -")
            }
            k
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid keywords", errors)
        val duplicates = normalized.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw InvalidInput(
            "Duplicate keywords", listOf(FieldError("keywords", "duplicates after normalisation: ${duplicates.sorted()}"))
        )
        return normalized.toCollection(LinkedHashSet())
    }
}
