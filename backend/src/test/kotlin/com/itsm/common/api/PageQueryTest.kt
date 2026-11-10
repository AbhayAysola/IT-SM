package com.itsm.common.api

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
}
