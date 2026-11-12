package com.itsm.auth

import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** Local HTTP development: the `dev` profile plus an explicit `app.auth.cookie.secure=false`. Everything else about the cookie is unchanged. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = ["app.auth.cookie.secure=false"])
@Testcontainers(disabledWithoutDocker = true)
class WebSessionDevProfileIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var encoder: PasswordEncoder

    @Test fun `the dev cookie has no Secure attribute but is still HttpOnly, Strict and Path-api`() {
        val email = "d-${UUID.randomUUID().toString().take(8)}@campus.example"
        users.save(UserEntity(email = email, password = encoder.encode("Passw0rd!"), name = "D", role = "EMPLOYEE"))
        val header = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("Origin", "http://localhost:3000").header("X-ITSM-Client", "web")
            .content("""{"email":"$email","password":"Passw0rd!"}""")).andExpect(status().isOk).andReturn().response.getHeader(HttpHeaders.SET_COOKIE)!!
        assertFalse("Secure" in header, header)
        for (attr in listOf("HttpOnly", "SameSite=Strict", "Path=/api", "Max-Age=")) assertTrue(attr in header, "$attr missing in $header")
    }
}
