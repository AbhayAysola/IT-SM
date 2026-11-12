package com.itsm.auth

import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * B14a: a duplicate and a new address receive the SAME visible answer, an existing account is never touched, and unique-index
 * races give the same answer. Real PostgreSQL 16 and the real filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RegistrationHardeningIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        const val PASSWORD = "Passw0rd!"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @SpyBean lateinit var users: UserRepository
    @SpyBean lateinit var encoder: PasswordEncoder

    private lateinit var t: AdminApiTestSupport
    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private fun email() = "r-${UUID.randomUUID().toString().take(8)}@campus.example"
    private fun body(email: String, password: String = PASSWORD, name: String = "Pat") = """{"email":"$email","password":"$password","name":"$name"}"""
    private fun register(json: String) = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
    private fun usersWithEmail(e: String) = jdbc.queryForObject("select count(*) from users where lower(email) = lower(?)", Long::class.java, e)!!
    private fun registeredEvents() = jdbc.queryForObject("select count(*) from audit_events where action = 'USER_REGISTERED'", Long::class.java)!!

    /** Status, header names+values and body, without the fields that legitimately differ per request. */
    private fun visible(r: MockHttpServletResponse): List<Any?> {
        val json = t.json.readTree(r.contentAsString)
        val comparableBody = if (json.has("traceId")) json.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove("traceId"); remove("timestamp") } else json
        // Bean validation does not promise an order between two messages for one field; the SET of messages is what matters.
        val errors = comparableBody.get("errors")?.map { it.toString() }?.sorted()
        return listOf(r.status, r.headerNames.sorted().associateWith { r.getHeaders(it) },
            (comparableBody.deepCopy<com.fasterxml.jackson.databind.JsonNode>() as? com.fasterxml.jackson.databind.node.ObjectNode)?.apply { remove("errors") } ?: comparableBody, errors)
    }

    @Test fun `a new and an already registered address get byte-identical visible responses`() {
        val existing = email()
        register(body(existing)).andExpect(status().isAccepted)
        val forNew = register(body(email())).andExpect(status().isAccepted).andReturn().response
        val forExisting = register(body(existing)).andExpect(status().isAccepted).andReturn().response
        assertEquals("""{"status":"ACCEPTED"}""", forNew.contentAsString)
        assertEquals(forNew.contentAsString, forExisting.contentAsString)
        assertEquals(visible(forNew), visible(forExisting))
        for (r in listOf(forNew, forExisting)) {
            assertFalse(r.contentAsString.contains("token") || r.contentAsString.contains("already", ignoreCase = true) || r.contentAsString.contains("exist", ignoreCase = true))
            assertEquals(null, r.getHeader("Location"), "no Location header that could differ")
        }
    }

    @Test fun `case variants of an existing address are treated like the existing address`() {
        val e = email()
        register(body(e)).andExpect(status().isAccepted)
        val before = usersWithEmail(e)
        for (variant in listOf(e.uppercase(), e.replaceFirstChar { it.uppercase() })) {
            register(body(variant)).andExpect(status().isAccepted)
        }
        assertEquals(before, usersWithEmail(e))
        assertEquals(1L, usersWithEmail(e))
    }

    @Test fun `registering an existing account changes nothing about it, including an admin`() {
        val admin = t.caller(Role.ADMIN)
        data class Row(val hash: String, val role: String, val name: String, val version: Long, val tokenVersion: Int, val active: Boolean, val updated: Any?)
        fun row() = jdbc.queryForObject("select password, role, name, version, token_version, active, updated_at from users where email = ?",
            { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5), rs.getBoolean(6), rs.getObject(7)) }, admin.email)!!
        val before = row(); val auditBefore = t.auditCount()
        register("""{"email":"${admin.email}","password":"Attacker-Pa55!","name":"Hacker","role":"EMPLOYEE","active":false}""").andExpect(status().isAccepted)
        assertEquals(before, row(), "no password, role, name, state or token-version change")
        assertEquals(auditBefore, t.auditCount(), "no audit event for a duplicate")
        // the old credentials still work, the attacker's do not, and the existing token is still valid
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"${admin.email}","password":"Attacker-Pa55!"}""")).andExpect(status().isUnauthorized)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/users/me").header("Authorization", admin.bearer)).andExpect(status().isOk)
    }

    @Test fun `password hashing happens exactly once on the new path and on the existing path`() {
        val existing = email()
        register(body(existing)).andExpect(status().isAccepted)
        clearInvocations(encoder)
        register(body(email())).andExpect(status().isAccepted)
        verify(encoder, times(1)).encode(any())
        clearInvocations(encoder)
        register(body(existing)).andExpect(status().isAccepted)
        verify(encoder, times(1)).encode(any())      // the same dominant cost, so the timing does not depend on existence
    }

    @Test fun `validation errors are the same 400 whether or not the address exists`() {
        val existing = email(); register(body(existing)).andExpect(status().isAccepted)
        for (bad in listOf("short", "é".repeat(40), "")) {
            val a = register(body(existing, password = bad)).andExpect(status().isBadRequest).andReturn().response
            val b = register(body(email(), password = bad)).andExpect(status().isBadRequest).andReturn().response
            assertEquals(visible(a), visible(b), "password '${bad.take(5)}'")
        }
        register("""{"email":"not-an-email","password":"$PASSWORD","name":"X"}""").andExpect(status().isBadRequest)
    }

    @Test fun `a new registration creates an EMPLOYEE with one audit event, and still ignores forged fields`() {
        val e = email(); val before = registeredEvents()
        register("""{"email":"$e","password":"$PASSWORD","name":"Pat","role":"ADMIN","roles":["ADMIN"],"active":true,"tokenVersion":9}""").andExpect(status().isAccepted)
        assertEquals("EMPLOYEE", jdbc.queryForObject("select role from users where email = ?", String::class.java, e))
        assertEquals(before + 1, registeredEvents())
    }

    @Test fun `a lost unique-index race gives the same answer and leaves one account and one audit event`() {
        val e = email()
        // The row already exists, but the duplicate pre-check is made to say "no" once, exactly as if a concurrent request
        // had inserted it between that check and our insert: the insert then fails on the unique index.
        users.save(UserEntity(email = e, password = encoder.encode(PASSWORD), name = "Winner", role = Role.EMPLOYEE.name))
        val eventsBefore = registeredEvents()
        doReturn(false).doReturn(true).`when`(users).existsByEmail(e)   // 1st: the racy pre-check; 2nd: the re-check after the failed insert
        val raced = register(body(e, name = "Loser")).andExpect(status().isAccepted).andReturn().response
        reset(users)
        assertEquals("""{"status":"ACCEPTED"}""", raced.contentAsString)
        assertEquals(1L, usersWithEmail(e))
        assertEquals("Winner", jdbc.queryForObject("select name from users where email = ?", String::class.java, e))
        assertEquals(eventsBefore, registeredEvents(), "the loser wrote no audit event")
        register(body(e)).andExpect(status().isAccepted)   // and the transaction machinery is healthy afterwards
    }

    @Test fun `parallel registrations of the same new address all answer 202 and create exactly one account`() {
        repeat(3) {
            val e = email(); val eventsBefore = registeredEvents()
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            val statuses = (1..8).map { pool.submit<Int> { go.await(); register(body(e)).andReturn().response.status } }
            go.countDown()
            val results = statuses.map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            assertTrue(results.all { it == 202 }, "statuses $results")
            assertEquals(1L, usersWithEmail(e))
            assertEquals(eventsBefore + 1, registeredEvents())
        }
    }
}
