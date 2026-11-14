package com.itsm.sla

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SlaRiskModelTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private fun inMinutes(m: Long) = now.plus(Duration.ofMinutes(m))

    private fun subject(
        elapsed: Long = 0, dueIn: Long = 120, category: List<Long> = emptyList(), priority: List<Long> = emptyList(), load: Double? = null
    ) = RiskSubject(elapsed, inMinutes(dueIn), category, priority, load)

    @Test fun `without enough similar incidents there is no forecast`() {
        assertNull(SlaRiskModel.forecast(subject(category = listOf(60, 70, 80, 90), priority = listOf(60, 70, 80, 90)), now))
    }

    @Test fun `the same category is preferred, and the priority alone is the fallback`() {
        val byCategory = SlaRiskModel.forecast(subject(category = listOf(10, 20, 30, 40, 50), priority = listOf(100, 200, 300, 400, 500, 600)), now)!!
        assertEquals(RiskBasis.CATEGORY, byCategory.basis)
        assertEquals(5, byCategory.sampleSize)
        assertEquals(30, byCategory.typicalRemainingMinutes)

        val byPriority = SlaRiskModel.forecast(subject(category = listOf(10, 20), priority = listOf(100, 200, 300, 400, 500, 600)), now)!!
        assertEquals(RiskBasis.PRIORITY, byPriority.basis)
        assertEquals(300, byPriority.typicalRemainingMinutes, "lower middle of an even count: a real past duration")
    }

    @Test fun `the remaining time comes from the incidents that took longer than this one has run`() {
        // 40 minutes in: of those that took longer (60, 90, 150, 200), the median remainder is 90 - 40 = 50.
        val f = SlaRiskModel.forecast(subject(elapsed = 40, category = listOf(20, 30, 60, 90, 150, 200)), now)!!
        assertEquals(50, f.typicalRemainingMinutes)
        assertEquals(inMinutes(50), f.predictedFinishAt)
    }

    @Test fun `an incident that has outrun nearly all similar ones is assumed to need a typical incident's time again`() {
        // 160 minutes in: only 200 took longer (fewer than three), so the median of all (60) is used.
        val f = SlaRiskModel.forecast(subject(elapsed = 160, category = listOf(20, 30, 60, 90, 150, 200)), now)!!
        assertEquals(60, f.typicalRemainingMinutes)
    }

    @Test fun `an overloaded assignee slows the forecast, within limits, and spare capacity does not speed it up`() {
        val history = listOf(30L, 40, 50, 60, 70)
        assertEquals(inMinutes(75), SlaRiskModel.forecast(subject(category = history, load = 1.5), now)!!.predictedFinishAt)
        assertEquals(3.0, SlaRiskModel.forecast(subject(category = history, load = 10.0), now)!!.loadFactor)
        assertEquals(1.0, SlaRiskModel.forecast(subject(category = history, load = 0.2), now)!!.loadFactor)
        assertEquals(1.0, SlaRiskModel.forecast(subject(category = history, load = null), now)!!.loadFactor, "nobody assigned")
    }

    @Test fun `late means predicted after the deadline`() {
        val f = SlaRiskModel.forecast(subject(category = listOf(30, 40, 50, 60, 70)), now)!! // finishes in 50 minutes
        assertTrue(f.lateFor(inMinutes(49)))
        assertFalse(f.lateFor(inMinutes(50)), "exactly on the deadline is in time")
    }
}
