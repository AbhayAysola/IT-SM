package com.itsm.sla

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs [SlaMonitor] on a fixed delay. On by default; `app.sla.monitor.enabled=false` turns it off (the test suite does,
 * so background runs never write audit rows other tests count; tests call the monitor directly instead).
 *
 * Scheduling is enabled here, inside P4's module, so `ItSmApplication` is untouched. This application has no other
 * `@Scheduled` methods, so enabling it changes nothing else.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.sla.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SlaSchedulingConfig

@Component
@ConditionalOnProperty(prefix = "app.sla.monitor", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SlaMonitorSchedule(private val monitor: SlaMonitor) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${app.sla.monitor.initial-delay-ms:30000}",
        fixedDelayString = "\${app.sla.monitor.interval-ms:60000}"
    )
    fun tick() {
        try {
            val result = monitor.runOnce()
            if (!result.skipped && result.events > 0) {
                log.info("SLA monitor: {} incidents evaluated, {} written, {} events", result.evaluated, result.written, result.events)
            }
        } catch (e: Exception) {
            // The run rolled back; the next tick retries everything. Never let one failure stop the schedule.
            log.error("SLA monitor run failed; it will be retried on the next run", e)
        }
    }
}
