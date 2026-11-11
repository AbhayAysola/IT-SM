package com.itsm.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * B14a: login and registration throttles through the real filter chain, on PostgreSQL 16. Limits are lowered
 * (pair 3, address 6, registration 3 per window) so the real limiter, with the real system clock, is exercised without
 * waiting; window expiry, capacity and concurrency are covered by the fake-clock unit tests in `auth.ratelimit`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "app.rate-limit.login-pair.max-failures=3", "app.rate-limit.login-ip.max-failures=6",
    "app.rate-limit.register-ip.max-attempts=3", "app.registration.enabled=true"
])
@Testcontainers(disabledWithoutDocker = true)
class AuthThrottleIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        const val PASSWORD = "Passw0rd!"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @SpyBean lateinit var encoder: PasswordEncoder

    private lateinit var t: AdminApiTestSupport
    private val throttleLog = LoggerFactory.getLogger("com.itsm.security.throttle") as Logger
    private val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        passwordHash   // computed once, before any test clears or verifies encoder invocations
        appender.list.clear(); appender.start(); throttleLog.addAppender(appender); throttleLog.level = Level.INFO
    }

    @AfterEach fun tearDown() { throttleLog.detachAppender(appender) }

    private fun email() = "t-${UUID.randomUUID().toString().take(8)}@campus.example"

    /** A real account, created directly (registration is itself throttled, and has its own tests below). */
    private val passwordHash by lazy { encoder.encode(PASSWORD) }

    private fun account(): String {
        val e = email()
        users.save(com.itsm.user.UserEntity(email = e, password = passwordHash, name = "T", role = Role.EMPLOYEE.name))
        return e
    }

    private fun login(email: String, password: String, ip: String? = null, xff: String? = null): ResultActions =
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$password"}""")
            .with { r -> ip?.let { r.remoteAddr = it }; r }.apply { if (xff != null) header("X-Forwarded-For", xff) })

    private fun register(body: String, ip: String? = null): ResultActions =
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body).with { r -> ip?.let { r.remoteAddr = it }; r })

    private fun regBody(email: String, password: String = PASSWORD, name: String = "T") = """{"email":"$email","password":"$password","name":"$name"}"""

    // --- login ---------------------------------------------------------------------------------------------------------

    @Test fun `a locked-out caller is refused even with the correct password, with a generic 429 and a Retry-After`() {
        val e = account()
        repeat(3) { login(e, "wrong-wrong", ip = "10.1.0.1").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")) }
        val r = login(e, PASSWORD, ip = "10.1.0.1").andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(jsonPath("$.token").doesNotExist())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER)).andReturn().response
        val seconds = r.getHeader(HttpHeaders.RETRY_AFTER)!!.toLong()
        assertTrue(seconds in 1..900, "Retry-After $seconds")
        // generic: no scope, no counts, no email, no hint which limit fired
        val body = r.contentAsString
        listOf("login-pair", "login-ip", "pair", "address", e, "failed", "attempts remaining").forEach { assertFalse(body.contains(it, ignoreCase = true), "leaked '$it': $body") }
    }

    @Test fun `known and unknown accounts are throttled identically`() {
        val known = account(); val unknown = email()
        repeat(3) { login(known, "wrong-wrong", ip = "10.1.0.2"); login(unknown, "wrong-wrong", ip = "10.1.0.3") }
        val a = login(known, "wrong-wrong", ip = "10.1.0.2").andExpect(status().isTooManyRequests).andReturn().response
        val b = login(unknown, "wrong-wrong", ip = "10.1.0.3").andExpect(status().isTooManyRequests).andReturn().response
        fun shape(r: org.springframework.mock.web.MockHttpServletResponse) =
            t.json.readTree(r.contentAsString).let { listOf(it["status"], it["code"], it["detail"], it["title"], it["type"]).map { n -> n.asText() } } +
                r.headerNames.sorted()
        assertEquals(shape(a), shape(b))
    }

    @Test fun `a throttled attempt does no password hashing at all`() {
        val e = account()
        repeat(3) { login(e, "wrong-wrong", ip = "10.1.0.4") }
        clearInvocations(encoder)
        repeat(5) { login(e, PASSWORD, ip = "10.1.0.4").andExpect(status().isTooManyRequests) }
        verify(encoder, never()).matches(any(), any())
        verify(encoder, never()).encode(any())
    }

    @Test fun `the same account from another address, and another account from this address, are not locked out`() {
        val e = account(); val other = account()
        repeat(3) { login(e, "wrong-wrong", ip = "10.1.0.5") }
        login(e, "wrong-wrong", ip = "10.1.0.5").andExpect(status().isTooManyRequests)
        login(e, PASSWORD, ip = "10.1.0.6").andExpect(status().isOk)               // the real user, elsewhere
        login(other, PASSWORD, ip = "10.1.0.5").andExpect(status().isOk)           // someone else behind the same address
    }

    @Test fun `a successful login clears that pair's failures`() {
        val e = account()
        repeat(2) { login(e, "wrong-wrong", ip = "10.1.0.7") }
        login(e, PASSWORD, ip = "10.1.0.7").andExpect(status().isOk)
        repeat(3) { login(e, "wrong-wrong", ip = "10.1.0.7").andExpect(status().isUnauthorized) }   // a fresh budget of three
        login(e, "wrong-wrong", ip = "10.1.0.7").andExpect(status().isTooManyRequests)
    }

    @Test fun `many accounts from one address hit the per-address limit`() {
        repeat(6) { login(email(), "wrong-wrong", ip = "10.1.0.8").andExpect(status().isUnauthorized) }
        login(email(), "wrong-wrong", ip = "10.1.0.8").andExpect(status().isTooManyRequests)
        login(email(), "wrong-wrong", ip = "10.1.0.9").andExpect(status().isUnauthorized)
    }

    @Test fun `a spoofed X-Forwarded-For does not buy a fresh budget when forwarded headers are not trusted`() {
        val e = account()
        repeat(3) { n -> login(e, "wrong-wrong", ip = "10.1.0.10", xff = "203.0.113.$n").andExpect(status().isUnauthorized) }
        login(e, "wrong-wrong", ip = "10.1.0.10", xff = "203.0.113.99").andExpect(status().isTooManyRequests)
        login(e, PASSWORD, ip = "10.1.0.10", xff = "203.0.113.100").andExpect(status().isTooManyRequests)
    }

    @Test fun `throttle events are logged with a hashed email and never the address, the password or a token`() {
        val e = account()
        repeat(3) { login(e, "wrong-wrong-pw", ip = "10.1.0.11") }
        login(e, PASSWORD, ip = "10.1.0.11").andExpect(status().isTooManyRequests)
        val lines = appender.list.map { it.formattedMessage }
        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines.single().contains("event=auth.throttled") && lines.single().contains("scope=login-pair") && lines.single().contains("clientIp=10.1.0.11"))
        lines.forEach { line -> listOf(e, e.substringBefore('@'), PASSWORD, "wrong-wrong-pw", "eyJ").forEach { assertFalse(line.contains(it), "log leaked '$it': $line") } }
    }

    @Test fun `no throttle event is written to the audit table`() {
        val e = account()
        val before = t.auditCount()
        repeat(4) { login(e, "wrong-wrong", ip = "10.1.0.12") }
        assertEquals(before, t.auditCount())
    }

    // --- registration --------------------------------------------------------------------------------------------------

    @Test fun `registration allows three attempts per address, valid or not, then answers 429 with a Retry-After`() {
        register("""{"email":"not-an-email","password":"$PASSWORD","name":"X"}""", ip = "10.2.0.1").andExpect(status().isBadRequest)   // counts
        register("{}", ip = "10.2.0.1").andExpect(status().isBadRequest)                                                                // counts
        register(regBody(email()), ip = "10.2.0.1").andExpect(status().isAccepted)                                                      // counts
        register(regBody(email()), ip = "10.2.0.1").andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        register(regBody(email()), ip = "10.2.0.2").andExpect(status().isAccepted)                                                      // another address
    }

    @Test fun `a throttled registration does no hashing and creates nothing`() {
        repeat(3) { register(regBody(email()), ip = "10.2.0.3") }
        clearInvocations(encoder)
        val e = email()
        val before = jdbc.queryForObject("select count(*) from users", Long::class.java)
        register(regBody(e), ip = "10.2.0.3").andExpect(status().isTooManyRequests)
        verify(encoder, never()).encode(any())
        assertEquals(before, jdbc.queryForObject("select count(*) from users", Long::class.java))
    }

    @Test fun `the registration throttle answers a known and an unknown address identically`() {
        val known = account()
        repeat(3) { register(regBody(email()), ip = "10.2.0.4") }
        val a = register(regBody(known), ip = "10.2.0.4").andExpect(status().isTooManyRequests).andReturn().response.contentAsString
        val b = register(regBody(email()), ip = "10.2.0.4").andExpect(status().isTooManyRequests).andReturn().response.contentAsString
        assertEquals(t.json.readTree(a)["detail"], t.json.readTree(b)["detail"])
    }
}
