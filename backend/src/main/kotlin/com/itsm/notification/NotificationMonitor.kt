package com.itsm.notification

import com.itsm.common.event.IncidentClassified
import com.itsm.common.event.IncidentEvent
import com.itsm.common.event.IncidentStateChanged
import com.itsm.common.spi.UserDirectory
import com.itsm.escalation.EscalationRecorded
import com.itsm.sla.SlaNotice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Turns P4's own SLA and escalation events into notifications. Runs synchronously inside the monitor's transaction, so
 * the in-app rows commit with the SLA or escalation change they report, or not at all.
 */
@Component
class NotificationEventListener(
    private val store: NotificationStore,
    private val notifications: NotificationService,
    private val directory: UserDirectory
) {
    @EventListener
    fun on(notice: SlaNotice) {
        val c = store.context(notice.incidentId) ?: return
        notifications.send(NotificationMessages.forSla(notice, c) { directory.listAdmins().filter { it.active }.map { it.id } })
    }

    @EventListener
    fun on(escalation: EscalationRecorded) {
        val c = store.context(escalation.incidentId) ?: return
        notifications.send(NotificationMessages.forEscalation(escalation, c))
    }
}

/**
 * Notices a lifecycle change as soon as P2 or P3 makes it (docs/architecture/p4.md section 6), instead of at the next
 * sweep. A classification only records the baseline (a new incident is not news); assignments, questions, fixes and
 * reopens are sent at once. `IncidentAssigned` needs no handler: it always comes with an `IncidentStateChanged`.
 *
 * Runs after the change committed, in the monitor's own transaction. A failure is logged and never reaches the publisher;
 * the sweep notices the change on its next run.
 */
@Component
@ConditionalOnProperty(prefix = "app.p4.event-listeners", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IncidentNotificationListener(private val monitor: NotificationMonitor, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentClassified) = process(event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentStateChanged) = process(event)

    private fun process(event: IncidentEvent) {
        try {
            monitor.processIncident(event.incidentId, Instant.now(clock))
        } catch (e: Exception) {
            log.error("Notifications for {} after {} failed; the next sweep sends them", event.incidentId, event.javaClass.simpleName, e)
        }
    }
}

data class NotificationRunResult(val skipped: Boolean, val changes: Int = 0, val sent: Int = 0) {
    companion object {
        val SKIPPED = NotificationRunResult(skipped = true)
    }
}

/**
 * Notices lifecycle changes (assigned, question asked, resolved, reopened) by comparing each incident with the state P4
 * last saw, and notifies the people concerned. Each change is noticed at once from P2/P3's domain events
 * (docs/architecture/p4.md section 6, [processIncident]); the sweep ([runAt], every 5 minutes) is the safety net for a
 * change no event reported. One run is one transaction under its own advisory lock. Tests call [runAt].
 */
@Component
class NotificationMonitor(
    private val store: NotificationStore,
    private val notifications: NotificationService,
    private val clock: Clock,
    @Value("\${app.notification.monitor.batch-size:500}") private val batchSize: Int
) {
    fun runOnce(): NotificationRunResult = runAt(Instant.now(clock))

    @Transactional
    fun runAt(time: Instant): NotificationRunResult {
        if (!store.tryMonitorLock()) return NotificationRunResult.SKIPPED
        return process(store.changes(batchSize), time)
    }

    /**
     * Notices one incident's change now, in its own transaction: P4's reaction to an incident domain event (see
     * [NotificationEventListener]). Waits for a running sweep instead of skipping. The watch row makes it idempotent: a
     * change already noticed (by the sweep, or by an earlier event of the same change) is not sent again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processIncident(incidentId: String, time: Instant): NotificationRunResult {
        store.waitForMonitorLock()
        return process(listOfNotNull(store.change(incidentId)), time)
    }

    private fun process(changes: List<IncidentChange>, time: Instant): NotificationRunResult {
        val now = time.truncatedTo(ChronoUnit.MICROS)
        var sent = 0
        for (change in changes) {
            val messages = NotificationMessages.forChange(change, now)
            notifications.send(messages)
            sent += messages.size
            store.remember(change.context.incidentId, change.state, change.context.assigneeId, now)
        }
        return NotificationRunResult(skipped = false, changes = changes.size, sent = sent)
    }
}

/** Runs [NotificationMonitor] on a fixed delay; `app.notification.monitor.enabled=false` turns it off (the test suite does). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.notification.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationSchedulingConfig

@Component
@ConditionalOnProperty(prefix = "app.notification.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationMonitorSchedule(private val monitor: NotificationMonitor) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${app.notification.monitor.initial-delay-ms:20000}",
        fixedDelayString = "\${app.notification.monitor.interval-ms:300000}"
    )
    fun tick() {
        try {
            val result = monitor.runOnce()
            if (!result.skipped && result.sent > 0) log.info("Notification monitor: {} changes seen, {} notifications sent", result.changes, result.sent)
        } catch (e: Exception) {
            log.error("Notification monitor run failed; it will be retried on the next run", e)
        }
    }
}
