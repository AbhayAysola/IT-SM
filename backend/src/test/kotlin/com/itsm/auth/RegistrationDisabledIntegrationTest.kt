package com.itsm.auth

import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** `app.registration.enabled=false` (also the application default): public self-registration does not exist; login is unaffected. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.registration.enabled=false"])
@Testcontainers(disabledWithoutDocker = true)
class RegistrationDisabledIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @SpyBean lateinit var encoder: PasswordEncoder

    private fun register(json: String) = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))

    @Test fun `registration answers the same 404 for a new and an existing address and creates nothing`() {
        val existing = AdminApiTestSupport(users, jwt, jdbc).caller(com.itsm.common.model.Role.EMPLOYEE).email
        val fresh = "d-${UUID.randomUUID().toString().take(8)}@campus.example"
        val before = jdbc.queryForObject("select count(*) from users", Long::class.java)
        val a = register("""{"email":"$existing","password":"Passw0rd!","name":"X"}""").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND")).andReturn().response.contentAsString
        val b = register("""{"email":"$fresh","password":"Passw0rd!","name":"X"}""").andExpect(status().isNotFound).andReturn().response.contentAsString
        assertEquals(AdminApiTestSupport(users, jwt, jdbc).json.readTree(a)["detail"], AdminApiTestSupport(users, jwt, jdbc).json.readTree(b)["detail"])
        assertEquals(before, jdbc.queryForObject("select count(*) from users", Long::class.java))
        verify(encoder, never()).encode(any())
    }

    @Test fun `malformed registration is still a 400 and login still works`() {
        register("{}").andExpect(status().isBadRequest)
        val c = AdminApiTestSupport(users, jwt, jdbc).caller(com.itsm.common.model.Role.EMPLOYEE)
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"${c.email}","password":"wrong-wrong"}""")).andExpect(status().isUnauthorized)
    }
}
