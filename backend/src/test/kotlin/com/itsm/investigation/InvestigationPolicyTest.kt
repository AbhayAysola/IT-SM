package com.itsm.investigation

import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.InvalidStateTransition
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.investigation.InvestigationEntryType.*
import com.itsm.lifecycle.IncidentState
import com.itsm.lifecycle.IncidentState.*
import com.itsm.lifecycle.IncidentStateMachine
import com.itsm.lifecycle.IncidentTransition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class InvestigationPolicyTest {
    private val admin = CurrentUser(1, "a@campus.example", Role.ADMIN)
    private val tech = CurrentUser(2, "t@campus.example", Role.TECHNICIAN)
    private val employee = CurrentUser(3, "e@campus.example", Role.EMPLOYEE)
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val inc = "INC1024"

    private fun add(state: IncidentState, who: CurrentUser, type: InvestigationEntryType = NOTE, body: String = "checked the logs",
                    vis: Visibility? = null) = InvestigationPolicy.newManualEntry(inc, state, who, type, body, vis, now)

    // ---- manual entries
    @Test fun `a technician can note, find and record actions while working`() {
        for (state in listOf(ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO, RESOLVED)) for (t in listOf(NOTE, FINDING, ACTION_TAKEN)) {
            val e = add(state, tech, t)
            assertEquals(Visibility.INTERNAL, e.visibility, "default is internal")
            assertEquals(state, e.incidentState)
            assertEquals(tech.id, e.authorId)
        }
    }

    @Test fun `no manual entries before acknowledgement or after verification`() {
        for (state in listOf(NEW, CLASSIFIED, ASSIGNED, VERIFIED, CLOSED)) {
            val e = assertThrows<InvestigationStateException>("$state") { add(state, tech) }
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        }
    }

    @Test fun `an employee cannot add working notes`() {
        assertThrows<UnauthorizedOperation> { add(IN_PROGRESS, employee) }
    }

    @Test fun `an admin can add notes`() {
        assertEquals(admin.id, add(IN_PROGRESS, admin).authorId)
    }

    @Test fun `the author can choose to share a finding with the reporter`() {
        assertEquals(Visibility.REPORTER_VISIBLE, add(IN_PROGRESS, tech, FINDING, vis = Visibility.REPORTER_VISIBLE).visibility)
    }

    @Test fun `types made by transitions cannot be added directly`() {
        for (t in InvestigationEntryType.entries.filter { !it.manual }) {
            val e = assertThrows<InvalidInput>("$t") { add(IN_PROGRESS, admin, t) }
            assertEquals(ErrorCode.INVALID_INPUT, e.code)
        }
    }

    @Test fun `the body is trimmed, and blank or oversized is a field error`() {
        assertEquals("hello", add(IN_PROGRESS, tech, body = "  hello \n").body)
        assertEquals("body", assertThrows<InvalidInput> { add(IN_PROGRESS, tech, body = "   ") }.fieldErrors.single().field)
        assertEquals("body", assertThrows<InvalidInput> { add(IN_PROGRESS, tech, body = "x".repeat(4001)) }.fieldErrors.single().field)
        assertEquals(4000, add(IN_PROGRESS, tech, body = "x".repeat(4000)).body.length)
    }

    @Test fun `input is checked before state, state before permission`() {
        assertThrows<InvalidInput> { add(CLOSED, employee, body = " ") }                 // bad input beats state and permission
        assertThrows<InvestigationStateException> { add(CLOSED, employee) }              // state beats permission
        assertThrows<UnauthorizedOperation> { add(IN_PROGRESS, employee) }
    }

    // ---- entries produced by transitions
    private fun entry(from: IncidentState, t: IncidentTransition, who: CurrentUser, reason: String? = "because") =
        InvestigationPolicy.entryFor(inc, IncidentStateMachine.apply(from, t, who, reason), who, now)

    @Test fun `transitions with a conversation produce the matching entry`() {
        assertEquals(INFO_REQUEST, entry(IN_PROGRESS, IncidentTransition.REQUEST_INFO, tech)?.type)
        assertEquals(INFO_RESPONSE, entry(PENDING_INFO, IncidentTransition.PROVIDE_INFO, employee)?.type)
        assertEquals(RESOLUTION, entry(IN_PROGRESS, IncidentTransition.RESOLVE, tech)?.type)
        assertEquals(REOPEN_REASON, entry(RESOLVED, IncidentTransition.REOPEN, employee)?.type)
        assertEquals(REASSIGNMENT, entry(IN_PROGRESS, IncidentTransition.REASSIGN, admin)?.type)
    }

    @Test fun `the entry records the state the incident was in and the reason as its text`() {
        val e = entry(IN_PROGRESS, IncidentTransition.RESOLVE, tech, "  replaced the cable ")!!
        assertEquals(IN_PROGRESS, e.incidentState)
        assertEquals("replaced the cable", e.body)
        assertEquals(now, e.createdAt)
    }

    @Test fun `moves that carry no narrative produce no entry`() {
        assertNull(entry(CLASSIFIED, IncidentTransition.ASSIGN, admin, null))
        assertNull(entry(ASSIGNED, IncidentTransition.ACKNOWLEDGE, tech, null))
        assertNull(entry(ACKNOWLEDGED, IncidentTransition.START_WORK, tech, null))
        assertNull(entry(RESOLVED, IncidentTransition.VERIFY, employee, null))
        assertNull(entry(VERIFIED, IncidentTransition.CLOSE, admin, null))
    }

    @Test fun `every transition that requires a reason produces an entry, so no narrative is lost`() {
        for (t in IncidentTransition.entries.filter { it.requiresReason }) {
            assertTrue(entry(t.from.first(), t, admin) != null, "$t drops its reason")
        }
    }

    // ---- visibility
    @Test fun `the reporter sees conversation entries but never internal ones`() {
        val internal = add(IN_PROGRESS, tech)
        val shared = entry(IN_PROGRESS, IncidentTransition.REQUEST_INFO, tech)!!
        val reassign = entry(IN_PROGRESS, IncidentTransition.REASSIGN, admin)!!
        val all = listOf(internal, shared, reassign)
        assertEquals(listOf(shared), InvestigationPolicy.visibleTo(employee, all))
        assertEquals(all, InvestigationPolicy.visibleTo(tech, all))
        assertEquals(all, InvestigationPolicy.visibleTo(admin, all))
    }

    @Test fun `reassignment text can never be made visible to the reporter`() {
        assertThrows<IllegalArgumentException> {
            InvestigationEntry(inc, 1, REASSIGNMENT, Visibility.REPORTER_VISIBLE, "x", IN_PROGRESS, now)
        }
    }

    @Test fun `a question to the reporter can never be hidden from them`() {
        for (t in listOf(INFO_REQUEST, INFO_RESPONSE, RESOLUTION, REOPEN_REASON)) {
            assertThrows<IllegalArgumentException>("$t") { InvestigationEntry(inc, 1, t, Visibility.INTERNAL, "x", IN_PROGRESS, now) }
        }
    }

    @Test fun `entry invariants reject bad ids and authors`() {
        assertThrows<IllegalArgumentException> { InvestigationEntry("bad id!", 1, NOTE, Visibility.INTERNAL, "x", IN_PROGRESS, now) }
        assertThrows<IllegalArgumentException> { InvestigationEntry(inc, 0, NOTE, Visibility.INTERNAL, "x", IN_PROGRESS, now) }
        assertFalse(InvestigationEntry.ID_FORMAT.matches("x".repeat(31)))
    }

    @Test fun `audit action is the same for every type and has no text in it`() {
        assertEquals(setOf("INCIDENT_INVESTIGATION_ENTRY_ADDED"), InvestigationEntryType.entries.map { it.auditAction }.toSet())
    }
}

/** Alias so the tests read as intent: adding a note in the wrong state is a state conflict. */
private typealias InvestigationStateException = InvalidStateTransition
