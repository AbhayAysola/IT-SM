package com.itsm.sla

import com.itsm.common.error.MissingSlaConfiguration
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.SlaPolicyProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What one run did. */
data class SlaRunResult(val skipped: Boolean, val evaluated: Int = 0, val written: Int = 0, val events: Int = 0) {
    companion object {
        val SKIPPED = SlaRunResult(skipped = true)
    }
}

/**
 * Evaluates every tracked incident's SLA (docs/architecture/p4.md section 2): starts tracking newly classified incidents,
 * records pauses, resumes and stops, raises warnings and breaches, and publishes one audit event per change, as
 * `AuditActor.system("SLA_MONITOR")`.
 *
 * One run is one transaction holding a PostgreSQL advisory lock, so concurrent runs (two instances) never both evaluate.
 * The audit rows commit with the SLA rows or not at all. `MissingSlaConfiguration` and `ServiceNotFound` are answers
 * from P1's provider (recorded as `NO_POLICY`, retried on the next run); any other failure rolls the run back and the
 * next run retries everything.
 *
 * Scheduled by [SlaMonitorSchedule]; tests call [runAt] directly with a chosen time.
 */
@Component
class SlaMonitor(
    private val incidents: IncidentSnapshotReader,
    private val store: SlaStore,
    private val policies: SlaPolicyProvider,
    private val audit: AuditPublisher,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
    @Value("\${app.sla.monitor.batch-size:500}") private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(): SlaRunResult = runAt(Instant.now(clock))

    @Transactional
    fun runAt(time: Instant): SlaRunResult {
        if (!store.tryMonitorLock()) return SlaRunResult.SKIPPED
        val now = time.truncatedTo(ChronoUnit.MICROS)
        val snapshots = incidents.findTracked(batchSize)
        val records = store.findAll(snapshots.map { it.incidentId })
        var written = 0
        var published = 0
        for (snapshot in snapshots) {
            val previous = records[snapshot.incidentId]
            val lookup = if (needsLookup(previous, snapshot)) lookup(snapshot) else null
            val evaluation = SlaEvaluator.evaluate(previous, snapshot, lookup, now)
            if (evaluation.record != previous) {
                store.save(evaluation.record, previous, now)
                written++
            }
            evaluation.events.forEach { audit.publish(it.toAudit(evaluation.record)) }
            evaluation.events.mapNotNull { it.toNotice(evaluation.record) }.forEach(events::publishEvent)
            published += evaluation.events.size
        }
        if (snapshots.size == batchSize) log.info("SLA monitor reached its batch size ({}); the rest is evaluated next run", batchSize)
        return SlaRunResult(skipped = false, evaluated = snapshots.size, written = written, events = published)
    }

    /** A lookup is needed to start tracking, to retry a missing policy, and when the priority or service changed. */
    private fun needsLookup(previous: SlaRecord?, s: IncidentSnapshot) =
        previous == null || previous.targets == null || previous.priority != s.priority || previous.serviceId != s.serviceId

    private fun lookup(s: IncidentSnapshot): TargetLookup = try {
        val p = policies.resolve(s.serviceId, s.priority)
        TargetLookup.Found(
            SlaTargets(
                policyId = p.id,
                scope = if (p.serviceId == null) PolicyScope.GLOBAL else PolicyScope.SERVICE,
                responseMinutes = p.responseMinutes,
                resolutionMinutes = p.resolutionMinutes,
                warningThresholdPct = p.warningThresholdPct
            )
        )
    } catch (e: MissingSlaConfiguration) {
        TargetLookup.Missing(NoPolicyReason.MISSING_SLA_CONFIG)
    } catch (e: ServiceNotFound) {
        TargetLookup.Missing(NoPolicyReason.SERVICE_NOT_FOUND)
    }

    private fun SlaEvent.toAudit(r: SlaRecord): AuditableEvent {
        val clockName = clock?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
        val description = when (type) {
            SlaEvent.Type.STARTED -> "SLA tracking started for ${r.priority.name}"
            SlaEvent.Type.RECALCULATED -> "SLA deadlines recalculated for ${r.priority.name}"
            SlaEvent.Type.POLICY_MISSING -> "No SLA policy applies to this incident ($detail)"
            SlaEvent.Type.PAUSED -> "Resolution SLA paused while waiting on the reporter"
            SlaEvent.Type.RESUMED -> if (detail == SlaEvaluator.REOPEN) "Resolution SLA resumed after a reopen" else "Resolution SLA resumed"
            SlaEvent.Type.WARNING -> "$clockName SLA warning: deadline ${dueOf(r)}"
            SlaEvent.Type.BREACHED -> "$clockName SLA breached at ${dueOf(r)}"
            SlaEvent.Type.MET -> "$clockName SLA met"
        }
        val metadata = buildMap<String, Any> {
            clock?.let { put("clock", it.name) }
            put("priority", r.priority.name)
            put("at", at.toString())
            r.targets?.let { put("policyId", it.policyId); put("policyScope", it.scope.name) }
            dueOf(r)?.let { put("dueAt", it.toString()) }
            detail?.let { put("reason", it) }
        }
        return AuditableEvent(
            actor = AuditActor.system(ACTOR), action = type.auditAction, entityType = ENTITY_TYPE,
            entityId = r.incidentId, incidentId = r.incidentId, description = description, metadata = metadata
        )
    }

    /** The events people are told about (notifications); the rest are only audited. */
    private fun SlaEvent.toNotice(r: SlaRecord): SlaNotice? = when (type) {
        SlaEvent.Type.WARNING -> SlaNotice(r.incidentId, SlaNotice.Kind.WARNING, clock, dueOf(r))
        SlaEvent.Type.BREACHED -> SlaNotice(r.incidentId, SlaNotice.Kind.BREACHED, clock, dueOf(r))
        SlaEvent.Type.POLICY_MISSING -> SlaNotice(r.incidentId, SlaNotice.Kind.POLICY_MISSING, null, null)
        else -> null
    }

    private fun SlaEvent.dueOf(r: SlaRecord): Instant? = when (clock) {
        SlaClock.RESPONSE -> r.responseDueAt
        SlaClock.RESOLUTION -> r.resolutionDueAt
        null -> null
    }

    companion object {
        const val ACTOR = "SLA_MONITOR"
        const val ENTITY_TYPE = "INCIDENT_SLA"
    }
}
