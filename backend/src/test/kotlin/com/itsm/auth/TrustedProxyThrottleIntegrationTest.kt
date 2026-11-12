package com.itsm.auth

import com.itsm.common.model.Role
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * One trusted proxy hop (an ingress at 10.0.0.0/8): the throttle is charged to the address the proxy APPENDED to
 * X-Forwarded-For, not to the proxy and not to anything the client forged on the left of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.security.trusted-proxy-hops=1", "app.security.trusted-proxies=10.0.0.0/8", "app.rate-limit.login-pair.max-failures=3"])
@Testcontainers(disabledWithoutDocker = true)
class TrustedProxyThrottleIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var encoder: PasswordEncoder

    private fun account(): String {
        val e = "p-${UUID.randomUUID().toString().take(8)}@campus.example"
        users.save(UserEntity(email = e, password = encoder.encode("Passw0rd!"), name = "P", role = Role.EMPLOYEE.name))
        return e
    }

    private fun login(email: String, password: String, peer: String, xff: String): ResultActions =
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$password"}""")
            .with { r -> r.remoteAddr = peer; r }.header("X-Forwarded-For", xff))

    @Test fun `from a trusted proxy the client is the appended address, so forged left-hand entries share one bucket`() {
        val e = account()
        repeat(3) { n -> login(e, "wrong-wrong", "10.8.0.1", "forged-$n.example, 9.9.9.$n, 203.0.113.9").andExpect(status().isUnauthorized) }
        login(e, "wrong-wrong", "10.8.0.1", "6.6.6.6, 203.0.113.9").andExpect(status().isTooManyRequests)
        login(e, "Passw0rd!", "10.8.0.2", "1.2.3.4, 203.0.113.9").andExpect(status().isTooManyRequests)   // another proxy replica, same client
    }

    @Test fun `different clients behind the same proxy do not share a bucket`() {
        val e = account()
        repeat(3) { login(e, "wrong-wrong", "10.8.0.1", "203.0.113.9").andExpect(status().isUnauthorized) }
        login(e, "wrong-wrong", "10.8.0.1", "203.0.113.9").andExpect(status().isTooManyRequests)
        login(e, "Passw0rd!", "10.8.0.1", "203.0.113.77").andExpect(status().isOk)
    }

    @Test fun `a peer that is not a trusted proxy cannot choose its bucket with the header`() {
        val e = account()
        repeat(3) { n -> login(e, "wrong-wrong", "198.51.100.5", "203.0.113.$n").andExpect(status().isUnauthorized) }
        login(e, "Passw0rd!", "198.51.100.5", "203.0.113.200").andExpect(status().isTooManyRequests)
    }
}
