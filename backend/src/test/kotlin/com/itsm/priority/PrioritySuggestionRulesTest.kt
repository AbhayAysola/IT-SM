package com.itsm.priority

import com.itsm.common.model.Impact
import com.itsm.common.model.Priority
import com.itsm.common.model.Urgency
import com.itsm.common.spi.PriorityAssessment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrioritySuggestionRulesTest {
    private fun assessed(p: Priority, vararg reasons: String) =
        PriorityAssessment(p, p, Impact.MEDIUM, Impact.MEDIUM, Urgency.HIGH, 5, 0, reasons.toList())

    private fun suggest(current: Priority, assessed: PriorityAssessment? = null, manual: Boolean = false, duplicates: Int = 0) =
        PrioritySuggestionRules.suggest(SuggestionInput(current, assessed, manual, duplicates))

    @Test fun `nothing when the priority still fits`() {
        assertNull(suggest(Priority.P2, assessed(Priority.P2)))
        assertNull(suggest(Priority.P2, null, duplicates = 2), "two duplicates are not yet a pattern")
    }

    @Test fun `a changed service suggests what the rules give now, up or down, with their reasons`() {
        val up = suggest(Priority.P2, assessed(Priority.P1, "Business criticality 5 raises it."))!!
        assertEquals(Priority.P1, up.priority)
        assertEquals(listOf(SuggestionRule.CRITICALITY), up.rules)
        assertTrue(up.reasons.single().contains("Business criticality 5 raises it."), up.reasons.toString())
        assertEquals(Priority.P4, suggest(Priority.P2, assessed(Priority.P4))!!.priority)
    }

    @Test fun `an admin's own change is not second-guessed by the criticality rule`() {
        assertNull(suggest(Priority.P3, assessed(Priority.P1), manual = true))
    }

    @Test fun `duplicates raise it one level, and P1 stays P1`() {
        val s = suggest(Priority.P3, duplicates = 3)!!
        assertEquals(Priority.P2, s.priority); assertEquals(listOf(SuggestionRule.DUPLICATES), s.rules)
        assertTrue(s.reasons.single().startsWith("3 new reports"))
        assertNull(suggest(Priority.P1, duplicates = 9))
        assertEquals(Priority.P2, suggest(Priority.P3, manual = true, duplicates = 4)!!.priority, "duplicates still count after a manual change")
    }

    @Test fun `the most urgent suggestion wins, with every rule that agrees`() {
        val both = suggest(Priority.P3, assessed(Priority.P1, "Critical."), duplicates = 3)!!
        assertEquals(Priority.P1, both.priority)
        assertEquals(listOf(SuggestionRule.CRITICALITY, SuggestionRule.DUPLICATES), both.rules)

        val opposed = suggest(Priority.P3, assessed(Priority.P4), duplicates = 3)!!
        assertEquals(Priority.P2, opposed.priority, "a spreading problem outweighs a lower assessment")
        assertEquals(listOf(SuggestionRule.DUPLICATES), opposed.rules)
    }
}
