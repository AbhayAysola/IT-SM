package com.itsm.config

import com.itsm.metrics.BusinessMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Actuator on its own port, over real HTTP (a mock request cannot show which port answered). The management port lets Prometheus scrape
 * without credentials and refuses everything that is not actuator; the API port still keeps `/actuator/prometheus` behind ADMIN.
 */
// Spring's test support switches metrics export off unless asked, and there is no /actuator/prometheus without it.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ManagementPortIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        private val managementPort = ServerSocket(0).use { it.localPort }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { managementPort }
        }
    }

    @LocalServerPort var apiPort: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var metrics: BusinessMetrics

    private val http = HttpClient.newHttpClient()
    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test fun `the management port serves health and prometheus without credentials`() {
        assertNotEquals(apiPort, managementPort)
        val live = get(managementPort, "/actuator/health/liveness")
        assertEquals(200, live.statusCode(), "liveness: ${live.body()}")
        val scrape = get(managementPort, "/actuator/prometheus")
        assertEquals(200, scrape.statusCode(), "prometheus: ${scrape.body().take(300)}")
        assertTrue(scrape.body().contains("http_server_requests_seconds"), "request latency metrics are exported")
    }

    @Test fun `the management port refuses everything that is not actuator`() {
        for (path in listOf("/api/users/me", "/api/incidents", "/api/admin/users")) {
            val status = get(managementPort, path).statusCode()
            assertTrue(status == 401 || status == 403 || status == 404, "$path must not be served on the management port, got $status")
        }
    }

    @Test fun `the API port no longer serves prometheus to anyone`() {
        val status = get(apiPort, "/actuator/prometheus").statusCode()
        assertTrue(status == 401 || status == 403 || status == 404, "got $status")
        assertEquals(401, get(apiPort, "/api/users/me").statusCode(), "the API is still protected")
    }

    @Test fun `business metrics report incidents, SLA state, breaches and escalations`() {
        val reporter = jdbc.queryForObject(
            "INSERT INTO users (email, password, name, role) VALUES ('metrics@campus.example', 'x', 'Metrics', 'EMPLOYEE') RETURNING id", Long::class.java
        )!!
        val service = jdbc.queryForObject("INSERT INTO it_services (name, business_criticality) VALUES ('metrics-svc', 3) RETURNING id", Long::class.java)!!
        jdbc.update(
            "INSERT INTO incidents (id, title, description, state, reporter_id, category_id, service_id, priority) " +
                "VALUES ('INC9000001', 'Metric', 'x', 'CLASSIFIED', ?, 1, ?, 'P1')", reporter, service
        )
        jdbc.update(
            "INSERT INTO incident_sla (incident_id, priority, service_id, clock_start, no_policy_reason, response_status, resolution_status, status, response_breached_at) " +
                "VALUES ('INC9000001', 'P1', ?, now(), 'MISSING_SLA_CONFIG', 'BREACHED', 'NO_POLICY', 'BREACHED', now())", service
        )
        metrics.refresh()
        val body = get(managementPort, "/actuator/prometheus").body()
        fun value(prefix: String) = body.lineSequence().firstOrNull { it.startsWith(prefix) }?.substringAfterLast(' ')?.toDouble()
        assertTrue((value("itsm_incidents{state=\"CLASSIFIED\"") ?: 0.0) >= 1.0, body.lines().filter { it.startsWith("itsm_") }.joinToString("\n"))
        assertTrue((value("itsm_incidents_open{priority=\"P1\"") ?: 0.0) >= 1.0)
        assertTrue((value("itsm_sla_open{status=\"BREACHED\"") ?: 0.0) >= 1.0)
        assertTrue((value("itsm_sla_breaches") ?: 0.0) >= 1.0)
        assertTrue(body.contains("itsm_escalations"))
    }
}
