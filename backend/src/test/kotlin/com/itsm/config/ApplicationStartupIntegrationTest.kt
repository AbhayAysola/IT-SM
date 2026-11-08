package com.itsm.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.DatabaseErrorClassifier
import com.itsm.common.error.ErrorCode
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Full Spring Boot startup against a real PostgreSQL 16: Flyway runs, Hibernate validates the schema,
 * the security wiring (incl. the @Lazy cycle fix) loads, and the existing auth flow works over MockMvc.
 * Redis/RabbitMQ are not needed for startup (their connections are lazy).
 * Skipped (not failed) when Docker is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationStartupIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var flyway: Flyway
    private val json = ObjectMapper()

    private fun register(email: String) = mvc.perform(
        post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"Passw0rd!","name":"Test"}""")
    )

    @Test fun `application starts on PostgreSQL 16 with the baseline migration applied`() {
        assertEquals("1", flyway.info().current().version.version)
    }

    @Test fun `register, duplicate register, login and me work end to end`() {
        register("flow@x.com").andExpect(status().isOk)

        register("flow@x.com")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
            .andExpect(jsonPath("$.detail").value("Email is already registered"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)

        val token = json.readTree(
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"flow@x.com","password":"Passw0rd!"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString
        )["token"].asText()

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.email").value("flow@x.com"))
        mvc.perform(get("/api/users/me"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test fun `a duplicate that bypasses the service check is caught by the unique constraint as DUPLICATE_RESOURCE`() {
        users.saveAndFlush(UserEntity(email = "race@x.com", password = "h", name = "A"))
        val ex = assertThrows<DataIntegrityViolationException> {
            users.saveAndFlush(UserEntity(email = "race@x.com", password = "h", name = "B"))
        }
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, DatabaseErrorClassifier.classify(ex))
    }
}
