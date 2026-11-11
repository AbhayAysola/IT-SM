package com.itsm.common.api

import com.itsm.common.error.InvalidInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PageQueryTest {
    @Test fun `valid paging is accepted, including the defaults and both limits`() {
        assertEquals(20, PageQuery().size)
        PageQuery(0, 1); PageQuery(5, PageQuery.MAX_SIZE)
    }

    @Test fun `out of range paging is rejected with the offending fields`() {
        assertEquals(listOf("size"), assertThrows<InvalidInput> { PageQuery(0, 0) }.fieldErrors.map { it.field })
        assertEquals(listOf("size"), assertThrows<InvalidInput> { PageQuery(0, PageQuery.MAX_SIZE + 1) }.fieldErrors.map { it.field })
        assertEquals(listOf("page", "size"), assertThrows<InvalidInput> { PageQuery(-1, 0) }.fieldErrors.map { it.field })
    }
}
