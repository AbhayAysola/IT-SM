package com.itsm.notification

import com.itsm.common.model.Priority
import com.itsm.escalation.EscalationRecorded
import com.itsm.escalation.EscalationTrigger
import com.itsm.sla.SlaClock
import com.itsm.sla.SlaNotice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

class NotificationMessagesTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val incident = IncidentContext("INC1001", "Printer offline", Priority.P2, reporterId = 1, assigneeId = 7)

    private fun change(state: String, previousState: String?, previousAssignee: Long? = 7, assignee: Long? = 7, assignedAt: Instant? = now.minusSeconds(60)) =
        IncidentChange(incident.copy(assigneeId = assignee), state, assignedAt, previousState, previousAssignee)

    private fun List<Notification>.summary() = map { it.type to it.recipientId }

    // --- lifecycle ----------------------------------------------------------------------------------------------------

    @Test fun `a new assignment tells the assignee and the reporter`() {
        val n = NotificationMessages.forChange(change("ASSIGNED", "CLASSIFIED", previousAssignee = null), now)
        assertEquals(listOf(NotificationType.INCIDENT_ASSIGNED to 7L, NotificationType.ASSIGNMENT_MADE to 1L), n.summary())
        assertEquals("INC1001 was assigned to you", n[0].title)
        assertEquals("Printer offline", n[0].body)
    }

    @Test fun `a reassignment tells the new assignee`() {
        val n = NotificationMessages.forChange(change("ASSIGNED", "IN_PROGRESS", previousAssignee = 7, assignee = 8), now)
        assertEquals(listOf(NotificationType.INCIDENT_ASSIGNED to 8L, NotificationType.ASSIGNMENT_MADE to 1L), n.summary())
    }

    @Test fun `a technician who reported the incident is not told twice`() {
        val own = IncidentChange(incident.copy(reporterId = 7), "ASSIGNED", now, "CLASSIFIED", null)
        assertEquals(listOf(NotificationType.INCIDENT_ASSIGNED to 7L), NotificationMessages.forChange(own, now).summary())
    }

    @Test fun `a question, a fix and a reopen each tell the person who must act`() {
        assertEquals(listOf(NotificationType.INFO_REQUESTED to 1L), NotificationMessages.forChange(change("PENDING_INFO", "IN_PROGRESS"), now).summary())
        assertEquals(listOf(NotificationType.INCIDENT_RESOLVED to 1L), NotificationMessages.forChange(change("RESOLVED", "IN_PROGRESS"), now).summary())
        assertEquals(listOf(NotificationType.INCIDENT_REOPENED to 7L), NotificationMessages.forChange(change("IN_PROGRESS", "RESOLVED"), now).summary())
    }

    @Test fun `moves nobody needs to hear about send nothing`() {
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(change("ACKNOWLEDGED", "ASSIGNED"), now))
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(change("IN_PROGRESS", "ACKNOWLEDGED"), now))
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(change("CLOSED", "VERIFIED"), now))
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(change("VERIFIED", "RESOLVED"), now), "verified, not reopened")
    }

    @Test fun `a first-seen incident is a silent baseline, except a fresh assignment`() {
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(change("IN_PROGRESS", previousState = null, previousAssignee = null), now))
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(change("RESOLVED", previousState = null, previousAssignee = null), now))
        val fresh = change("ASSIGNED", previousState = null, previousAssignee = null, assignedAt = now.minus(Duration.ofMinutes(10)))
        assertEquals(2, NotificationMessages.forChange(fresh, now).size)
        val stale = change("ASSIGNED", previousState = null, previousAssignee = null, assignedAt = now.minus(Duration.ofMinutes(16)))
        assertEquals(emptyList<Notification>(), NotificationMessages.forChange(stale, now))
    }

    // --- SLA and escalation -------------------------------------------------------------------------------------------

    @Test fun `SLA warnings and breaches go to the assignee, with the deadline`() {
        val warn = NotificationMessages.forSla(SlaNotice("INC1001", SlaNotice.Kind.WARNING, SlaClock.RESPONSE, Instant.parse("2026-11-14T10:05:00Z")), incident) { error("not needed") }
        assertEquals(listOf(NotificationType.SLA_WARNING to 7L), warn.summary())
        assertEquals("INC1001: response SLA at risk", warn[0].title)
        assertTrue(warn[0].body.contains("Due 14 Nov, 10:05 UTC"), warn[0].body)
        val breach = NotificationMessages.forSla(SlaNotice("INC1001", SlaNotice.Kind.BREACHED, SlaClock.RESOLUTION, now), incident) { error("not needed") }
        assertEquals("INC1001: resolution SLA breached", breach.single().title)
    }

    @Test fun `with nobody assigned an SLA warning has nobody to tell`() {
        assertEquals(emptyList<Notification>(), NotificationMessages.forSla(SlaNotice("INC1001", SlaNotice.Kind.WARNING, SlaClock.RESPONSE, now), incident.copy(assigneeId = null)) { listOf(90) })
    }

    @Test fun `a missing policy tells each admin once`() {
        val n = NotificationMessages.forSla(SlaNotice("INC1001", SlaNotice.Kind.POLICY_MISSING, null, null), incident) { listOf(90, 91, 90) }
        assertEquals(listOf(NotificationType.SLA_POLICY_MISSING to 90L, NotificationType.SLA_POLICY_MISSING to 91L), n.summary())
    }

    @Test fun `an escalation tells everyone it reached, with the reason`() {
        val n = NotificationMessages.forEscalation(EscalationRecorded("INC1001", EscalationTrigger.RESOLUTION_OVERDUE, listOf(20, 21)), incident)
        assertEquals(listOf(NotificationType.ESCALATED to 20L, NotificationType.ESCALATED to 21L), n.summary())
        assertEquals("Resolution far past its target: Printer offline", n[0].body)
    }

    // --- channels -----------------------------------------------------------------------------------------------------

    @Test fun `the more urgent the incident, the more channels`() {
        assertEquals(setOf(ChannelKey.IN_APP, ChannelKey.EMAIL, ChannelKey.SMS), ChannelPolicy.channelsFor(Priority.P1))
        assertEquals(setOf(ChannelKey.IN_APP, ChannelKey.EMAIL), ChannelPolicy.channelsFor(Priority.P2))
        assertEquals(setOf(ChannelKey.IN_APP), ChannelPolicy.channelsFor(Priority.P3))
        assertEquals(setOf(ChannelKey.IN_APP), ChannelPolicy.channelsFor(Priority.P4))
        assertEquals(setOf(ChannelKey.IN_APP), ChannelPolicy.channelsFor(null))
        assertEquals(ChannelPolicy.channelsFor(Priority.P2), NotificationMessages.forChange(change("RESOLVED", "IN_PROGRESS"), now).single().channels)
    }

    @Test fun `every notification is kept in the inbox and has text`() {
        assertThrows<IllegalArgumentException> { Notification(1, NotificationType.ESCALATED, null, null, "t", "b", setOf(ChannelKey.EMAIL)) }
        assertThrows<IllegalArgumentException> { Notification(1, NotificationType.ESCALATED, null, null, " ", "b", setOf(ChannelKey.IN_APP)) }
    }

    // --- service ------------------------------------------------------------------------------------------------------

    private class Recording(override val key: ChannelKey, override val transactional: Boolean, val fail: Boolean = false) : NotificationChannel {
        val delivered = mutableListOf<Notification>()
        override fun deliver(notification: Notification) {
            if (fail) error("mail server down")
            delivered += notification
        }
    }

    @AfterEach fun clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
    }

    @Test fun `the inbox is written now, email and SMS only after the commit, and a failing channel never throws`() {
        val inApp = Recording(ChannelKey.IN_APP, transactional = true)
        val email = Recording(ChannelKey.EMAIL, transactional = false, fail = true)
        val sms = Recording(ChannelKey.SMS, transactional = false)
        val service = NotificationService(listOf(inApp, email, sms))
        val p1 = Notification(7, NotificationType.SLA_BREACHED, "INC1", Priority.P1, "t", "b", ChannelPolicy.channelsFor(Priority.P1))

        TransactionSynchronizationManager.initSynchronization()
        service.send(listOf(p1))
        assertEquals(1, inApp.delivered.size)
        assertTrue(sms.delivered.isEmpty(), "nothing external before the commit")

        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertEquals(1, sms.delivered.size, "SMS is still sent although email failed")
        assertFalse(email.delivered.isNotEmpty())
    }

    @Test fun `a missing channel bean is a startup error`() {
        assertThrows<IllegalArgumentException> { NotificationService(listOf(Recording(ChannelKey.IN_APP, true))) }
    }
}
