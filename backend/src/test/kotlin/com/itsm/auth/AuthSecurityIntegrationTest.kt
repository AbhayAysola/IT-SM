package com.itsm.auth

import com.itsm.admin.AdminApiTestSupport
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** Registration, login, `/api/users/me`, token invalidation, actuator and CORS on a real PostgreSQL 16 (V15, Hibernate validation). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthSecurityIntegrationTest {

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
    @Value("\${app.jwt.secret}") lateinit var secret: String

    private lateinit var t: AdminApiTestSupport

    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private fun email() = "u-${UUID.randomUUID().toString().take(8)}@campus.example"

    private fun register(body: String): ResultActions =
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun login(email: String, password: String): ResultActions =
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$password"}"""))

    private fun me(bearer: String?): ResultActions =
        mvc.perform(get("/api/users/me").apply { if (bearer != null) header("Authorization", bearer) })

    private fun storedRole(email: String) = jdbc.queryForObject("select role from users where email = ?", String::class.java, email)

    // --- registration ----------------------------------------------------------------------------------------------

    @Test fun `public registration always creates an EMPLOYEE whatever role the client sends`() {
        for (forged in listOf("ADMIN", "TECHNICIAN", "USER", "admin", "ROLE_ADMIN", "WIZARD")) {
            val e = email()
            register("""{"email":"$e","password":"Passw0rd!","name":"Mallory","role":"$forged","roles":["ADMIN"],"active":true,"tokenVersion":99}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.user.role").value("EMPLOYEE"))
            assertEquals("EMPLOYEE", storedRole(e), "forged role $forged")
        }
    }

    @Test fun `registration normalises email, rejects case-insensitive duplicates and never returns secrets`() {
        val base = email()
        val result = register("""{"email":"${base.uppercase()}","password":"Passw0rd!","name":" Pat "}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.user.email").value(base)).andExpect(jsonPath("$.user.name").value("Pat"))
            .andReturn().response.contentAsString
        assertFalse(result.contains("password", ignoreCase = true) || result.contains("\$2a\$") || result.contains("tokenVersion"))
        assertEquals(base, jdbc.queryForObject("select email from users where lower(email) = ?", String::class.java, base))

        register("""{"email":"${base.replaceFirstChar { it.uppercase() }}","password":"Passw0rd!","name":"Dup"}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        login(base.uppercase(), "Passw0rd!").andExpect(status().isOk)
    }

    @Test fun `registration validates input and creates nothing on failure`() {
        val before = jdbc.queryForObject("select count(*) from users", Long::class.java)
        register("""{"email":"not-an-email","password":"Passw0rd!","name":"X"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        register("""{"email":"${email()}","password":"short","name":"X"}""").andExpect(status().isBadRequest)
        register("""{"email":"${email()}","password":"${"é".repeat(40)}","name":"X"}""").andExpect(status().isBadRequest)   // 80 bytes > BCrypt's 72
        register("""{"email":"${email()}","password":"Passw0rd!","name":"  "}""").andExpect(status().isBadRequest)
        register("""{"email":"${email()}","password":"Passw0rd!"}""").andExpect(status().isBadRequest)
        register("""{}""").andExpect(status().isBadRequest)
        assertEquals(before, jdbc.queryForObject("select count(*) from users", Long::class.java))
    }

    // --- login -----------------------------------------------------------------------------------------------------

    @Test fun `login works, and wrong password, unknown email and disabled account are indistinguishable`() {
        val e = email()
        register("""{"email":"$e","password":"Passw0rd!","name":"Pat"}""").andExpect(status().isOk)
        login(e, "Passw0rd!").andExpect(status().isOk).andExpect(jsonPath("$.token").isNotEmpty).andExpect(jsonPath("$.user.role").value("EMPLOYEE"))

        val wrong = login(e, "nope-nope").andExpect(status().isUnauthorized).andReturn().response.contentAsString
        val unknown = login(email(), "Passw0rd!").andExpect(status().isUnauthorized).andReturn().response.contentAsString
        jdbc.update("update users set active = false where email = ?", e)
        val disabled = login(e, "Passw0rd!").andExpect(status().isUnauthorized).andReturn().response.contentAsString
        for (body in listOf(wrong, unknown, disabled)) {
            val json = t.json.readTree(body)
            assertEquals("INVALID_CREDENTIALS", json["code"].asText()); assertEquals("Invalid email or password", json["detail"].asText())
        }
        login(e, "x".repeat(200)).andExpect(status().isBadRequest)
        login(e, "é".repeat(40)).andExpect(status().isUnauthorized)
        login("", "").andExpect(status().isBadRequest)
    }

    // --- /api/users/me ---------------------------------------------------------------------------------------------

    @Test fun `me returns the stored canonical role for all three roles`() {
        for (role in Role.entries) {
            val c = t.caller(role)
            me(c.bearer).andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(c.id)).andExpect(jsonPath("$.email").value(c.email))
                .andExpect(jsonPath("$.name").isNotEmpty).andExpect(jsonPath("$.role").value(role.name))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.password").doesNotExist())
        }
    }

    @Test fun `me reads the role from the database, not from the token`() {
        val c = t.caller(Role.EMPLOYEE)
        jdbc.update("update users set role = 'ADMIN' where id = ?", c.id)   // token_version unchanged on purpose
        me(c.bearer).andExpect(jsonPath("$.role").value("ADMIN"))
    }

    // --- token validity --------------------------------------------------------------------------------------------

    @Test fun `missing, malformed, expired and wrongly signed tokens are 401`() {
        me(null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        me("Bearer garbage").andExpect(status().isUnauthorized)
        me("Bearer ").andExpect(status().isUnauthorized)
        me("Basic abc").andExpect(status().isUnauthorized)

        val c = t.caller(Role.EMPLOYEE)
        val shortLived = JwtService(secret, 1)
        val expired = shortLived.generateToken(c.id, 0); Thread.sleep(50)
        me("Bearer $expired").andExpect(status().isUnauthorized)

        val foreign = JwtService("another-completely-different-secret-0123456789", 60_000).generateToken(c.id, 0)
        me("Bearer $foreign").andExpect(status().isUnauthorized)
        me(c.bearer).andExpect(status().isOk)
    }

    @Test fun `token for a deleted or unknown user is 401, never 500`() {
        val c = t.caller(Role.EMPLOYEE)
        jdbc.update("delete from users where id = ?", c.id)
        me(c.bearer).andExpect(status().isUnauthorized)
        me("Bearer " + jwt.generateToken(9_999_999, 0)).andExpect(status().isUnauthorized)
    }

    @Test fun `a token-version mismatch, either direction, is rejected`() {
        val c = t.caller(Role.EMPLOYEE)
        jdbc.update("update users set token_version = token_version + 1 where id = ?", c.id)
        me(c.bearer).andExpect(status().isUnauthorized)
        me("Bearer " + jwt.generateToken(c.id, 1)).andExpect(status().isOk)
        me("Bearer " + jwt.generateToken(c.id, 2)).andExpect(status().isUnauthorized)
    }

    @Test fun `a deactivated user's token is rejected and reactivation does not revive it`() {
        val c = t.caller(Role.EMPLOYEE)
        jdbc.update("update users set active = false, token_version = token_version + 1 where id = ?", c.id)
        me(c.bearer).andExpect(status().isUnauthorized)
        jdbc.update("update users set active = true, token_version = token_version + 1 where id = ?", c.id)
        me(c.bearer).andExpect(status().isUnauthorized)
    }

    // --- database-level guarantees (V15) ---------------------------------------------------------------------------

    @Test fun `the database refuses non-canonical roles and non-normalised or duplicate emails`() {
        fun insert(email: String, role: String) = jdbc.update("insert into users (email, password, name, role) values (?, 'h', 'n', ?)", email, role)
        val e = email()
        insert(e, "EMPLOYEE")
        for (bad in listOf("USER", "WIZARD", "employee")) {
            val ex = runCatching { insert(email(), bad) }.exceptionOrNull()
            assertNotNull(ex, "role $bad must be rejected")
        }
        assertNotNull(runCatching { insert(e.uppercase(), "EMPLOYEE") }.exceptionOrNull())
        assertNotNull(runCatching { insert(" ${email()}", "EMPLOYEE") }.exceptionOrNull())
        val defaults = jdbc.queryForMap("select active, token_version, version, role from users where email = ?", e)
        assertEquals(true, defaults["active"]); assertEquals(0, defaults["token_version"]); assertEquals(0L, defaults["version"])
    }

    // --- actuator and CORS -----------------------------------------------------------------------------------------

    @Test fun `actuator health is public without details, everything else needs ADMIN`() {
        val body = mvc.perform(get("/actuator/health")).andReturn().response.contentAsString   // 200 or 503 depending on Redis/RabbitMQ
        assertFalse(body.contains("components") || body.contains("jdbc") || body.contains("postgres"), "health must not leak details: $body")

        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator/metrics").header("Authorization", t.caller(Role.EMPLOYEE).bearer)).andExpect(status().isForbidden)
        mvc.perform(get("/actuator/metrics").header("Authorization", t.caller(Role.TECHNICIAN).bearer)).andExpect(status().isForbidden)
        mvc.perform(get("/actuator/metrics").header("Authorization", t.caller(Role.ADMIN).bearer)).andExpect(status().isOk)
    }

    @Test fun `CORS allows the configured origin only`() {
        fun preflight(origin: String) = mvc.perform(
            options("/api/users/me").header("Origin", origin)
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization")
        )
        val allowed = preflight("http://localhost:3000").andExpect(status().isOk).andReturn().response
        assertEquals("http://localhost:3000", allowed.getHeader("Access-Control-Allow-Origin"))
        val denied = preflight("https://evil.example").andExpect(status().isForbidden).andReturn().response
        assertNull(denied.getHeader("Access-Control-Allow-Origin"))
    }
}
