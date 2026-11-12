package com.itsm.lifecycle

import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.InvalidStateTransition
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.feedback.FeedbackNotAllowed
import com.itsm.investigation.InvestigationEntryType
import com.itsm.investigation.Visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.itsm.lifecycle.IncidentState as S
import com.itsm.lifecycle.IncidentTransition as T

class IncidentLifecycleServiceTest {
    private val log = FakeLog()
    private val feedback = FakeFeedback()
    private val audit = RecordingAudit()

    private fun service(vararg rows: IncidentRecord): Pair<IncidentLifecycleService, FakeIncidentStore> {
        val store = FakeIncidentStore(*rows)
        return IncidentLifecycleService(store, log, feedback, audit, CLOCK) to store
    }

    // ---- transitions
    @Test fun `the assignee acknowledges, the first response time is set once and the audit trail has the states`() {
        val (svc, store) = service(record(state = S.ASSIGNED))
        val updated = svc.transition("INC1", T.ACKNOWLEDGE, null, techA)
        assertEquals(S.ACKNOWLEDGED, updated.state)
        assertEquals(NOW, updated.firstResponseAt)
        assertEquals(updated, store.rows["INC1"])
        val event = audit.events.single()
        assertEquals("INCIDENT_ACKNOWLEDGED", event.action)
        assertEquals("ASSIGNED", event.previousState); assertEquals("ACKNOWLEDGED", event.newState)
        assertEquals("INC1", event.incidentId)
    }

    @Test fun `a later acknowledgement never moves the first response time`() {
        val earlier = NOW.minusSeconds(500)
        val (svc, _) = service(record(state = S.ASSIGNED).copy(firstResponseAt = earlier)) // e.g. after a reassignment
        assertEquals(earlier, svc.transition("INC1", T.ACKNOWLEDGE, null, techA).firstResponseAt)
    }

    @Test fun `resolve records a reporter-visible resolution, sets resolved_at, and keeps the text out of the audit`() {
        val (svc, _) = service(record(state = S.IN_PROGRESS))
        val updated = svc.transition("INC1", T.RESOLVE, "Replaced the toner cartridge", techA)
        assertEquals(S.RESOLVED, updated.state); assertEquals(NOW, updated.resolvedAt)
        val entry = log.entries.single()
        assertEquals(InvestigationEntryType.RESOLUTION, entry.type)
        assertEquals(Visibility.REPORTER_VISIBLE, entry.visibility)
        assertEquals(S.IN_PROGRESS, entry.incidentState)
        val event = audit.events.single()
        assertEquals(true, event.metadata["reasonGiven"])
        assertFalse(event.metadata.values.any { it.toString().contains("toner") }, "the reason text must not reach the audit")
        assertFalse(event.description.contains("toner"))
    }

    @Test fun `reopen clears resolved_at and writes the reason for the technician`() {
        val (svc, _) = service(record(state = S.RESOLVED))
        val updated = svc.transition("INC1", T.REOPEN, "Still prints blank pages", reporter)
        assertEquals(S.IN_PROGRESS, updated.state); assertNull(updated.resolvedAt)
        assertEquals(InvestigationEntryType.REOPEN_REASON, log.entries.single().type)
    }

    @Test fun `verify keeps resolved_at and close sets closed_at`() {
        val (svc, _) = service(record(state = S.RESOLVED))
        val verified = svc.transition("INC1", T.VERIFY, null, reporter)
        assertEquals(S.VERIFIED, verified.state); assertNotNull(verified.resolvedAt); assertNull(verified.closedAt)
        val closed = svc.transition("INC1", T.CLOSE, null, admin)
        assertEquals(S.CLOSED, closed.state); assertEquals(NOW, closed.closedAt)
        assertTrue(log.entries.isEmpty(), "verify and close leave no timeline entry; the audit trail holds them")
    }

    @Test fun `the reporter answers a question and work resumes`() {
        val (svc, _) = service(record(state = S.IN_PROGRESS))
        svc.transition("INC1", T.REQUEST_INFO, "Which floor is the printer on?", techA)
        val updated = svc.transition("INC1", T.PROVIDE_INFO, "Second floor, by the lift", reporter)
        assertEquals(S.IN_PROGRESS, updated.state)
        assertEquals(listOf(InvestigationEntryType.INFO_REQUEST, InvestigationEntryType.INFO_RESPONSE), log.entries.map { it.type })
    }

