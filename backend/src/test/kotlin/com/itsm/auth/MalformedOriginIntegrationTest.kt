package com.itsm.auth

import com.itsm.common.model.Role
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * B14c: a malformed `Origin` header never causes an exception or a 500, anywhere in the real security chain (MockMvc with the
 * full filter chain on PostgreSQL 16). Allowed origin (default `app.cors.allowed-origins`): http://localhost:3000.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MalformedOriginIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        const val PASSWORD = "Passw0rd!"
        const val ORIGIN = "http://localhost:3000"
        const val COOKIE = "itsm_session"
        const val CLIENT = "X-ITSM-Client"

        /** Values that make Spring's own CORS parser throw (verified), plus other things that are not an origin. */
        val MALFORMED = listOf(
            "http://localhost:3000.evil.example", "http://[::1", "http://", "http://localhost:99999999999", "http://localhost:70000",
            "", " ", "://", "localhost:3000", "http://local host", "ftp://localhost:3000", "javascript:alert(1)",
            "http://localhost:3000/", "http://localhost:3000/path", "http://user@localhost:3000", "http://localhost:3000, http://evil.example",
            "http://localhost:abc", "http://exa%mple.com", "http://" + "a".repeat(400)
        )
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @SpyBean lateinit var encoder: PasswordEncoder
    @SpyBean lateinit var userRepoSpy: UserRepository
    private val hash by lazy { encoder.encode(PASSWORD) }

    @BeforeEach fun warmUp() { hash }

    private fun account(role: Role = Role.EMPLOYEE): UserEntity =
        users.save(UserEntity(email = "m-${UUID.randomUUID().toString().take(8)}@campus.example", password = hash, name = "M", role = role.name))

    private fun loginBody(email: String) = """{"email":"$email","password":"$PASSWORD"}"""

    private fun sessionToken(email: String): String =
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("Origin", ORIGIN).header(CLIENT, "web").content(loginBody(email)))
            .andExpect(status().isOk).andReturn().response.getCookie(COOKIE)!!.value

    private fun bearerToken(email: String): String =
        com.fasterxml.jackson.databind.ObjectMapper().readTree(
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(email))).andReturn().response.contentAsString
        )["token"].asText()

    private fun tokenVersion(id: Long) = jdbc.queryForObject("select token_version from users where id = ?", Int::class.java, id)!!

    /** The one generic refusal: a 400 problem body that names neither the value, the parser, nor the allow-list. */
    private fun assertGenericRefusal(r: MockHttpServletResponse, origin: String) {
        assertEquals(400, r.status, "Origin [$origin]")
        val body = r.contentAsString
        assertTrue(body.contains("Invalid Origin header"), body)
        assertTrue(body.contains("VALIDATION_FAILED"), body)
        for (leak in listOf("localhost:3000", "Exception", "java.", "org.springframework", "allowed", "IllegalState", "IllegalArgument")) {
            assertFalse(body.contains(leak, ignoreCase = true), "response must not contain '$leak': $body")
        }
        if (origin.isNotBlank()) assertFalse(body.contains(origin.take(40)), "the value must not be echoed")
        assertNull(r.getHeader("Access-Control-Allow-Origin"))
        assertNull(r.getHeader("Access-Control-Allow-Credentials"))
        assertTrue(r.getHeaders("Set-Cookie").isEmpty())
    }

    // --- preflight ------------------------------------------------------------------------------------------------------
    @Test fun `a malformed Origin on a CORS preflight is a generic 400, never an exception`() {
        for (o in MALFORMED) {
            val r = mvc.perform(options("/api/auth/logout").header("Origin", o).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-itsm-client")).andReturn().response
            assertGenericRefusal(r, o)
            // also on a path that does not exist and on the public login path
            assertGenericRefusal(mvc.perform(options("/api/nope").header("Origin", o).header("Access-Control-Request-Method", "GET")).andReturn().response, o)
            assertGenericRefusal(mvc.perform(options("/api/auth/login").header("Origin", o).header("Access-Control-Request-Method", "POST")).andReturn().response, o)
        }
    }

    // --- actual requests ------------------------------------------------------------------------------------------------
    @Test fun `a malformed Origin on an actual request is refused before authentication and before any controller`() {
        val acct = account(); val bearer = bearerToken(acct.email); val cookie = sessionToken(acct.email)
        clearInvocations(userRepoSpy)
        for (o in MALFORMED) {
            // unauthenticated, Bearer-authenticated and cookie-authenticated, protected and public paths: all the same refusal
            assertGenericRefusal(mvc.perform(get("/api/users/me").header("Origin", o)).andReturn().response, o)
            assertGenericRefusal(mvc.perform(get("/api/users/me").header("Origin", o).header("Authorization", "Bearer $bearer")).andReturn().response, o)
            assertGenericRefusal(mvc.perform(get("/api/users/me").header("Origin", o).cookie(Cookie(COOKIE, cookie))).andReturn().response, o)
            assertGenericRefusal(mvc.perform(get("/actuator/health").header("Origin", o)).andReturn().response, o)
            assertGenericRefusal(mvc.perform(get("/api/admin/users").header("Origin", o).header("Authorization", "Bearer $bearer")).andReturn().response, o)
        }
        // not one of those requests reached authentication (no user was looked up)
        verify(userRepoSpy, never()).findById(anyLong())
        // and the same credentials work as soon as the Origin is valid or absent
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
        mvc.perform(get("/api/users/me").header("Origin", ORIGIN).cookie(Cookie(COOKIE, cookie))).andExpect(status().isOk)
    }

    @Test fun `two Origin headers are refused as malformed`() {
        val r = mvc.perform(get("/api/users/me").header("Origin", ORIGIN).header("Origin", "http://evil.example")).andReturn().response
        assertGenericRefusal(r, "")
    }

    // --- web login ------------------------------------------------------------------------------------------------------
    @Test fun `a malformed Origin on web login and on Bearer login is refused before any password check or throttle charge`() {
        val acct = account()
        clearInvocations(encoder, userRepoSpy)
        for (o in MALFORMED) {
            for (client in listOf("web", null, "bogus")) {
                val r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("Origin", o)
                    .apply { client?.let { header(CLIENT, it) } }.content(loginBody(acct.email))).andReturn().response
                assertGenericRefusal(r, o)
            }
        }
        verify(encoder, never()).matches(any(), any())
        verify(userRepoSpy, never()).findByEmail(anyString())
        // the account was never locked or throttled: a correct web login still works
        sessionToken(acct.email)
    }

    // --- cookie-authenticated unsafe requests ----------------------------------------------------------------------------
    @Test fun `a malformed Origin on a cookie-authenticated POST PUT PATCH and DELETE is refused and has no effect`() {
        val admin = account(Role.ADMIN); val token = sessionToken(admin.email)
        for (o in MALFORMED) {
            for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                val r = mvc.perform(request(method, "/api/admin/users/${admin.id}").cookie(Cookie(COOKIE, token)).header("Origin", o).header(CLIENT, "web")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response
                assertGenericRefusal(r, o)
            }
            assertGenericRefusal(mvc.perform(post("/api/auth/logout").cookie(Cookie(COOKIE, token)).header("Origin", o).header(CLIENT, "web")).andReturn().response, o)
        }
        assertEquals(0, tokenVersion(admin.id), "no logout may have happened")
        mvc.perform(get("/api/users/me").cookie(Cookie(COOKIE, token))).andExpect(status().isOk)
    }

    // --- regressions ----------------------------------------------------------------------------------------------------
    @Test fun `a valid allowed Origin still works for preflight, login, cookie requests and logout`() {
        val acct = account()
        val pre = mvc.perform(options("/api/auth/logout").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "x-itsm-client")).andExpect(status().isOk).andReturn().response
        assertEquals(ORIGIN, pre.getHeader("Access-Control-Allow-Origin"))
        val token = sessionToken(acct.email)
        mvc.perform(get("/api/users/me").header("Origin", ORIGIN).cookie(Cookie(COOKIE, token))).andExpect(status().isOk)
        // case-insensitive scheme/host stays accepted, as in B14b
        mvc.perform(post("/api/auth/logout").cookie(Cookie(COOKIE, token)).header("Origin", "HTTP://LOCALHOST:3000").header(CLIENT, "web"))
            .andExpect(status().isOk)
        assertEquals(1, tokenVersion(acct.id))
    }

    @Test fun `a well-formed foreign Origin, the null origin and an IPv6 origin are still refused as before, not as malformed`() {
        val acct = account(); val token = sessionToken(acct.email)
        for (o in listOf("http://evil.example", "https://evil.example:8443", "null", "http://[::1]:3000", "http://127.0.0.1:3000", "https://localhost:3000")) {
            val pre = mvc.perform(options("/api/auth/logout").header("Origin", o).header("Access-Control-Request-Method", "POST")).andReturn().response
            assertEquals(403, pre.status, o); assertNull(pre.getHeader("Access-Control-Allow-Origin"))
            assertFalse(pre.contentAsString.contains("Invalid Origin header"))
            val actual = mvc.perform(post("/api/auth/logout").cookie(Cookie(COOKIE, token)).header("Origin", o).header(CLIENT, "web")).andReturn().response
            assertEquals(403, actual.status, o); assertNull(actual.getHeader("Access-Control-Allow-Origin"))
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("Origin", o).header(CLIENT, "web").content(loginBody(acct.email)))
                .andExpect(status().isForbidden)
        }
        assertEquals(0, tokenVersion(acct.id))
    }

    @Test fun `Bearer-only clients without an Origin keep their behaviour`() {
        val acct = account(); val bearer = bearerToken(acct.email)
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer $bearer")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").exists())
        assertNotEquals(500, mvc.perform(get("/api/users/me")).andReturn().response.status)
    }
}
