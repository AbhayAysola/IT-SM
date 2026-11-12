package com.itsm.config

import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The Kubernetes probe groups. This test environment has PostgreSQL but NO Redis and NO RabbitMQ, which is exactly the situation the
 * probes must survive: liveness and readiness are UP, while the aggregate `/actuator/health` honestly reports the missing optional services.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class HealthProbeGroupsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private lateinit var t: AdminApiTestSupport

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    @Test fun `liveness and readiness are UP and public without details while Redis and RabbitMQ are unavailable`() {
        for (group in listOf("liveness", "readiness")) {
            val body = mvc.perform(get("/actuator/health/$group")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UP"))
                .andReturn().response.contentAsString
            assertFalse(body.contains("components") || body.contains("db") || body.contains("redis"), "$group must not leak details: $body")
        }
    }

    @Test fun `the aggregate health still reports the unavailable optional services`() {
        val admin = t.caller(Role.ADMIN).bearer
        val body = mvc.perform(get("/actuator/health").header("Authorization", admin)).andExpect(status().isServiceUnavailable)
            .andReturn().response.contentAsString
        assertTrue(body.contains("\"redis\"") && body.contains("\"rabbit\""), "aggregate health must still show redis and rabbit: $body")
    }

    @Test fun `readiness requires the database but not Redis or RabbitMQ, liveness requires neither`() {
        val admin = t.caller(Role.ADMIN).bearer
        val readiness = mvc.perform(get("/actuator/health/readiness").header("Authorization", admin)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(readiness.contains("\"db\"") && readiness.contains("\"readinessState\""), "readiness members: $readiness")
        assertFalse(readiness.contains("redis") || readiness.contains("rabbit"), "readiness must not include optional services: $readiness")

        val liveness = mvc.perform(get("/actuator/health/liveness").header("Authorization", admin)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(liveness.contains("\"livenessState\""), "liveness members: $liveness")
        assertFalse(liveness.contains("\"db\"") || liveness.contains("redis") || liveness.contains("rabbit"), "liveness must not include dependencies: $liveness")
    }

    @Test fun `the probe groups do not open any other actuator endpoint`() {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator/health/db")).andExpect(status().isNotFound)   // no per-indicator path is published
    }
}
