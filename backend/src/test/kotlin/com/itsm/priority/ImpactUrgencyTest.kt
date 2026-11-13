package com.itsm.priority

import com.itsm.common.error.InvalidInput
import com.itsm.common.model.Impact
import com.itsm.common.model.Urgency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ImpactUrgencyTest {

    @Test fun `codes parse case-insensitively and trimmed`() {
        assertEquals(Impact.MEDIUM, Impact.fromCode(" medium "))
        assertEquals(Urgency.HIGH, Urgency.fromCode("High"))
    }

    @Test fun `an unknown code is a client error that does not echo long input`() {
        val e = assertThrows<InvalidInput> { Impact.fromCode("x".repeat(200)) }
        assertTrue(e.message!!.length < 100, e.message)
        assertThrows<InvalidInput> { Urgency.fromCode("URGENT") }
    }

    @Test fun `raising impact widens it by one level and stops at HIGH`() {
        assertEquals(Impact.MEDIUM, Impact.LOW.raised())
        assertEquals(Impact.HIGH, Impact.MEDIUM.raised())
        assertEquals(Impact.HIGH, Impact.HIGH.raised())
    }
}
