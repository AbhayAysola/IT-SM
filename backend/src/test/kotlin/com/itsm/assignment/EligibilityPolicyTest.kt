package com.itsm.assignment

import com.itsm.common.error.TechnicianUnavailable
import com.itsm.common.error.WorkloadExceeded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EligibilityPolicyTest {
    @Test fun `own limit wins, non-positive limit means use the default`() {
        assertEquals(3, EligibilityPolicy.effectiveMax(cand(1, max = 3), 10))
        assertEquals(10, EligibilityPolicy.effectiveMax(cand(1, max = 0), 10))
        assertEquals(10, EligibilityPolicy.effectiveMax(cand(1, max = -2), 10))
    }

    @Test fun `capacity is strictly below the limit`() {
        assertTrue(EligibilityPolicy.hasCapacity(cand(1, active = 4, max = 5), 5))
        assertFalse(EligibilityPolicy.hasCapacity(cand(1, active = 5, max = 5), 5))
        assertFalse(EligibilityPolicy.hasCapacity(cand(1, active = 6, max = 5), 5))
    }

    @Test fun `no own limit and a non-positive default means no capacity`() {
        assertFalse(EligibilityPolicy.hasCapacity(cand(1, active = 0, max = 0), 0))
    }

    @Test fun `unavailable, full and excluded technicians are filtered, order is kept`() {
        val c = listOf(
            cand(1), cand(2, available = false), cand(3, active = 5, max = 5), cand(4), cand(5)
        )
        val result = EligibilityPolicy.eligible(c, req(exclude = setOf(4L)), 5)
        assertEquals(listOf(1L, 5L), result.ids())
    }

    @Test fun `eligibility does not require a skill`() {
        assertEquals(listOf(1L), EligibilityPolicy.eligible(listOf(cand(1)), req(), 5).ids())
    }

    @Test fun `manual assignment of an unavailable technician is a 409`() {
        assertThrows<TechnicianUnavailable> { EligibilityPolicy.requireCanTake(cand(1, available = false), 5) }
    }

    @Test fun `manual assignment past the limit is a 409 naming the numbers`() {
        val e = assertThrows<WorkloadExceeded> { EligibilityPolicy.requireCanTake(cand(1, active = 5, max = 5), 5) }
        assertEquals("Technician 1 is at 5 of 5 allowed active incidents", e.message)
    }

    @Test fun `manual assignment outside the technician's skills is allowed`() {
        EligibilityPolicy.requireCanTake(cand(1, skills = mapOf(99L to 1)), 5)
    }

    @Test fun `a negative active count is rejected at construction`() {
        assertThrows<IllegalArgumentException> { cand(1, active = -1) }
    }
}
