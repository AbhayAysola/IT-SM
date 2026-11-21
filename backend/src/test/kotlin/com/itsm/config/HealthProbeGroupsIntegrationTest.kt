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
 * The Kubernetes probe groups: liveness is the process alone, readiness adds PostgreSQL, and neither leaks details to the public.
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

    @Test fun `liveness and readiness are UP and public without details`() {
        for (group in listOf("liveness", "readiness")) {
            val body = mvc.perform(get("/actuator/health/$group")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UP"))
                .andReturn().response.contentAsString
            assertFalse(body.contains("components") || body.contains("db"), "$group must not leak details: $body")
        }
    }

    @Test fun `the aggregate health is UP and shows the database to an admin`() {
        val admin = t.caller(Role.ADMIN).bearer
        val body = mvc.perform(get("/actuator/health").header("Authorization", admin)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(body.contains("\"db\"") && body.contains("\"status\":\"UP\""), "aggregate health: $body")
    }

    @Test fun `readiness requires the database, liveness requires no dependency`() {
        val admin = t.caller(Role.ADMIN).bearer
        val readiness = mvc.perform(get("/actuator/health/readiness").header("Authorization", admin)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(readiness.contains("\"db\"") && readiness.contains("\"readinessState\""), "readiness members: $readiness")

        val liveness = mvc.perform(get("/actuator/health/liveness").header("Authorization", admin)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(liveness.contains("\"livenessState\""), "liveness members: $liveness")
        assertFalse(liveness.contains("\"db\""), "liveness must not include dependencies: $liveness")
    }

    @Test fun `the probe groups do not open any other actuator endpoint`() {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator/health/db")).andExpect(status().isNotFound)   // no per-indicator path is published
    }
}