    @Test fun `an incident you may not see does not exist for you`() {
        val (svc, store) = service(record(state = S.IN_PROGRESS))
        assertThrows<ResourceNotFound> { svc.transition("INC1", T.RESOLVE, "fixed", stranger) }
        assertThrows<ResourceNotFound> { svc.transition("INC1", T.RESOLVE, "fixed", techB) } // a technician, but not the assignee
        assertThrows<ResourceNotFound> { svc.transition("NOPE", T.RESOLVE, "fixed", admin) }
        assertEquals(0, store.saves); assertTrue(audit.events.isEmpty())
    }

    @Test fun `ownership is checked on top of the permission`() {
        val (svc, _) = service(record(state = S.IN_PROGRESS))
        // the reporter can see it but holds no work permission
        assertThrows<UnauthorizedOperation> { svc.transition("INC1", T.RESOLVE, "fixed", reporter) }
        // the assignee has the work permission but is not the reporter and holds no verify permission
        val (svc2, _) = service(record(state = S.RESOLVED))
        assertThrows<UnauthorizedOperation> { svc2.transition("INC1", T.VERIFY, null, techA) }
        // a technician who reported an incident someone else works on: may see it, may not work it
        val own = record(state = S.IN_PROGRESS).copy(reporterId = TECH_B)
        val (svc3, _) = service(own)
        assertThrows<UnauthorizedOperation> { svc3.transition("INC1", T.RESOLVE, "fixed", techB) }
    }

    @Test fun `an admin is exempt from ownership`() {
        val (svc, _) = service(record(state = S.IN_PROGRESS))
        assertEquals(S.RESOLVED, svc.transition("INC1", T.RESOLVE, "Resolved on the assignee's behalf", admin).state)
    }

    @Test fun `illegal moves and missing reasons change nothing`() {
        val (svc, store) = service(record(state = S.ASSIGNED))
        assertThrows<InvalidStateTransition> { svc.transition("INC1", T.RESOLVE, "fixed", techA) }
        val (svc2, store2) = service(record(state = S.IN_PROGRESS))
        assertThrows<InvalidInput> { svc2.transition("INC1", T.RESOLVE, "  ", techA) }
        assertEquals(0, store.saves + store2.saves); assertTrue(audit.events.isEmpty()); assertTrue(log.entries.isEmpty())
    }

    @Test fun `classify, assign and reassign are not done through this service`() {
        val (svc, _) = service(record(state = S.NEW))
        for (t in listOf(T.CLASSIFY, T.ASSIGN, T.REASSIGN)) assertThrows<InvalidInput> { svc.transition("INC1", t, "x", admin) }
    }

    // ---- what to show
    @Test fun `available transitions follow state and ownership`() {
        val (svc, _) = service(record(id = "A", state = S.IN_PROGRESS), record(id = "B", state = S.PENDING_INFO), record(id = "C", state = S.CLASSIFIED))
        assertEquals(setOf(T.REQUEST_INFO, T.RESOLVE), svc.availableTransitions("A", techA).toSet())
        assertEquals(emptyList<T>(), svc.availableTransitions("A", reporter))
        assertEquals(listOf(T.PROVIDE_INFO), svc.availableTransitions("B", reporter))
        assertEquals(emptyList<T>(), svc.availableTransitions("C", admin), "assignment is a different operation")
        assertThrows<ResourceNotFound> { svc.availableTransitions("A", stranger) }
    }

    // ---- notes and timeline
    @Test fun `a note is internal by default and the audit has the type but not the text`() {
        val (svc, _) = service(record(state = S.IN_PROGRESS))
        val entry = svc.addNote("INC1", InvestigationEntryType.FINDING, "  disk full on host db-3  ", null, techA)
        assertEquals("disk full on host db-3", entry.body); assertEquals(Visibility.INTERNAL, entry.visibility)
        val event = audit.events.single()
        assertEquals("INCIDENT_INVESTIGATION_ENTRY_ADDED", event.action)
        assertEquals(mapOf("entryType" to "FINDING", "visibility" to "INTERNAL"), event.metadata)
    }

    @Test fun `notes need the assignee, a working state and a well-formed body`() {
        val (svc, _) = service(record(state = S.ASSIGNED))
        assertThrows<InvalidStateTransition> { svc.addNote("INC1", InvestigationEntryType.NOTE, "x", null, techA) }
        val (svc2, _) = service(record(state = S.IN_PROGRESS))
        assertThrows<ResourceNotFound> { svc2.addNote("INC1", InvestigationEntryType.NOTE, "x", null, techB) }
        assertThrows<UnauthorizedOperation> { svc2.addNote("INC1", InvestigationEntryType.NOTE, "x", null, reporter) }
        assertThrows<InvalidInput> { svc2.addNote("INC1", InvestigationEntryType.NOTE, " ", null, techA) }
        assertThrows<InvalidInput> { svc2.addNote("INC1", InvestigationEntryType.RESOLUTION, "x", null, techA) }
        assertTrue(log.entries.isEmpty() && audit.events.isEmpty())
    }

