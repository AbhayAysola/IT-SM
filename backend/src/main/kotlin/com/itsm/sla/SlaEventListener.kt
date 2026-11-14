package com.itsm.sla

import com.itsm.common.event.IncidentClassified
import com.itsm.common.event.IncidentEvent
import com.itsm.common.event.IncidentStateChanged
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant

/**
 * Evaluates an incident's SLA as soon as P2 classifies it or P3 moves it (docs/architecture/p4.md section 6), instead of
 * at the next sweep: tracking starts at once, and pauses, resumes and stops carry the exact time of the change.
 *
 * Runs after the change committed, in the monitor's own transaction. A failure is logged and never reaches the publisher;
 * the sweep ([SlaMonitorSchedule]) evaluates the incident on its next run anyway.
 */
@Component
@ConditionalOnProperty(prefix = "app.p4.event-listeners", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SlaEventListener(private val monitor: SlaMonitor, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentClassified) = evaluate(event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentStateChanged) = evaluate(event)

    private fun evaluate(event: IncidentEvent) {
        try {
            monitor.evaluateIncident(event.incidentId, Instant.now(clock))
        } catch (e: Exception) {
            log.error("SLA evaluation of {} after {} failed; the next sweep retries it", event.incidentId, event.javaClass.simpleName, e)
        }
    }
}
