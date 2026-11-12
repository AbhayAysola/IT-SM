package com.itsm.common.api

import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Regression guard: PageQuery's validation once read `size` inside `buildList`, where it resolves to the builder
 * list's own size (0), so every PageQuery, including the default one, was rejected.
 */
class PageQueryTest {

    @Test fun `valid paging is accepted, including the boundaries`() {
        assertEquals(PageQuery.DEFAULT_SIZE, PageQuery().size)
        assertEquals(0, PageQuery(0, 1).page)
        assertEquals(PageQuery.MAX_SIZE, PageQuery(3, PageQuery.MAX_SIZE).size)
        assertEquals(60, PageQuery(3, 20).toPageable().offset)
    }

    @Test fun `invalid paging is rejected with the offending fields`() {
        assertEquals(listOf("size"), assertThrows<InvalidInput> { PageQuery(0, 0) }.fieldErrors.map { it.field })
        assertEquals(listOf("size"), assertThrows<InvalidInput> { PageQuery(0, PageQuery.MAX_SIZE + 1) }.fieldErrors.map { it.field })
        assertEquals(listOf("page", "size"), assertThrows<InvalidInput> { PageQuery(-1, 0) }.fieldErrors.map { it.field })
    }

    // --- B10-PAGE-001: the offset (page * size) must stay within what the persistence layer accepts ----------------------

    private fun outOfRange(page: Int, size: Int) =
        assertThrows<InvalidInput> { PageQuery(page, size) }.fieldErrors

    @Test fun `the last reachable offset is accepted and the first unreachable one is rejected, at size 20`() {
        val lastPage = (Int.MAX_VALUE.toLong() / 20).toInt()           // 107374182 -> offset 2147483640
        assertEquals(2_147_483_640L, PageQuery(lastPage, 20).toPageable().offset)
        assertEquals(listOf(FieldError("page", "is out of range")), outOfRange(lastPage + 1, 20))   // offset 2147483660
    }

    @Test fun `the last reachable offset is accepted and the first unreachable one is rejected, at size 100`() {
        val lastPage = (Int.MAX_VALUE.toLong() / 100).toInt()          // 21474836 -> offset 2147483600
        assertEquals(2_147_483_600L, PageQuery(lastPage, 100).toPageable().offset)
        assertEquals(listOf(FieldError("page", "is out of range")), outOfRange(lastPage + 1, 100)) // 2147483700, the audited 500
    }

    @Test fun `an offset of exactly Int MAX_VALUE is accepted, one more is not`() {
        assertEquals(Int.MAX_VALUE.toLong(), PageQuery(Int.MAX_VALUE, 1).toPageable().offset)
        assertEquals(listOf("page"), outOfRange(Int.MAX_VALUE, 2).map { it.field })
    }

    @Test fun `the product is computed in Long, so Int overflow cannot let a huge page through`() {
        // 2147483647 * 100 wraps to -100 in Int arithmetic; 65536 * 65536 wraps to 0. Both must still be rejected.
        assertEquals(listOf("page"), outOfRange(Int.MAX_VALUE, PageQuery.MAX_SIZE).map { it.field })
        assertEquals(listOf("page"), outOfRange(Int.MAX_VALUE, 20).map { it.field })
        assertEquals(listOf("page"), outOfRange(Int.MAX_VALUE / 2 + 1, 3).map { it.field })
    }

    @Test fun `a negative page or an invalid size reports only its own error, never also out of range`() {
        assertEquals(listOf("page"), outOfRange(-1, 20).map { it.field })
        assertEquals(listOf("page"), outOfRange(Int.MIN_VALUE, 20).map { it.field })
        assertEquals(listOf("size"), outOfRange(Int.MAX_VALUE, 0).map { it.field })
        assertEquals(listOf("size"), outOfRange(Int.MAX_VALUE, PageQuery.MAX_SIZE + 1).map { it.field })
        assertEquals(listOf("page", "size"), outOfRange(-1, 0).map { it.field })
    }

    @Test fun `normal pages are unaffected`() {
        assertEquals(0L, PageQuery(0, 20).toPageable().offset)
        assertEquals(2000L, PageQuery(20, 100).toPageable().offset)
        assertEquals(100_000L * 20, PageQuery(100_000, 20).toPageable().offset)
    }
}
