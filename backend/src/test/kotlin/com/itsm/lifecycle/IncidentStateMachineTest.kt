package com.itsm.lifecycle

import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.InvalidStateTransition
import com.itsm.common.error.ItsmException
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.Permission
import com.itsm.lifecycle.IncidentState.*
import com.itsm.lifecycle.IncidentTransition.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IncidentStateMachineTest {
    private val admin = CurrentUser(1, "a@campus.example", Role.ADMIN)
    private val tech = CurrentUser(2, "t@campus.example", Role.TECHNICIAN)
    private val employee = CurrentUser(3, "e@campus.example", Role.EMPLOYEE)

    private val reason = "because"

    @Test fun `the happy path walks the whole lifecycle`() {
        var state = NEW
        val steps = listOf(
            CLASSIFY to employee, ASSIGN to admin, ACKNOWLEDGE to tech, START_WORK to tech,
            RESOLVE to tech, VERIFY to employee, CLOSE to admin
        )
        for ((t, who) in steps) {
            state = IncidentStateMachine.apply(state, t, who, reason).newState
        }
        assertEquals(CLOSED, state)
    }

    @Test fun `every transition is accepted from each of its source states by a holder of its permission`() {
        for (t in IncidentTransition.entries) for (from in t.from) {
            val user = admin // ADMIN holds every permission
            val r = IncidentStateMachine.apply(from, t, user, reason)
            assertEquals(from, r.previousState); assertEquals(t.to, r.newState); assertEquals(t.auditAction, r.auditAction)
        }
    }

    @Test fun `every transition is rejected from every other state with 409 even for an admin`() {
        for (t in IncidentTransition.entries) for (from in IncidentState.entries - t.from) {
            val e = assertThrows<InvalidStateTransition>("$t from $from") { IncidentStateMachine.apply(from, t, admin, reason) }
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        }
    }

    @Test fun `CLOSED is terminal and nothing leaves it`() {
        assertTrue(CLOSED.terminal)
        assertTrue(IncidentStateMachine.transitionsFrom(CLOSED).isEmpty())
        assertEquals(listOf(CLOSED), IncidentState.entries.filter { it.terminal })
    }

    @Test fun `every non-terminal state can reach CLOSED, so no incident can get stuck`() {
        for (start in IncidentState.entries) {
            val seen = mutableSetOf(start); val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) for (t in IncidentStateMachine.transitionsFrom(queue.removeFirst())) {
                if (seen.add(t.to)) queue.add(t.to)
            }
            assertTrue(CLOSED in seen, "$start cannot reach CLOSED")
        }
    }

    @Test fun `a new incident cannot jump to closed or resolved`() {
        assertThrows<InvalidStateTransition> { IncidentStateMachine.apply(NEW, CLOSE, admin) }
        assertThrows<InvalidStateTransition> { IncidentStateMachine.apply(NEW, RESOLVE, admin, reason) }
        assertEquals("Incident cannot move from NEW to CLOSED",
            assertThrows<InvalidStateTransition> { IncidentStateMachine.apply(NEW, CLOSE, admin) }.message)
    }

    @Test fun `resolved cannot be closed without verification`() {
        assertThrows<InvalidStateTransition> { IncidentStateMachine.apply(RESOLVED, CLOSE, admin) }
    }

    @Test fun `an employee cannot assign or work an incident`() {
        assertThrows<UnauthorizedOperation> { IncidentStateMachine.apply(CLASSIFIED, ASSIGN, employee) }
        assertThrows<UnauthorizedOperation> { IncidentStateMachine.apply(ASSIGNED, ACKNOWLEDGE, employee) }
        assertThrows<UnauthorizedOperation> { IncidentStateMachine.apply(IN_PROGRESS, RESOLVE, employee, reason) }
    }

    @Test fun `a technician cannot assign, verify or close`() {
        assertThrows<UnauthorizedOperation> { IncidentStateMachine.apply(CLASSIFIED, ASSIGN, tech) }
        assertThrows<UnauthorizedOperation> { IncidentStateMachine.apply(RESOLVED, VERIFY, tech) }
        assertThrows<UnauthorizedOperation> { IncidentStateMachine.apply(VERIFIED, CLOSE, tech) }
    }

    @Test fun `only reporter-side permission can verify or reopen`() {
        assertEquals(VERIFIED, IncidentStateMachine.apply(RESOLVED, VERIFY, employee).newState)
        assertEquals(IN_PROGRESS, IncidentStateMachine.apply(RESOLVED, REOPEN, employee, reason).newState)
    }

    @Test fun `the legality check comes before the permission check`() {
        // An employee cannot assign, but on a CLOSED incident the answer is "it has moved on" (409), not 403.
        assertThrows<InvalidStateTransition> { IncidentStateMachine.apply(CLOSED, ASSIGN, employee) }
    }

    @Test fun `reasons are required where the table says so`() {
        for (t in IncidentTransition.entries.filter { it.requiresReason }) {
            val from = t.from.first()
            assertThrows<InvalidInput>("$t without reason") { IncidentStateMachine.apply(from, t, admin) }
            assertThrows<InvalidInput>("$t with blank reason") { IncidentStateMachine.apply(from, t, admin, "   ") }
            assertEquals(reason, IncidentStateMachine.apply(from, t, admin, "  $reason  ").reason)
        }
    }

    @Test fun `a reason is optional elsewhere and blank becomes null`() {
        assertNull(IncidentStateMachine.apply(ASSIGNED, ACKNOWLEDGE, tech, "  ").reason)
    }

    @Test fun `an over-long reason is rejected`() {
        val e = assertThrows<InvalidInput> {
            IncidentStateMachine.apply(IN_PROGRESS, RESOLVE, tech, "x".repeat(IncidentStateMachine.MAX_REASON_LENGTH + 1))
        }
        assertEquals(ErrorCode.INVALID_INPUT, e.code)
        IncidentStateMachine.apply(IN_PROGRESS, RESOLVE, tech, "x".repeat(IncidentStateMachine.MAX_REASON_LENGTH))
    }

    @Test fun `reassign is allowed from every active state and lands in ASSIGNED`() {
        for (s in listOf(ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO)) {
            assertEquals(ASSIGNED, IncidentStateMachine.apply(s, REASSIGN, admin, reason).newState)
        }
        for (s in listOf(NEW, CLASSIFIED, RESOLVED, VERIFIED, CLOSED)) {
            assertFalse(IncidentStateMachine.canApply(REASSIGN, s), "$s")
        }
    }

    @Test fun `available actions follow state and permission`() {
        assertEquals(listOf(ASSIGN), IncidentStateMachine.availableTo(CLASSIFIED, admin))
        assertTrue(IncidentStateMachine.availableTo(CLASSIFIED, tech).isEmpty())
        assertEquals(setOf(VERIFY, REOPEN), IncidentStateMachine.availableTo(RESOLVED, employee).toSet())
        assertTrue(IncidentStateMachine.availableTo(CLOSED, admin).isEmpty())
    }

    @Test fun `workload counts exactly the states where a technician is working`() {
        assertEquals(setOf(ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO),
            IncidentState.entries.filter { it.countsTowardWorkload }.toSet())
    }

    @Test fun `audit actions and states are valid audit vocabulary`() {
        val upperSnake = Regex("^[A-Z][A-Z0-9_]*$")
        IncidentTransition.entries.forEach { assertTrue(upperSnake.matches(it.auditAction), it.auditAction) }
        IncidentState.entries.forEach { assertTrue(upperSnake.matches(it.name), it.name) }
    }

    @Test fun `every transition requires a real permission and no transition is a no-op`() {
        for (t in IncidentTransition.entries) {
            assertTrue(t.permission in Permission.entries)
            if (t != REASSIGN) assertTrue(t.from.none { it == t.to }, "$t is a self-loop")
            assertTrue(t.from.isNotEmpty())
        }
    }

    @Test fun `state names round-trip and unknown names are a data error`() {
        IncidentState.entries.forEach { assertEquals(it, IncidentState.fromName(" ${it.name.lowercase()} ")) }
        assertThrows<IllegalStateException> { IncidentState.fromName("NOPE") }
    }

    @Test fun `errors are ItsmExceptions the global handler can render`() {
        assertTrue(assertThrows<ItsmException> { IncidentStateMachine.apply(NEW, CLOSE, admin) } is InvalidStateTransition)
    }
}
