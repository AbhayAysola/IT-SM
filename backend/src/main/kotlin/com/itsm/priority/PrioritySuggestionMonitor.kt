package com.itsm.priority

import com.itsm.common.api.ApiPaths
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Priority
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.PriorityPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Published inside the monitor's transaction; P4's notifications tell every active admin. */
data class PrioritySuggested(val incidentId: String, val current: Priority, val suggested: Priority, val reasons: List<String>)

data class PrioritySuggestionRunResult(val skipped: Boolean, val evaluated: Int = 0, val suggested: Int = 0, val closed: Int = 0)

/**
 * Suggests a new priority for open incidents whose priority looks out of date ([PrioritySuggestionRules]), records each
 * once (V45), audits `PRIORITY_SUGGESTED` as `AuditActor.system("PRIORITY_MONITOR")` and publishes [PrioritySuggested].
 * Closes suggestions the incident has moved past first. It never changes a priority itself: an admin applies a
 * suggestion through P2's priority endpoint, or dismisses it. One run is one transaction under its own advisory lock.
 */
@Component
class PrioritySuggestionMonitor(
    private val store: PrioritySuggestionStore,
    private val policy: PriorityPolicy,
    private val audit: AuditPublisher,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${app.priority.monitor.batch-size:500}") private val batchSize: Int,
    @Value("\${app.priority.monitor.duplicate-window-hours:24}") private val duplicateWindowHours: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(): PrioritySuggestionRunResult = runAt(Instant.now(clock))

    @Transactional
    fun runAt(time: Instant): PrioritySuggestionRunResult {
        if (!store.tryMonitorLock()) return PrioritySuggestionRunResult(skipped = true)
        val now = time.truncatedTo(ChronoUnit.MICROS)
        val closed = store.closeResolved(now)
        val candidates = store.candidates(now.minus(Duration.ofHours(duplicateWindowHours)), batchSize)
        var suggested = 0
        for (c in candidates) {
            val assessed = if (c.impact != null && c.urgency != null) {
                try { policy.assess(c.serviceId, c.impact, c.urgency) } catch (e: ServiceNotFound) { null }
            } else null
            val s = PrioritySuggestionRules.suggest(SuggestionInput(c.priority, assessed, c.manuallyChanged, c.duplicateReports)) ?: continue
            if (store.wasDismissed(c.incidentId, s.priority)) continue
            store.insert(c.incidentId, c.priority, s, now)
            audit.publish(
                AuditableEvent(
                    actor = AuditActor.system(ACTOR), action = "PRIORITY_SUGGESTED", entityType = "INCIDENT",
                    entityId = c.incidentId, incidentId = c.incidentId,
                    description = "Priority ${s.priority.name} suggested (now ${c.priority.name})",
                    metadata = mapOf("priority" to c.priority.name, "suggestedPriority" to s.priority.name, "rules" to s.rules.map { it.name })
                )
            )
            events.publishEvent(PrioritySuggested(c.incidentId, c.priority, s.priority, s.reasons))
            suggested++
        }
        if (candidates.size == batchSize) log.info("Priority monitor reached its batch size ({}); the rest is evaluated next run", batchSize)
        return PrioritySuggestionRunResult(skipped = false, evaluated = candidates.size, suggested = suggested, closed = closed)
    }

    companion object {
        const val ACTOR = "PRIORITY_MONITOR"
    }
}

/** Runs [PrioritySuggestionMonitor] on a fixed delay; `app.priority.monitor.enabled=false` turns it off (the test suite does). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.priority.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PrioritySchedulingConfig

@Component
@ConditionalOnProperty(prefix = "app.priority.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PrioritySuggestionSchedule(private val monitor: PrioritySuggestionMonitor) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${app.priority.monitor.initial-delay-ms:60000}",
        fixedDelayString = "\${app.priority.monitor.interval-ms:300000}"
    )
    fun tick() {
        try {
            val r = monitor.runOnce()
            if (!r.skipped && r.suggested > 0) log.info("Priority monitor: {} incidents evaluated, {} suggestions", r.evaluated, r.suggested)
        } catch (e: Exception) {
            log.error("Priority monitor run failed; it will be retried on the next run", e)
        }
    }
}

data class PrioritySuggestionDto(
    val incidentId: String, val currentPriority: String, val suggestedPriority: String,
    val rules: List<String>, val reasons: List<String>, val createdAt: Instant
) {
    companion object {
        fun of(s: StoredSuggestion) = PrioritySuggestionDto(
            s.incidentId, s.currentPriority.name, s.suggestedPriority.name, s.rules.map { it.name }, s.reasons, s.createdAt
        )
    }
}

@Service
class PrioritySuggestionService(
    private val store: PrioritySuggestionStore,
    private val audit: AuditPublisher,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun current(incidentId: String): PrioritySuggestionDto? = store.openFor(incidentId)?.let(PrioritySuggestionDto::of)

    @Transactional
    fun dismiss(incidentId: String, userId: Long, actor: AuditActor) {
        val s = store.dismiss(incidentId, userId, Instant.now(clock).truncatedTo(ChronoUnit.MICROS))
            ?: throw ResourceNotFound("Priority suggestion", incidentId)
        audit.publish(
            AuditableEvent(
                actor = actor, action = "PRIORITY_SUGGESTION_DISMISSED", entityType = "INCIDENT", entityId = incidentId, incidentId = incidentId,
                description = "Priority suggestion ${s.suggestedPriority.name} dismissed",
                metadata = mapOf("priority" to s.currentPriority.name, "suggestedPriority" to s.suggestedPriority.name)
            )
        )
    }
}

/** Admins (`INCIDENT_ASSIGN`) read and dismiss suggestions; applying one is P2's `POST /api/incidents/{id}/priority`. */
@RestController
@RequestMapping(ApiPaths.INCIDENTS)
class PrioritySuggestionController(private val service: PrioritySuggestionService, private val currentUser: CurrentUserProvider) {

    /** 200 with the open suggestion, or 204 when there is none. */
    @GetMapping("/{id}/priority-suggestion")
    @PreAuthorize("hasAuthority('INCIDENT_ASSIGN')")
    fun current(@PathVariable id: String): ResponseEntity<PrioritySuggestionDto> =
        service.current(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    /** 204; 404 when there is no open suggestion. A dismissed priority is not suggested again for this incident. */
    @PostMapping("/{id}/priority-suggestion/dismiss")
    @PreAuthorize("hasAuthority('INCIDENT_ASSIGN')")
    fun dismiss(@PathVariable id: String): ResponseEntity<Void> {
        val user = currentUser.current()
        service.dismiss(id, user.id, AuditActor.of(user))
        return ResponseEntity.noContent().build()
    }
}
