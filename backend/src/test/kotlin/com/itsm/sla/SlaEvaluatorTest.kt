package com.itsm.sla

import com.itsm.common.model.Priority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class SlaEvaluatorTest {
    private val t0 = Instant.parse("2026-11-13T09:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    /** The seeded global P3 policy: respond in 60 min, resolve in 480 min, warn at 80 %. */
    private val p3 = SlaTargets(policyId = 3, scope = PolicyScope.GLOBAL, responseMinutes = 60, resolutionMinutes = 480, warningThresholdPct = 80)
    private val found = TargetLookup.Found(p3)

    private fun snap(
        state: String = "ASSIGNED", updated: Long = 0, firstResponse: Long? = null, resolved: Long? = null, priority: Priority = Priority.P3
    ) = IncidentSnapshot("INC1001", state, priority, serviceId = 7, createdAt = t0, updatedAt = at(updated),
        firstResponseAt = firstResponse?.let(::at), resolvedAt = resolved?.let(::at))

    private fun eval(previous: SlaRecord?, s: IncidentSnapshot, now: Long, lookup: TargetLookup? = null) =
        SlaEvaluator.evaluate(previous, s, lookup ?: if (previous == null) found else null, at(now))

    private fun SlaEvaluation.types() = events.map { it.type }
    private fun started(now: Long = 1) = eval(null, snap(), now).record

    // --- starting ------------------------------------------------------------------------------------------------------

    @Test fun `tracking starts with deadlines from created_at`() {
        val e = eval(null, snap("CLASSIFIED"), 1)
        assertEquals(listOf(SlaEvent.Type.STARTED), e.types())
        with(e.record) {
            assertEquals(at(60), responseDueAt); assertEquals(at(48), responseWarningAt)
            assertEquals(at(480), resolutionDueAt); assertEquals(at(384), resolutionWarningAt)
            assertEquals(ClockStatus.RUNNING, responseStatus); assertEquals(ClockStatus.RUNNING, resolutionStatus)
            assertEquals(SlaStatus.RUNNING, status); assertFalse(complete)
            assertEquals(t0, clockStart)
        }
    }

    @Test fun `a new record needs a lookup and must be for the same incident`() {
        assertThrows<IllegalArgumentException> { SlaEvaluator.evaluate(null, snap(), null, at(1)) }
        assertThrows<IllegalArgumentException> { SlaEvaluator.evaluate(started(), snap().copy(incidentId = "INC2"), null, at(2)) }
    }

    @Test fun `nothing changes and nothing is reported when nothing happened`() {
        val r = started()
        val again = eval(r, snap(), 2)
        assertEquals(r, again.record)
        assertTrue(again.events.isEmpty())
    }

    // --- missing policy ------------------------------------------------------------------------------------------------

    @Test fun `a missing policy is reported once, retried, and starts tracking when a policy appears`() {
        val missing = TargetLookup.Missing(NoPolicyReason.MISSING_SLA_CONFIG)
        val first = eval(null, snap(), 1, missing)
        assertEquals(listOf(SlaEvent.Type.POLICY_MISSING), first.types())
        assertEquals("MISSING_SLA_CONFIG", first.events[0].detail)
        with(first.record) {
            assertEquals(SlaStatus.NO_POLICY, status); assertNull(targets); assertNull(responseDueAt); assertNull(resolutionDueAt)
        }
        val retried = eval(first.record, snap(), 2, missing)
        assertTrue(retried.events.isEmpty(), "same reason: not reported again")

        val other = eval(retried.record, snap(), 3, TargetLookup.Missing(NoPolicyReason.SERVICE_NOT_FOUND))
        assertEquals(listOf(SlaEvent.Type.POLICY_MISSING), other.types(), "a different reason is reported")

        // The policy appears after the response deadline: tracking starts and the breach is recognised at once.
        val late = eval(other.record, snap(firstResponse = null), 70, found)
        assertEquals(listOf(SlaEvent.Type.STARTED, SlaEvent.Type.BREACHED), late.types())
        assertEquals(at(60), late.record.responseBreachedAt)
        assertNull(late.record.noPolicyReason)
    }

    @Test fun `facts are recorded without a policy, so a late policy judges them correctly`() {
        val missing = TargetLookup.Missing(NoPolicyReason.MISSING_SLA_CONFIG)
        val r = eval(null, snap("ACKNOWLEDGED", firstResponse = 30), 31, missing).record
        assertEquals(at(30), r.responseMetAt)
        val e = eval(r, snap("ACKNOWLEDGED", firstResponse = 30), 40, found)
        assertEquals(listOf(SlaEvent.Type.STARTED, SlaEvent.Type.MET), e.types())
        assertEquals(ClockStatus.MET, e.record.responseStatus)
    }

    @Test fun `a missing policy after tracking started keeps the old targets`() {
        val r = started()
        val e = eval(r, snap(priority = Priority.P2), 2, TargetLookup.Missing(NoPolicyReason.MISSING_SLA_CONFIG))
        assertEquals(p3, e.record.targets)
        assertEquals(Priority.P3, e.record.priority, "still the priority the targets are for, so the monitor retries")
        assertTrue(e.events.isEmpty())
    }

    @Test fun `a priority change with a new policy recalculates the deadlines from created_at`() {
        val p2 = SlaTargets(2, PolicyScope.GLOBAL, 15, 120, 80)
        val e = eval(started(), snap(priority = Priority.P2), 5, TargetLookup.Found(p2))
        assertEquals(listOf(SlaEvent.Type.RECALCULATED), e.types())
        assertEquals(at(15), e.record.responseDueAt); assertEquals(at(120), e.record.resolutionDueAt)
        assertEquals(Priority.P2, e.record.priority)
    }

    // --- response clock ------------------------------------------------------------------------------------------------

    @Test fun `the response clock warns once at the threshold`() {
        val e = eval(started(), snap(), 50)
        assertEquals(listOf(SlaEvent.Type.WARNING), e.types())
        assertEquals(SlaClock.RESPONSE, e.events[0].clock); assertEquals(at(48), e.events[0].at)
        assertEquals(ClockStatus.WARNING, e.record.responseStatus); assertEquals(SlaStatus.WARNING, e.record.status)
        assertTrue(eval(e.record, snap(), 55).events.isEmpty(), "not warned twice")
    }

    @Test fun `a breach is dated at the deadline and replaces an unseen warning`() {
        val e = eval(started(), snap(), 75)
        assertEquals(listOf(SlaEvent.Type.BREACHED), e.types(), "no warning for a clock that is already breached")
        assertEquals(at(60), e.events[0].at)
        assertEquals(at(60), e.record.responseBreachedAt); assertEquals(at(48), e.record.responseWarnedAt)
        assertEquals(SlaStatus.BREACHED, e.record.status)
    }

    @Test fun `the deadline instant itself is a breach, one microsecond before is not`() {
        assertTrue(SlaEvent.Type.BREACHED !in SlaEvaluator.evaluate(started(), snap(), null, at(60).minusNanos(1000)).types())
        assertTrue(SlaEvent.Type.BREACHED in SlaEvaluator.evaluate(started(), snap(), null, at(60)).types())
    }

    @Test fun `responding in time meets the clock, once`() {
        val e = eval(started(), snap("ACKNOWLEDGED", updated = 10, firstResponse = 10), 11)
        assertEquals(listOf(SlaEvent.Type.MET), e.types())
        assertEquals(at(10), e.events[0].at)
        assertEquals(ClockStatus.MET, e.record.responseStatus)
        assertTrue(eval(e.record, snap("ACKNOWLEDGED", firstResponse = 10), 90).events.isEmpty(), "met: never warns or breaches later")
    }

    @Test fun `a late response noticed after the fact is a breach, not a met`() {
        val e = eval(started(), snap("ACKNOWLEDGED", firstResponse = 70), 72)
        assertEquals(listOf(SlaEvent.Type.BREACHED), e.types())
        assertEquals(at(60), e.record.responseBreachedAt)
        assertEquals(ClockStatus.BREACHED, e.record.responseStatus)
    }

    // --- resolution clock: pauses, resolve, reopen ---------------------------------------------------------------------

    private fun working() = eval(started(), snap("IN_PROGRESS", updated = 20, firstResponse = 10), 21).record

    @Test fun `waiting on the reporter pauses the resolution clock, which cannot breach while paused`() {
        val paused = eval(working(), snap("PENDING_INFO", updated = 100, firstResponse = 10), 101)
        assertEquals(listOf(SlaEvent.Type.PAUSED), paused.types())
        assertEquals(at(100), paused.record.pausedSince)
        assertEquals(ClockStatus.PAUSED, paused.record.resolutionStatus); assertEquals(SlaStatus.PAUSED, paused.record.status)

        val longWait = eval(paused.record, snap("PENDING_INFO", updated = 100, firstResponse = 10), 900)
        assertTrue(longWait.events.isEmpty(), "past the original deadline but paused: no warning, no breach")
    }

    @Test fun `resuming moves every resolution deadline later by the paused time`() {
        val paused = eval(working(), snap("PENDING_INFO", updated = 100, firstResponse = 10), 101).record
        val resumed = eval(paused, snap("IN_PROGRESS", updated = 700, firstResponse = 10), 701)
        assertEquals(listOf(SlaEvent.Type.RESUMED), resumed.types())
        assertEquals("PENDING_INFO", resumed.events[0].detail)
        with(resumed.record) {
            assertNull(pausedSince); assertEquals(600 * 60L, pausedSeconds)
            assertEquals(at(480 + 600), resolutionDueAt); assertEquals(at(384 + 600), resolutionWarningAt)
            assertEquals(ClockStatus.RUNNING, resolutionStatus)
        }
    }

    @Test fun `a pause that starts after the deadline does not hide the breach`() {
        val e = eval(working(), snap("PENDING_INFO", updated = 500, firstResponse = 10), 501)
        assertEquals(listOf(SlaEvent.Type.PAUSED, SlaEvent.Type.BREACHED), e.types())
        assertEquals(at(480), e.record.resolutionBreachedAt)
        assertEquals(ClockStatus.BREACHED, e.record.resolutionStatus, "a breach outranks the pause")
    }

    @Test fun `resolving meets the resolution clock and verifying completes tracking`() {
        val resolved = eval(working(), snap("RESOLVED", updated = 200, firstResponse = 10, resolved = 200), 201)
        assertEquals(listOf(SlaEvent.Type.MET), resolved.types())
        assertEquals(SlaClock.RESOLUTION, resolved.events[0].clock)
        assertEquals(SlaStatus.MET, resolved.record.status); assertFalse(resolved.record.complete)

        val verified = eval(resolved.record, snap("VERIFIED", updated = 300, firstResponse = 10, resolved = 200), 301)
        assertTrue(verified.events.isEmpty())
        assertTrue(verified.record.complete)
    }

    @Test fun `a reopen resumes the clock and counts the time in RESOLVED as paused`() {
        val resolved = eval(working(), snap("RESOLVED", updated = 200, firstResponse = 10, resolved = 200), 201).record
        val reopened = eval(resolved, snap("IN_PROGRESS", updated = 260, firstResponse = 10), 261)
        assertEquals(listOf(SlaEvent.Type.RESUMED), reopened.types())
        assertEquals(SlaEvaluator.REOPEN, reopened.events[0].detail); assertEquals(at(260), reopened.events[0].at)
        with(reopened.record) {
            assertNull(resolutionMetAt); assertEquals(60 * 60L, pausedSeconds); assertEquals(at(540), resolutionDueAt)
            assertEquals(ClockStatus.RUNNING, resolutionStatus)
            assertEquals(ClockStatus.MET, responseStatus, "the response clock stays met")
        }
        val again = eval(reopened.record, snap("RESOLVED", updated = 300, firstResponse = 10, resolved = 300), 301)
        assertEquals(listOf(SlaEvent.Type.MET), again.types(), "resolving again is met again")
    }

    @Test fun `a pause and a resolve between two runs are both recorded`() {
        val paused = eval(working(), snap("PENDING_INFO", updated = 100, firstResponse = 10), 101).record
        val e = eval(paused, snap("RESOLVED", updated = 150, firstResponse = 10, resolved = 150), 151)
        assertEquals(listOf(SlaEvent.Type.RESUMED, SlaEvent.Type.MET), e.types())
        assertEquals(50 * 60L, e.record.pausedSeconds)
        assertEquals(at(150), e.record.resolutionMetAt)
    }

    @Test fun `a reopen and second resolve between two runs keep the new resolve time`() {
        val resolved = eval(working(), snap("RESOLVED", updated = 200, firstResponse = 10, resolved = 200), 201).record
        val e = eval(resolved, snap("RESOLVED", updated = 230, firstResponse = 10, resolved = 230), 231)
        assertEquals(at(230), e.record.resolutionMetAt)
        assertEquals(0, e.record.pausedSeconds)
        assertTrue(e.events.isEmpty())
    }

    @Test fun `a late resolve is a breach and stays one`() {
        val e = eval(working(), snap("RESOLVED", updated = 500, firstResponse = 10, resolved = 500), 501)
        assertEquals(listOf(SlaEvent.Type.BREACHED), e.types())
        assertEquals(ClockStatus.BREACHED, e.record.resolutionStatus)
        val verified = eval(e.record, snap("VERIFIED", updated = 510, firstResponse = 10, resolved = 500), 511)
        assertEquals(ClockStatus.BREACHED, verified.record.resolutionStatus); assertTrue(verified.record.complete)
    }

    @Test fun `the resolution clock warns at its own threshold`() {
        val e = eval(working(), snap("IN_PROGRESS", updated = 20, firstResponse = 10), 390)
        assertEquals(listOf(SlaEvent.Type.WARNING), e.types())
        assertEquals(SlaClock.RESOLUTION, e.events[0].clock); assertEquals(at(384), e.events[0].at)
    }

    @Test fun `the overall status is the most serious clock`() {
        // response breached, resolution still running
        val e = eval(started(), snap("ACKNOWLEDGED", firstResponse = 70), 72)
        assertEquals(ClockStatus.RUNNING, e.record.resolutionStatus)
        assertEquals(SlaStatus.BREACHED, e.record.status)
    }
}
