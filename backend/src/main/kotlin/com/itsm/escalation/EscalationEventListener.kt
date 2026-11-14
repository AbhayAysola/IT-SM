package com.itsm.escalation

import com.itsm.common.event.IncidentAssignmentFailed
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant

/**
 * When P3's automatic assignment finds nobody eligible, escalates the incident as UNASSIGNED at once instead of after
 * `unassignedMinutes` (docs/architecture/p4.md section 3): waiting cannot produce a technician.
 *
 * Runs after P3's transaction committed, in the monitor's own transaction. A failure is logged and never reaches P3; the
 * sweep still escalates the incident once `unassignedMinutes` have passed.
 */
@Component
@ConditionalOnProperty(prefix = "app.p4.event-listeners", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EscalationEventListener(private val monitor: EscalationMonitor, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: IncidentAssignmentFailed) {
        try {
            monitor.evaluateIncident(event.incidentId, Instant.now(clock), assignmentFailed = true)
        } catch (e: Exception) {
            log.error("Escalating {} after a failed assignment failed; the sweep escalates it later", event.incidentId, e)
        }
    }
}
