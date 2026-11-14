package com.itsm.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * What the operations dashboard shows about the service itself rather than the JVM: how many incidents are open, where they are
 * stuck, how the SLA clocks stand and how many breaches and escalations have happened. Exposed on `/actuator/prometheus`.
 *
 * The numbers come from the same tables the application owns, read on a timer (not on every scrape), so a Prometheus scrape never
 * touches the database and a slow query cannot slow it down. Reads only: nothing here changes any state. A failed refresh keeps
 * the previous values and logs once per failure; it never affects the application.
 *
 * | Metric | Labels | Meaning |
 * |---|---|---|
 * | `itsm_incidents` | `state` | all incidents per lifecycle state |
 * | `itsm_incidents_open` | `priority` | incidents not yet verified or closed, per priority (`UNSET` while unclassified) |
 * | `itsm_sla_open` | `status` | SLA records of incidents still being worked, per status (`BREACHED`, `WARNING`, ...) |
 * | `itsm_sla_breaches` | none | incidents that have ever breached a response or resolution target |
 * | `itsm_escalations` | none | escalations recorded so far |
 *
 * They are gauges of current counts (not counters) because the database, not this process, is the source of truth: they stay
 * correct across restarts and across replicas.
 */
@Component
@ConditionalOnProperty(name = ["app.metrics.business.enabled"], havingValue = "true", matchIfMissing = true)
class BusinessMetrics(
    private val jdbc: JdbcTemplate,
    registry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val byState = MultiGauge.builder("itsm_incidents").description("Incidents per lifecycle state").register(registry)
    private val openByPriority = MultiGauge.builder("itsm_incidents_open").description("Open incidents per priority").register(registry)
    private val slaOpen = MultiGauge.builder("itsm_sla_open").description("SLA records of incidents still in progress, per status").register(registry)
    private val breaches = AtomicLong()
    private val escalations = AtomicLong()

    init {
        Gauge.builder("itsm_sla_breaches", breaches) { it.get().toDouble() }
            .description("Incidents that have breached a response or resolution target").strongReference(true).register(registry)
        Gauge.builder("itsm_escalations", escalations) { it.get().toDouble() }
            .description("Escalations recorded").strongReference(true).register(registry)
    }

    @Scheduled(fixedDelayString = "\${app.metrics.business.refresh-ms:15000}", initialDelayString = "\${app.metrics.business.initial-delay-ms:15000}")
    fun refresh() {
        try {
            byState.register(counts("SELECT state, count(*) FROM incidents GROUP BY state", "state"), true)
            openByPriority.register(
                counts(
                    "SELECT COALESCE(priority, 'UNSET'), count(*) FROM incidents WHERE state NOT IN ('VERIFIED', 'CLOSED') GROUP BY 1",
                    "priority"
                ),
                true
            )
            slaOpen.register(counts("SELECT status, count(*) FROM incident_sla WHERE NOT complete GROUP BY status", "status"), true)
            breaches.set(scalar("SELECT count(*) FROM incident_sla WHERE response_breached_at IS NOT NULL OR resolution_breached_at IS NOT NULL"))
            escalations.set(scalar("SELECT count(*) FROM incident_escalations"))
        } catch (e: Exception) {
            // Keep the last values; a database blip must not turn into an application problem.
            log.warn("Business metrics refresh failed, keeping the previous values: {}", e.javaClass.simpleName)
        }
    }

    private fun counts(sql: String, label: String): List<MultiGauge.Row<Number>> =
        jdbc.query(sql) { rs, _ -> MultiGauge.Row.of(Tags.of(label, rs.getString(1)), rs.getLong(2) as Number) }

    private fun scalar(sql: String): Long = jdbc.queryForObject(sql, Long::class.java) ?: 0L
}
