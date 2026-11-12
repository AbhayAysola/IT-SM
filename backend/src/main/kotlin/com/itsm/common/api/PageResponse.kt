package com.itsm.common.api

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/** Standard paged envelope for every list endpoint. */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int
) {
    companion object {
        fun <S, T> from(page: Page<S>, mapper: (S) -> T) = PageResponse(
            items = page.content.map(mapper),
            page = page.number,
            size = page.size,
            totalItems = page.totalElements,
            totalPages = page.totalPages
        )
    }
}

/** Validated paging input. Bind `page` and `size` query params to this. */
data class PageQuery(val page: Int = 0, val size: Int = DEFAULT_SIZE) {
    init {
        // Qualified on purpose: inside buildList a bare `size` is the builder list's own size (always 0 here).
        val errors = buildList {
            if (this@PageQuery.page < 0) add(FieldError("page", "must be >= 0"))
            if (this@PageQuery.size !in 1..MAX_SIZE) add(FieldError("size", "must be between 1 and $MAX_SIZE"))
            // Spring Data JPA converts the offset to an Int and throws InvalidDataAccessApiUsageException (a 500) past
            // Int.MAX_VALUE. Widen BEFORE multiplying: page * size overflows Int for page >= 21474837 at size 100.
            // Only checked once both inputs are individually valid, so a bad page or size reports just its own error.
            if (isEmpty() && !isReachable(this@PageQuery.page, this@PageQuery.size)) add(FieldError("page", "is out of range"))
        }
        if (errors.isNotEmpty()) throw InvalidInput("Invalid paging parameters", errors)
    }

    fun toPageable(sort: Sort = Sort.unsorted()): Pageable = PageRequest.of(page, size, sort)

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        /** Largest row offset the persistence layer accepts (it narrows the offset to an Int). */
        const val MAX_OFFSET = Int.MAX_VALUE.toLong()

        private fun isReachable(page: Int, size: Int): Boolean = page.toLong() * size.toLong() <= MAX_OFFSET
    }
}
