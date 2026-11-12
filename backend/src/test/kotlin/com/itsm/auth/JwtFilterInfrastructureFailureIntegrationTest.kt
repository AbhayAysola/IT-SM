package com.itsm.auth

import com.itsm.admin.AdminApiTestSupport
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Through the real filter chain: bad credentials stay 401, but a failing user lookup is a 503, not a fake 401. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class JwtFilterInfrastructureFailureIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @SpyBean lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `invalid credentials are 401 while a database failure during validation is 503 and leaks nothing`() {
        val caller = AdminApiTestSupport(users, jwt, jdbc).caller(Role.EMPLOYEE)

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwt.generateToken(caller.id, 99))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/users/me").header("Authorization", caller.bearer)).andExpect(status().isOk)

        doThrow(DataAccessResourceFailureException("jdbc:postgresql://db-internal:5432/itsm password=hunter2"))
            .`when`(users).findById(caller.id)
        try {
            val body = mvc.perform(get("/api/users/me").header("Authorization", caller.bearer))
                .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty)
                .andReturn().response.contentAsString
            assertFalse(body.contains("db-internal") || body.contains("hunter2") || body.contains("jdbc"), body)
        } finally {
            reset(users)
        }
        mvc.perform(get("/api/users/me").header("Authorization", caller.bearer)).andExpect(status().isOk)
    }
}
