package com.itsm.escalation

import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AuditPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class EscalationRunResult(val skipped: Boolean, val evaluated: Int = 0, val escalated: Int = 0) {
    companion object {
        val SKIPPED = EscalationRunResult(skipped = true)
    }
}

/**
 * Escalates open incidents (docs/architecture/p4.md section 3): asks [EscalationPolicy] which triggers are due, resolves
 * who receives each through [EscalationTargets], records it and publishes `INCIDENT_ESCALATED` as
 * `AuditActor.system("ESCALATION_MONITOR")`. It reads the SLA facts the SLA monitor committed, so a breach escalates at
 * most one polling interval after it is recorded.
 *
 * Escalation records and notifies; it never reassigns (that stays a human decision through P3). Notifications themselves
 * come with P4's notifications feature, which reads this log.
 *
 * One run is one transaction under its own advisory lock, so two instances never escalate the same incident twice (the
 * UNIQUE constraint on (incident, trigger) backs this up). Escalation is mostly time-driven, so the sweep stays; P3's
 * `IncidentAssignmentFailed` event escalates at once through [evaluateIncident]. Tests call [runAt] directly.
 */
@Component
class EscalationMonitor(
    private val store: EscalationStore,
    private val targets: EscalationTargets,
    private val audit: AuditPublisher,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
    @Value("\${app.escalation.monitor.batch-size:500}") private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(): EscalationRunResult = runAt(Instant.now(clock))

    @Transactional
    fun runAt(time: Instant): EscalationRunResult {
        if (!store.tryMonitorLock()) return EscalationRunResult.SKIPPED
        val result = escalate(store.openCandidates(batchSize), time, assignmentFailed = false)
        if (result.evaluated == batchSize) log.info("Escalation monitor reached its batch size ({}); the rest is evaluated next run", batchSize)
        return result
    }

    /**
     * Evaluates one incident now, in its own transaction: P4's reaction to an incident domain event (see
     * [EscalationEventListener]). With [assignmentFailed] (P3 found nobody eligible) an incident still `CLASSIFIED` is
     * escalated as UNASSIGNED at once instead of after `unassignedMinutes`. Waits for a running sweep instead of skipping;
     * a trigger never fires twice either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun evaluateIncident(incidentId: String, time: Instant, assignmentFailed: Boolean): EscalationRunResult {
        store.waitForMonitorLock()
        return escalate(listOfNotNull(store.openCandidate(incidentId)), time, assignmentFailed)
    }

    private fun escalate(candidates: List<EscalationCandidate>, time: Instant, assignmentFailed: Boolean): EscalationRunResult {
        val now = time.truncatedTo(ChronoUnit.MICROS)
        val settings = store.settings()
        val fired = store.firedTriggers(candidates.map { it.incidentId })
        var escalated = 0
        for (c in candidates) {
            for (trigger in EscalationPolicy.due(c, settings, fired[c.incidentId].orEmpty(), now, assignmentFailed)) {
                val people = targets.resolve(trigger.tier, c.assigneeId).map { it.id }
                if (people.isEmpty()) log.warn("Escalation {} of {} has nobody to go to (no team lead and no active admin)", trigger, c.incidentId)
                store.record(c.incidentId, trigger, people, c.assigneeId, now)
                audit.publish(
                    AuditableEvent(
                        actor = AuditActor.system(ACTOR), action = "INCIDENT_ESCALATED", entityType = "INCIDENT",
                        entityId = c.incidentId, incidentId = c.incidentId,
                        description = "Escalated: ${trigger.description}",
                        metadata = buildMap {
                            put("trigger", trigger.name)
                            put("tier", trigger.tier.name)
                            put("targetUserIds", people.take(MAX_METADATA_LIST))
                            put("targetCount", people.size)
                            c.assigneeId?.let { put("assigneeId", it) }
                            if (assignmentFailed && trigger == EscalationTrigger.UNASSIGNED) put("reason", "NO_ELIGIBLE_TECHNICIAN")
                        }
                    )
                )
                events.publishEvent(EscalationRecorded(c.incidentId, trigger, people))
                escalated++
            }
        }
        return EscalationRunResult(skipped = false, evaluated = candidates.size, escalated = escalated)
    }

    companion object {
        const val ACTOR = "ESCALATION_MONITOR"
        /** Audit metadata lists hold at most 20 items (foundation.md section 6); the full list is in the escalation log. */
        const val MAX_METADATA_LIST = 20
    }
}

/**
 * Runs [EscalationMonitor] on a fixed delay. On by default; `app.escalation.monitor.enabled=false` turns it off (the test
 * suite does). Enables scheduling itself, so it works even when the SLA schedule is turned off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.escalation.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EscalationSchedulingConfig

@Component
@ConditionalOnProperty(prefix = "app.escalation.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EscalationMonitorSchedule(private val monitor: EscalationMonitor) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Starts after the SLA monitor's first run (30 s), so its first pass already sees fresh SLA facts.
    @Scheduled(
        initialDelayString = "\${app.escalation.monitor.initial-delay-ms:45000}",
        fixedDelayString = "\${app.escalation.monitor.interval-ms:60000}"
    )
    fun tick() {
        try {
            val result = monitor.runOnce()
            if (!result.skipped && result.escalated > 0) log.info("Escalation monitor: {} incidents evaluated, {} escalated", result.evaluated, result.escalated)
        } catch (e: Exception) {
            log.error("Escalation monitor run failed; it will be retried on the next run", e)
        }
    }
}
