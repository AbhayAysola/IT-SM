package com.itsm.sla

import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.TechnicianWorkloadProvider
import com.itsm.common.spi.UserDirectory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Raises predictive SLA risk (docs/architecture/p4.md section 2): for every open incident whose resolution clock is
 * running, asks [SlaRiskModel] when it will probably be resolved, and if that is after the deadline records it once
 * (V43), audits `SLA_RISK_PREDICTED` and publishes an [SlaNotice] of kind `AT_RISK`, which notifications turn into a
 * message for the assignee (or the admins when nobody has it).
 *
 * Part of every SLA sweep, inside its transaction and advisory lock, after the clocks are evaluated: a clock that has just
 * reached its fixed warning is in `WARNING` and is no longer a candidate, so people are not told twice. Assignee load comes
 * from P3's [TechnicianWorkloadProvider] and P1's [UserDirectory]; without either, forecasts are made without it.
 */
@Component
class SlaRiskDetector(
    private val store: SlaRiskStore,
    private val audit: AuditPublisher,
    private val events: ApplicationEventPublisher,
    private val workload: ObjectProvider<TechnicianWorkloadProvider>,
    private val directory: ObjectProvider<UserDirectory>,
    @Value("\${app.sla.risk.batch-size:500}") private val batchSize: Int,
    @Value("\${app.sla.risk.history-days:180}") private val historyDays: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns how many risks were raised. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun detect(now: Instant): Int {
        val candidates = store.candidates(batchSize)
        if (candidates.isEmpty()) return 0
        val history = store.history(candidates.map { it.priority }.toSet(), now.minus(Duration.ofDays(historyDays)))
        val byPriority = history.groupBy { it.priority }
        val loads = loads(candidates.mapNotNull { it.assigneeId }.toSet())
        var raised = 0
        for (c in candidates) {
            val samePriority = byPriority[c.priority].orEmpty()
            val subject = RiskSubject(
                elapsedMinutes = Duration.between(c.clockStart, now).minusSeconds(c.pausedSeconds).toMinutes().coerceAtLeast(0),
                dueAt = c.resolutionDueAt,
                sameCategory = if (c.categoryId == null) emptyList() else samePriority.filter { it.categoryId == c.categoryId }.map { it.minutes },
                samePriority = samePriority.map { it.minutes },
                load = c.assigneeId?.let(loads::get)
            )
            val forecast = SlaRiskModel.forecast(subject, now) ?: continue
            if (!forecast.lateFor(c.resolutionDueAt)) continue
            val risk = SlaRisk(
                incidentId = c.incidentId, raisedAt = now, dueAt = c.resolutionDueAt, predictedFinishAt = forecast.predictedFinishAt,
                basis = forecast.basis, sampleSize = forecast.sampleSize, typicalRemainingMinutes = forecast.typicalRemainingMinutes,
                loadFactor = forecast.loadFactor, assigneeId = c.assigneeId
            )
            store.record(risk)
            audit.publish(risk.toAudit(c))
            events.publishEvent(SlaNotice(c.incidentId, SlaNotice.Kind.AT_RISK, SlaClock.RESOLUTION, c.resolutionDueAt, forecast.predictedFinishAt))
            raised++
        }
        if (candidates.size == batchSize) log.info("SLA risk detection reached its batch size ({}); the rest is assessed next run", batchSize)
        return raised
    }

    /** Active incidents / configured ceiling per assignee; empty when P1's or P3's side is not available. */
    private fun loads(assignees: Set<Long>): Map<Long, Double> {
        if (assignees.isEmpty()) return emptyMap()
        val counts = workload.getIfAvailable()?.activeIncidentCounts(assignees) ?: return emptyMap()
        val users = directory.getIfAvailable() ?: return emptyMap()
        return assignees.mapNotNull { id ->
            val ceiling = users.findTechnician(id)?.maxWorkload?.takeIf { it > 0 } ?: return@mapNotNull null
            id to (counts[id] ?: 0).toDouble() / ceiling
        }.toMap()
    }

    private fun SlaRisk.toAudit(c: RiskCandidate) = AuditableEvent(
        actor = AuditActor.system(SlaMonitor.ACTOR), action = AUDIT_ACTION, entityType = SlaMonitor.ENTITY_TYPE,
        entityId = incidentId, incidentId = incidentId,
        description = "Resolution SLA predicted to be missed: likely resolved around $predictedFinishAt, due $dueAt",
        metadata = buildMap {
            put("clock", SlaClock.RESOLUTION.name)
            put("priority", c.priority.name)
            put("dueAt", dueAt.toString())
            put("predictedFinishAt", predictedFinishAt.toString())
            put("basis", basis.name)
            put("sampleSize", sampleSize)
            put("typicalRemainingMinutes", typicalRemainingMinutes)
            put("loadFactor", loadFactor)
            assigneeId?.let { put("assigneeId", it) }
        }
    )

    companion object {
        const val AUDIT_ACTION = "SLA_RISK_PREDICTED"
    }
}
