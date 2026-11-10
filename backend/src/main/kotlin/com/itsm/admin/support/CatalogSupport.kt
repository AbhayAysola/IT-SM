package com.itsm.admin.support

import com.itsm.common.error.ErrorCode
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import jakarta.persistence.EntityManager
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Text normalisation shared by the admin catalog modules. */
object CatalogText {
    private val WHITESPACE = Regex("\\s+")

    /** Trims and collapses internal whitespace: "  Campus   Wi-Fi " -> "Campus Wi-Fi". */
    fun normalizeName(value: String): String = value.trim().replace(WHITESPACE, " ")

    /** Null for a blank optional text, otherwise the trimmed value. */
    fun normalizeOptional(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Lower-case `LIKE` pattern matching [search] anywhere, with `%`, `_` and `\` escaped. Null when blank. */
    fun containsPattern(search: String?): String? {
        val term = search?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val escaped = term.lowercase()
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}

/** Builds a validated [Sort] from `sort`/`dir` query parameters; unknown values are a 400, never silently ignored. */
object SortParams {
    fun of(sort: String?, dir: String?, allowed: Map<String, String>, default: String): Sort {
        val key = sort?.trim()?.takeIf { it.isNotEmpty() } ?: default
        val property = allowed[key]
            ?: throw InvalidInput("Unsupported sort", listOf(FieldError("sort", "must be one of ${allowed.keys.sorted()}")))
        val direction = when (dir?.trim()?.lowercase()) {
            null, "", "asc" -> Sort.Direction.ASC
            "desc" -> Sort.Direction.DESC
            else -> throw InvalidInput("Unsupported sort direction", listOf(FieldError("dir", "must be asc or desc")))
        }
        // Tie-break on id so paging is stable when the sort key repeats.
        return Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id"))
    }
}

/** 409 for a client that edited a stale copy (the request's `version` is not the stored one). */
fun staleVersion(resource: String): ItsmException = ItsmException(
    ErrorCode.CONCURRENT_MODIFICATION,
    "This $resource was changed by someone else. Reload it and apply your change again."
)

/** 409 for a request that conflicts with the current catalog state. */
fun catalogConflict(message: String): ItsmException = ItsmException(ErrorCode.CONFLICT, message)

/**
 * Transaction-scoped PostgreSQL advisory locks. Used where a rule spans several rows and no single constraint can
 * enforce it (e.g. "at least one active category", "no active service depends on an inactive one"): every writer
 * that could break the rule takes the same lock first, so the check and the write cannot interleave.
 * Released automatically at commit or rollback.
 */
@Component
class AdvisoryLocks(private val entityManager: EntityManager) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun lock(key: Long) {
        // pg_advisory_xact_lock returns void, which Hibernate cannot map; wrap it so the query returns a count.
        entityManager.createNativeQuery("select count(*) from (select pg_advisory_xact_lock(?1)) as l")
            .setParameter(1, key)
            .singleResult
    }

    companion object {
        /** Category activation state ("at least one active category"). */
        const val CATEGORY_STATE = 7310001L

        /** Service dependency graph and activation state. MUST match the key in V12's cycle trigger. */
        const val SERVICE_GRAPH = 7310002L
    }
}
