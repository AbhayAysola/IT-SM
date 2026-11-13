package com.itsm.priority

import com.itsm.common.model.Impact
import com.itsm.common.model.Priority
import com.itsm.common.model.Urgency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PriorityCalculatorTest {
    private val calculator = PriorityCalculator()

    private fun calc(impact: Impact, urgency: Urgency, criticality: Int = 3, dependents: Int = 0) =
        calculator.calculate(PriorityInputs(impact, urgency, "Campus Wi-Fi", criticality, dependents))

    @ParameterizedTest(name = "{0} impact, {1} urgency -> {2}")
    @CsvSource(
        "HIGH, HIGH, P1", "HIGH, MEDIUM, P2", "HIGH, LOW, P3",
        "MEDIUM, HIGH, P2", "MEDIUM, MEDIUM, P3", "MEDIUM, LOW, P4",
        "LOW, HIGH, P3", "LOW, MEDIUM, P4", "LOW, LOW, P4"
    )
    fun `the matrix has the documented value in every cell, unadjusted at neutral criticality`(i: Impact, u: Urgency, p: Priority) {
        assertEquals(p, PriorityCalculator.matrix(i, u))
        val a = calc(i, u)
        assertEquals(p, a.priority)
        assertEquals(p, a.basePriority)
        assertEquals(i, a.effectiveImpact)
        assertEquals(1, a.reasons.size)
    }

    @Test fun `criticality 2 to 4 never changes the priority`() {
        for (c in 2..4) for (i in Impact.entries) for (u in Urgency.entries) {
            assertEquals(PriorityCalculator.matrix(i, u), calc(i, u, criticality = c).priority, "criticality $c, $i/$u")
        }
    }

    @Test fun `criticality 5 makes it exactly one level more urgent, never past P1`() {
        assertEquals(Priority.P1, calc(Impact.MEDIUM, Urgency.HIGH, criticality = 5).priority)
        assertEquals(Priority.P2, calc(Impact.MEDIUM, Urgency.MEDIUM, criticality = 5).priority)
        assertEquals(Priority.P3, calc(Impact.LOW, Urgency.LOW, criticality = 5).priority)
        val top = calc(Impact.HIGH, Urgency.HIGH, criticality = 5)
        assertEquals(Priority.P1, top.priority)
        assertEquals(1, top.reasons.size, "no adjustment reason when the priority did not move")
    }

    @Test fun `criticality 1 makes it exactly one level less urgent, never past P4`() {
        assertEquals(Priority.P2, calc(Impact.HIGH, Urgency.HIGH, criticality = 1).priority)
        assertEquals(Priority.P4, calc(Impact.HIGH, Urgency.LOW, criticality = 1).priority)
        assertEquals(Priority.P4, calc(Impact.LOW, Urgency.LOW, criticality = 1).priority)
    }

    @Test fun `three dependents widen the impact by one level, two do not`() {
        val two = calc(Impact.MEDIUM, Urgency.MEDIUM, dependents = 2)
        assertEquals(Impact.MEDIUM, two.effectiveImpact)
        assertEquals(Priority.P3, two.priority)

        val three = calc(Impact.MEDIUM, Urgency.MEDIUM, dependents = 3)
        assertEquals(Impact.HIGH, three.effectiveImpact)
        assertEquals(Priority.P2, three.priority)
        assertEquals(Priority.P3, three.basePriority, "the base is the reporter's own impact")
        assertEquals(3, three.dependentServiceCount)

        assertEquals(Impact.MEDIUM, calc(Impact.LOW, Urgency.HIGH, dependents = 10).effectiveImpact, "one level only")
    }

    @Test fun `impact never goes past HIGH and adds no reason when already HIGH`() {
        val a = calc(Impact.HIGH, Urgency.MEDIUM, dependents = 50)
        assertEquals(Impact.HIGH, a.effectiveImpact)
        assertEquals(Priority.P2, a.priority)
        assertEquals(1, a.reasons.size)
    }

    @Test fun `the worked examples in the design doc hold`() {
        assertEquals(Priority.P1, calc(Impact.HIGH, Urgency.HIGH, 3, 0).priority)
        assertEquals(Priority.P1, calc(Impact.MEDIUM, Urgency.HIGH, 5, 0).priority)
        assertEquals(Priority.P2, calc(Impact.MEDIUM, Urgency.MEDIUM, 3, 4).priority)
        assertEquals(Priority.P2, calc(Impact.HIGH, Urgency.HIGH, 1, 0).priority)
        assertEquals(Priority.P3, calc(Impact.LOW, Urgency.LOW, 5, 0).priority)
    }

    @Test fun `both adjustments apply together and each is explained`() {
        val a = calc(Impact.LOW, Urgency.LOW, criticality = 5, dependents = 4)
        assertEquals(Priority.P4, a.basePriority)
        assertEquals(Impact.MEDIUM, a.effectiveImpact) // MEDIUM/LOW is still P4
        assertEquals(Priority.P3, a.priority)
        assertEquals(3, a.reasons.size)
        assertTrue(a.reasons[1].contains("4 services depend on Campus Wi-Fi"), a.reasons[1])
        assertTrue(a.reasons[2].startsWith("Raised from P4 to P3"), a.reasons[2])
    }

    @Test fun `a lowered priority says so`() {
        assertTrue(calc(Impact.HIGH, Urgency.HIGH, criticality = 1).reasons.last().startsWith("Lowered from P1 to P2"))
    }

    @Test fun `inputs outside the catalog's ranges are programming errors`() {
        assertThrows<IllegalArgumentException> { PriorityInputs(Impact.LOW, Urgency.LOW, "s", 0, 0) }
        assertThrows<IllegalArgumentException> { PriorityInputs(Impact.LOW, Urgency.LOW, "s", 6, 0) }
        assertThrows<IllegalArgumentException> { PriorityInputs(Impact.LOW, Urgency.LOW, "s", 3, -1) }
    }
}
