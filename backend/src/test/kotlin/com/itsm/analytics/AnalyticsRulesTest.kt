package com.itsm.analytics

import com.itsm.common.error.InvalidInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class AnalyticsRulesTest {

    @Test fun `compliance is the share of decided clocks that were met, to one decimal`() {
        assertEquals(33.3, SlaCounts.pct(1, 2))
        assertEquals(66.7, SlaCounts.pct(2, 1))
        assertEquals(100.0, SlaCounts.pct(4, 0))
        assertEquals(0.0, SlaCounts.pct(0, 3))
        assertNull(SlaCounts.pct(0, 0), "nothing decided yet is not 0 %")
        val c = SlaCounts(tracked = 5, responseMet = 3, responseBreached = 1, resolutionMet = 1, resolutionBreached = 1)
        assertEquals(75.0, c.responseCompliancePct); assertEquals(50.0, c.resolutionCompliancePct)
    }

    @Test fun `a range is at most 366 days and never backwards`() {
        val d = LocalDate.parse("2026-01-01")
        assertDoesNotThrow { AnalyticsService.validate(d, d) }
        assertDoesNotThrow { AnalyticsService.validate(d, d.plusDays(365)) }
        assertEquals("to", assertThrows<InvalidInput> { AnalyticsService.validate(d, d.plusDays(366)) }.fieldErrors.single().field)
        assertEquals("from", assertThrows<InvalidInput> { AnalyticsService.validate(d.plusDays(1), d) }.fieldErrors.single().field)
    }
}