    @Test fun `the reporter never receives internal entries`() {
        val (svc, _) = service(record(state = S.IN_PROGRESS))
        svc.addNote("INC1", InvestigationEntryType.NOTE, "suspect the switch", null, techA)
        svc.addNote("INC1", InvestigationEntryType.FINDING, "port 4 is flapping", Visibility.REPORTER_VISIBLE, techA)
        assertEquals(listOf("port 4 is flapping"), svc.timeline("INC1", reporter).map { it.body })
        assertEquals(2, svc.timeline("INC1", techA).size)
        assertThrows<ResourceNotFound> { svc.timeline("INC1", stranger) }
    }

    // ---- feedback
    @Test fun `the reporter rates a verified incident once and the audit carries the rating only`() {
        val (svc, _) = service(record(state = S.VERIFIED))
        val saved = svc.submitFeedback("INC1", 4, "Quick and polite", reporter)
        assertEquals(4, saved.rating); assertEquals(NOW, saved.submittedAt)
        assertEquals(saved, svc.feedback("INC1", reporter))
        val event = audit.events.single()
        assertEquals("INCIDENT_FEEDBACK_RECORDED", event.action); assertEquals(mapOf("rating" to 4), event.metadata)
        assertThrows<DuplicateResource> { svc.submitFeedback("INC1", 5, null, reporter) }
    }

    @Test fun `feedback is refused before the fix is confirmed and to anyone but the reporter`() {
        val (svc, _) = service(record(state = S.RESOLVED))
        assertThrows<FeedbackNotAllowed> { svc.submitFeedback("INC1", 5, null, reporter) }
        val (svc2, _) = service(record(state = S.CLOSED))
        assertThrows<UnauthorizedOperation> { svc2.submitFeedback("INC1", 5, null, admin) }
        assertThrows<UnauthorizedOperation> { svc2.submitFeedback("INC1", 5, null, techA) }
        assertThrows<ResourceNotFound> { svc2.submitFeedback("INC1", 5, null, stranger) }
        assertTrue(feedback.saved.isEmpty() && audit.events.isEmpty())
    }

    // ---- precedents
    @Test fun `similar fixes are for technicians and admins, scoped to their incident`() {
        val (svc, store) = service(record(state = S.IN_PROGRESS))
        store.similar = listOf(ResolvedIncident("INC0", "Printer offline", "Rebooted the print server", NOW, TECH_B, true))
        assertEquals(1, svc.similarResolved("INC1", techA).size)
        assertThrows<UnauthorizedOperation> { svc.similarResolved("INC1", reporter) }
        assertThrows<ResourceNotFound> { svc.similarResolved("INC1", techB) }
    }

    // ---- list
    @Test fun `a list holds only what the caller reported or is assigned, and an admin sees everything`() {
        val (svc, _) = service(
            record(id = "A", state = S.IN_PROGRESS), record(id = "B", state = S.CLASSIFIED),
            record(id = "C", state = S.IN_PROGRESS, assignee = TECH_B).copy(reporterId = 777)
        )
        assertEquals(setOf("A", "B"), svc.list(reporter, emptySet(), null, 0, 20).rows.map { it.record.id }.toSet())
        assertEquals(listOf("A"), svc.list(techA, emptySet(), null, 0, 20).rows.map { it.record.id })
        assertEquals(emptyList<String>(), svc.list(stranger, emptySet(), null, 0, 20).rows.map { it.record.id })
        assertEquals(3, svc.list(admin, emptySet(), null, 0, 20).total)
    }

    @Test fun `filters narrow the page but the counts cover the whole scope`() {
        val (svc, _) = service(record(id = "A", state = S.IN_PROGRESS), record(id = "B", state = S.CLASSIFIED))
        val result = svc.list(reporter, setOf(S.CLASSIFIED), null, 0, 20)
        assertEquals(listOf("B"), result.rows.map { it.record.id })
        assertEquals(mapOf(S.IN_PROGRESS to 1, S.CLASSIFIED to 1), result.stateCounts)
    }
}
