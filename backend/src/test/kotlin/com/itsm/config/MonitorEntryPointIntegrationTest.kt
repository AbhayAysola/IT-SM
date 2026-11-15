package com.itsm.config

import com.itsm.escalation.EscalationMonitor
import com.itsm.notification.NotificationMonitor
import com.itsm.priority.PrioritySuggestionMonitor
import com.itsm.sla.SlaMonitor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The schedules call each monitor's `runOnce()`, which calls `runAt()` on `this`. That self-call bypasses Spring's proxy, so
 * `runOnce()` itself must open the transaction the `MANDATORY` stores require. The other monitor tests call `runAt()` through
 * the proxy and would not notice if it did not: this one goes through the same entry point as production.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MonitorEntryPointIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var sla: SlaMonitor
    @Autowired lateinit var escalation: EscalationMonitor
    @Autowired lateinit var notification: NotificationMonitor
    @Autowired lateinit var priority: PrioritySuggestionMonitor

    @Test fun `the SLA monitor runs from its scheduled entry point`() { assertDoesNotThrow { sla.runOnce() } }

    @Test fun `the escalation monitor runs from its scheduled entry point`() { assertDoesNotThrow { escalation.runOnce() } }

    @Test fun `the notification monitor runs from its scheduled entry point`() { assertDoesNotThrow { notification.runOnce() } }

    @Test fun `the priority suggestion monitor runs from its scheduled entry point`() { assertDoesNotThrow { priority.runOnce() } }
}
