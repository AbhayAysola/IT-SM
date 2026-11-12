package com.itsm.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.model.Role
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * B14b: browser (cookie) authentication, its CSRF protection, the two login modes and logout, through the real filter chain on
 * PostgreSQL 16. Allowed origin (default `app.cors.allowed-origins`): http://localhost:3000.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.rate-limit.login-pair.max-failures=4"])
@Testcontainers(disabledWithoutDocker = true)
class WebSessionIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        const val PASSWORD = "Passw0rd!"
        const val ORIGIN = "http://localhost:3000"
        const val COOKIE = "itsm_session"
        const val CLIENT = "X-ITSM-Client"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Value("\${app.jwt.secret}") lateinit var secret: String
    @Value("\${app.jwt.expiration:86400000}") var expirationMs: Long = 0
    @SpyBean lateinit var encoder: PasswordEncoder
    private val json = ObjectMapper()
    private val hash by lazy { encoder.encode(PASSWORD) }

    @BeforeEach fun warmUp() { hash }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private fun account(role: Role = Role.EMPLOYEE): UserEntity =
        users.save(UserEntity(email = "w-${UUID.randomUUID().toString().take(8)}@campus.example", password = hash, name = "W", role = role.name))

    private fun loginReq(email: String, password: String = PASSWORD): MockHttpServletRequestBuilder =
        post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$password"}""")

    private fun webLogin(email: String, password: String = PASSWORD, origin: String? = ORIGIN, client: String? = "web"): ResultActions =
        mvc.perform(loginReq(email, password).apply { origin?.let { header("Origin", it) }; client?.let { header(CLIENT, it) } })

    private fun bearerLogin(email: String): ResultActions = mvc.perform(loginReq(email))

    /** The session JWT a successful web login put in the cookie. */
    private fun sessionToken(email: String): String =
        webLogin(email).andExpect(status().isOk).andReturn().response.getCookie(COOKIE)!!.value

    private fun bearerToken(email: String): String = json.readTree(bearerLogin(email).andReturn().response.contentAsString)["token"].asText()

    private fun cookieCall(method: HttpMethod, path: String, token: String, origin: String? = ORIGIN, client: String? = "web", body: String? = null): ResultActions =
        mvc.perform(request(method, path).cookie(Cookie(COOKIE, token)).apply {
            origin?.let { header("Origin", it) }; client?.let { header(CLIENT, it) }
            if (body != null) contentType(MediaType.APPLICATION_JSON).content(body)
        })

    private fun me(token: String? = null, bearer: String? = null, cookie: String? = null): ResultActions =
        mvc.perform(get("/api/users/me").apply {
            bearer?.let { header("Authorization", "Bearer $it") }
            (cookie ?: token)?.let { cookie(Cookie(COOKIE, it)) }
        })

    private fun setCookieHeaders(r: MockHttpServletResponse): List<String> = r.getHeaders(HttpHeaders.SET_COOKIE)

    private fun tokenVersion(id: Long) = jdbc.queryForObject("select token_version from users where id = ?", Int::class.java, id)!!
    private fun rowVersion(id: Long) = jdbc.queryForObject("select version from users where id = ?", Long::class.java, id)!!

    private fun forged(userId: Long, tv: Int, issued: Instant, expires: Instant): String =
        Jwts.builder().subject(userId.toString()).claim("tv", tv).issuedAt(Date.from(issued)).expiration(Date.from(expires))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))).compact()

    // --- login modes ---------------------------------------------------------------------------------------------------

    @Test fun `web login sets the HttpOnly Strict Path-api cookie with the JWT lifetime, and no Domain`() {
        val acct = account()
        val r = webLogin(acct.email).andExpect(status().isOk).andReturn().response
        val header = setCookieHeaders(r).single()
        assertTrue(header.startsWith("$COOKIE="), header)
        for (attr in listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/api", "Max-Age=${expirationMs / 1000}")) assertTrue(attr in header, "$attr missing in: $header")
        assertFalse(header.contains("Domain", ignoreCase = true), header)
        // Max-Age is exactly the JWT's own lifetime
        val payload = json.readTree(Base64.getUrlDecoder().decode(r.getCookie(COOKIE)!!.value.split('.')[1]))
        assertEquals(expirationMs / 1000, payload["exp"].asLong() - payload["iat"].asLong())
    }

    @Test fun `web login puts no JWT in the response body`() {
        val acct = account()
        val r = webLogin(acct.email).andExpect(status().isOk).andReturn().response
        val body = r.contentAsString
        val jwt = r.getCookie(COOKIE)!!.value
        assertFalse(body.contains(jwt), "the JWT leaked into the body")
        assertFalse(body.contains("eyJ"), body)
        val tree = json.readTree(body)
        assertFalse(tree.has("token")); assertEquals(acct.email, tree["user"]["email"].asText())
        assertEquals(listOf("user"), tree.fieldNames().asSequence().toList())
    }

    @Test fun `bearer login keeps its existing response and sets no cookie`() {
        val acct = account()
        val r = bearerLogin(acct.email).andExpect(status().isOk).andExpect(jsonPath("$.token").isNotEmpty).andExpect(jsonPath("$.user.email").value(acct.email)).andReturn().response
        assertTrue(setCookieHeaders(r).isEmpty(), "no cookie for a Bearer client")
        assertNull(r.getCookie(COOKIE))
        // and that token authenticates as before
        me(bearer = json.readTree(r.contentAsString)["token"].asText()).andExpect(status().isOk)
        // a Bearer login needs no Origin at all (non-browser clients send none). A BROWSER on a foreign origin is refused by CORS.
        mvc.perform(loginReq(acct.email).header("Origin", "http://evil.example")).andExpect(status().isForbidden)
    }

    @Test fun `an invalid mode header is a 400 with a fixed message, authenticates nobody, sets nothing and costs no attempt`() {
        val acct = account()
        clearInvocations(encoder)
        for (bad in listOf("WEB", "Web", "", " ", "bearer", "web,web", "web ", "true", "1")) {
            val r = webLogin(acct.email, client = bad).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[0].field").value(CLIENT)).andReturn().response
            assertTrue(setCookieHeaders(r).isEmpty(), "value '$bad' set a cookie")
            assertFalse(r.contentAsString.contains("eyJ"))
        }
        verify(encoder, never()).matches(any(), any())              // never even checked the password
        repeat(3) { bearerLogin(acct.email).andExpect(status().isOk) }   // and charged nothing against the throttle (limit is 4 failures)
    }

    // --- web login is login-CSRF protected ---------------------------------------------------------------------------------

    @Test fun `web login without an allowed Origin is a 403 with no cookie, no password check and no throttle charge`() {
        val acct = account()
        clearInvocations(encoder)
        // Refused by the application itself, with the generic JSON body: no Origin at all, or a same-origin-looking one that is not on the list.
        for (origin in listOf(null, "http://localhost")) {
            val r = webLogin(acct.email, origin = origin).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Request rejected")).andReturn().response
            assertTrue(setCookieHeaders(r).isEmpty(), "origin '$origin'")
        }
        // Any foreign browser origin is refused as well (by CORS before the application, or by the application): a 403 either way.
        for (origin in listOf("http://evil.example", "null", "http://localhost:30001", "https://localhost:3000")) {
            val r = webLogin(acct.email, origin = origin).andExpect(status().isForbidden).andReturn().response
            assertTrue(setCookieHeaders(r).isEmpty(), "origin '$origin'")
        }
        verify(encoder, never()).matches(any(), any())
        // Eight refusals later the account still logs in: none of them counted as a failed attempt (the limit here is 4).
        webLogin(acct.email).andExpect(status().isOk)
    }

    @Test fun `web login with the correct header and Origin but a wrong password is a normal 401 and no cookie`() {
        val acct = account()
        val r = webLogin(acct.email, password = "wrong-wrong").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().response
        assertTrue(setCookieHeaders(r).isEmpty())
    }

    @Test fun `web login accepts only a JSON body`() {
        val acct = account()
        for (type in listOf(MediaType.APPLICATION_FORM_URLENCODED, MediaType.TEXT_PLAIN, MediaType.MULTIPART_FORM_DATA)) {
            val r = mvc.perform(post("/api/auth/login").contentType(type).header("Origin", ORIGIN).header(CLIENT, "web")
                .content("email=${acct.email}&password=$PASSWORD")).andExpect(status().isUnsupportedMediaType).andReturn().response
            assertTrue(setCookieHeaders(r).isEmpty(), "$type")
        }
    }

    // --- cookie authentication and precedence ------------------------------------------------------------------------------

    @Test fun `the session cookie authenticates a request, and so does a Bearer token`() {
        val acct = account()
        me(cookie = sessionToken(acct.email)).andExpect(status().isOk).andExpect(jsonPath("$.email").value(acct.email))
        me(bearer = bearerToken(acct.email)).andExpect(status().isOk).andExpect(jsonPath("$.email").value(acct.email))
        me().andExpect(status().isUnauthorized)
    }

    @Test fun `precedence - an Authorization header decides alone, and an invalid Bearer never falls back to a valid cookie`() {
        val acct = account(); val other = account()
        val validCookie = sessionToken(acct.email)
        // invalid Bearer + valid cookie -> NOT authenticated
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer not.a.jwt").cookie(Cookie(COOKIE, validCookie))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer ").cookie(Cookie(COOKIE, validCookie))).andExpect(status().isUnauthorized)
        // a present but non-Bearer Authorization header also blocks the cookie
        mvc.perform(get("/api/users/me").header("Authorization", "Basic dXNlcjpwdw==").cookie(Cookie(COOKIE, validCookie))).andExpect(status().isUnauthorized)
        // valid Bearer wins over a cookie of a DIFFERENT user, and over a garbage cookie
        val bearer = bearerToken(other.email)
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer $bearer").cookie(Cookie(COOKIE, validCookie))).andExpect(status().isOk).andExpect(jsonPath("$.email").value(other.email))
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer $bearer").cookie(Cookie(COOKIE, "garbage"))).andExpect(status().isOk)
    }

    @Test fun `an expired, tampered, unsigned, empty or stale-version cookie is a 401`() {
        val acct = account()
        val now = Instant.now()
        val good = sessionToken(acct.email)
        me(cookie = good).andExpect(status().isOk)
        me(cookie = forged(acct.id, 0, now.minusSeconds(7200), now.minusSeconds(3600))).andExpect(status().isUnauthorized)            // expired
        val parts = good.split('.')
        val flipped = parts[2].let { it.dropLast(1) + if (it.last() == 'A') 'B' else 'A' }
        me(cookie = "${parts[0]}.${parts[1]}.$flipped").andExpect(status().isUnauthorized)                                         // bad signature
        val noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        me(cookie = "$noneHeader.${parts[1]}.").andExpect(status().isUnauthorized)                                                  // alg none
        me(cookie = forged(acct.id, 99, now, now.plusSeconds(3600))).andExpect(status().isUnauthorized)                            // wrong token version
        me(cookie = forged(999_999_999, 0, now, now.plusSeconds(3600))).andExpect(status().isUnauthorized)                         // unknown user
        mvc.perform(get("/api/users/me").cookie(Cookie(COOKIE, ""))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/users/me").cookie(Cookie("other_cookie", good))).andExpect(status().isUnauthorized)                  // wrong cookie name
    }

    // --- CSRF on cookie-authenticated unsafe requests ----------------------------------------------------------------------

    @Test fun `an unsafe cookie request without the web-client header is a generic 403 and nothing happens`() {
        val acct = account(); val token = sessionToken(acct.email)
        for (client in listOf(null, "", "WEB", "true", "bearer", "web, web")) {
            cookieCall(HttpMethod.POST, "/api/auth/logout", token, client = client).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.detail").value("Request rejected"))
        }
        me(cookie = token).andExpect(status().isOk)                         // the logout never ran
        assertEquals(0, tokenVersion(acct.id))
    }

    @Test fun `an unsafe cookie request with a missing or unlisted Origin is refused and nothing happens`() {
        val acct = account(); val token = sessionToken(acct.email)
        // Refused by JwtAuthFilter with the generic body (it never says which check failed). `http://localhost` is the MockMvc request's
        // own origin, so CORS treats it as same-origin and only the allow-list stands between it and the controller.
        for (origin in listOf(null, "http://localhost")) {
            val body = cookieCall(HttpMethod.POST, "/api/auth/logout", token, origin = origin).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.detail").value("Request rejected")).andReturn().response.contentAsString
            assertFalse(body.contains("Origin", ignoreCase = true) || body.contains(CLIENT, ignoreCase = true), "the refusal must not say which check failed: $body")
        }
        // Foreign browser origins never get that far when CORS refuses them first; either way the request is a 403 and has no effect.
        for (origin in listOf("null", "http://evil.example", "http://localhost:30001", "https://localhost:3000")) {
            cookieCall(HttpMethod.POST, "/api/auth/logout", token, origin = origin).andExpect(status().isForbidden)
        }
        me(cookie = token).andExpect(status().isOk)
        assertEquals(0, tokenVersion(acct.id))
    }

    @Test fun `POST PUT PATCH and DELETE are all protected, while GET HEAD and a valid request are not`() {
        val admin = account(Role.ADMIN); val token = sessionToken(admin.email)
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            cookieCall(method, "/api/admin/users/${admin.id}", token, origin = null, client = null, body = "{}").andExpect(status().isForbidden)
            cookieCall(method, "/api/admin/users/${admin.id}", token, origin = ORIGIN, client = null, body = "{}").andExpect(status().isForbidden)
            cookieCall(method, "/api/admin/users/${admin.id}", token, origin = "http://evil.example", client = "web", body = "{}").andExpect(status().isForbidden)
            // with both defences satisfied the request reaches the application (any answer but the CSRF refusal)
            val passed = cookieCall(method, "/api/admin/users/${admin.id}", token, body = "{}").andReturn().response
            assertNotEquals(403, passed.status, "$method with header and Origin must reach the controller")
            assertFalse(passed.contentAsString.contains("Request rejected"))
        }
        // safe methods need neither header nor Origin
        mvc.perform(get("/api/admin/users").cookie(Cookie(COOKIE, token))).andExpect(status().isOk)
        mvc.perform(request(HttpMethod.HEAD, "/api/admin/users").cookie(Cookie(COOKIE, token))).andExpect(status().isOk)
        me(cookie = token).andExpect(status().isOk)
    }

    @Test fun `a Bearer-only client is not subject to the cookie CSRF rules`() {
        val acct = account(); val bearer = bearerToken(acct.email)
        // unsafe, no X-ITSM-Client and no Origin (what a script or service sends): fine for a Bearer credential
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer $bearer")).andExpect(status().isOk)
        // and a Bearer request is not forced through the Origin allow-list by the application either (same-origin-looking Origin)
        val acct2 = account(); val bearer2 = bearerToken(acct2.email)
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer $bearer2").header("Origin", "http://localhost")).andExpect(status().isOk)
    }

    @Test fun `a cookie that is invalid gets 401 rather than 403 even on an unsafe request`() {
        cookieCall(HttpMethod.POST, "/api/auth/logout", "garbage", origin = null, client = null).andExpect(status().isUnauthorized)
    }

    // --- logout ---------------------------------------------------------------------------------------------------------

    @Test fun `logout revokes every token of the user, clears the cookie with matching attributes and returns a fixed body`() {
        val acct = account()
        val browserA = sessionToken(acct.email); val browserB = sessionToken(acct.email); val script = bearerToken(acct.email)
        val versionBefore = tokenVersion(acct.id); val rowBefore = rowVersion(acct.id)
        val r = cookieCall(HttpMethod.POST, "/api/auth/logout", browserA).andExpect(status().isOk).andExpect(jsonPath("$.status").value("LOGGED_OUT")).andReturn().response
        assertEquals("""{"status":"LOGGED_OUT"}""", r.contentAsString)
        val clearing = setCookieHeaders(r).single()
        for (attr in listOf("$COOKIE=;", "Max-Age=0", "Path=/api", "HttpOnly", "Secure", "SameSite=Strict")) assertTrue(attr in clearing, "$attr missing in: $clearing")
        assertFalse(clearing.contains("eyJ"))
        assertEquals(versionBefore + 1, tokenVersion(acct.id)); assertEquals(rowBefore + 1, rowVersion(acct.id))
        // ALL sessions died, not just the one that logged out
        me(cookie = browserA).andExpect(status().isUnauthorized)
        me(cookie = browserB).andExpect(status().isUnauthorized)
        me(bearer = script).andExpect(status().isUnauthorized)
        // and a fresh login works
        me(cookie = sessionToken(acct.email)).andExpect(status().isOk)
    }

    @Test fun `logout needs authentication and is CSRF protected for cookies`() {
        mvc.perform(post("/api/auth/logout").header("Origin", ORIGIN).header(CLIENT, "web")).andExpect(status().isUnauthorized)
        val acct = account(); val token = sessionToken(acct.email)
        cookieCall(HttpMethod.POST, "/api/auth/logout", token, client = null).andExpect(status().isForbidden)
        cookieCall(HttpMethod.POST, "/api/auth/logout", token, origin = null).andExpect(status().isForbidden)
        assertEquals(0, tokenVersion(acct.id))
        cookieCall(HttpMethod.POST, "/api/auth/logout", token).andExpect(status().isOk)
        assertEquals(1, tokenVersion(acct.id))
        // the old session cannot log out again
        cookieCall(HttpMethod.POST, "/api/auth/logout", token).andExpect(status().isUnauthorized)
        assertEquals(1, tokenVersion(acct.id))
        mvc.perform(get("/api/auth/logout")).andExpect(status().isUnauthorized)   // not a GET endpoint
    }

    @Test fun `a logout cannot be undone by an admin save made from an older copy of the user`() {
        val admin = account(Role.ADMIN); val adminToken = sessionToken(admin.email)
        val victim = account(); val victimToken = sessionToken(victim.email)
        val version = json.readTree(cookieCall(HttpMethod.GET, "/api/admin/users/${victim.id}", adminToken).andReturn().response.contentAsString)["version"].asLong()
        cookieCall(HttpMethod.POST, "/api/auth/logout", victimToken).andExpect(status().isOk)
        // the admin still holds the old `version`: without the version bump this would pass and write the old token_version back
        cookieCall(HttpMethod.PUT, "/api/admin/users/${victim.id}", adminToken, body = """{"email":"${victim.email}","name":"Renamed","version":$version}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        me(cookie = victimToken).andExpect(status().isUnauthorized)
        assertEquals(1, tokenVersion(victim.id))
    }

    @Test fun `parallel logouts all succeed and each revokes once`() {
        val acct = account()
        val tokens = (1..4).map { sessionToken(acct.email) }
        val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1)
        val results = tokens.map { t -> pool.submit<Int> { go.await(); cookieCall(HttpMethod.POST, "/api/auth/logout", t).andReturn().response.status } }
        go.countDown()
        val statuses = results.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(statuses.all { it == 200 || it == 401 }, "statuses $statuses")     // 401 = it lost the race to an earlier logout
        assertTrue(statuses.count { it == 200 } >= 1)
        assertEquals(statuses.count { it == 200 }, tokenVersion(acct.id))
        tokens.forEach { me(cookie = it).andExpect(status().isUnauthorized) }
    }

    // --- CORS together with CSRF ---------------------------------------------------------------------------------------------

    @Test fun `a preflight from the allowed origin permits credentials and the web-client header, nothing else`() {
        val r = mvc.perform(options("/api/auth/logout").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-itsm-client")).andExpect(status().isOk).andReturn().response
        assertEquals(ORIGIN, r.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", r.getHeader("Access-Control-Allow-Credentials"))
        assertTrue(r.getHeader("Access-Control-Allow-Headers")!!.lowercase().contains("x-itsm-client"))
        assertNotEquals("*", r.getHeader("Access-Control-Allow-Origin"))
        // a header that is not on the list is refused
        mvc.perform(options("/api/auth/logout").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "x-evil-header")).andExpect(status().isForbidden)
    }

    @Test fun `a preflight or actual request from any other origin gets no CORS grant and no credentials`() {
        for (origin in listOf("http://evil.example", "null", "https://localhost:3000", "http://localhost:30001")) {
            val pre = mvc.perform(options("/api/auth/logout").header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-itsm-client")).andExpect(status().isForbidden).andReturn().response
            assertNull(pre.getHeader("Access-Control-Allow-Origin")); assertNull(pre.getHeader("Access-Control-Allow-Credentials"))
            val acct = account(); val token = sessionToken(acct.email)
            val actual = cookieCall(HttpMethod.POST, "/api/auth/logout", token, origin = origin).andExpect(status().isForbidden).andReturn().response
            assertNull(actual.getHeader("Access-Control-Allow-Origin")); assertNull(actual.getHeader("Access-Control-Allow-Credentials"))
            assertEquals(0, tokenVersion(acct.id), "$origin must not have been able to log the user out")
        }
    }

    @Test fun `an actual request from the allowed origin carries the credentials headers back`() {
        val acct = account()
        val r = webLogin(acct.email).andExpect(status().isOk).andReturn().response
        assertEquals(ORIGIN, r.getHeader("Access-Control-Allow-Origin")); assertEquals("true", r.getHeader("Access-Control-Allow-Credentials"))
    }
}
