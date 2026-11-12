package com.itsm.admin.user

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.spi.UserDirectory
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** /api/admin/users end to end on PostgreSQL 16: authorization, mutations, token invalidation, guards, optimistic locking, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UserAdminIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/users"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var service: UserAdminService
    @Autowired lateinit var directory: UserDirectory

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN)
    }

    private fun email() = "m-${UUID.randomUUID().toString().take(8)}@campus.example"

    private fun call(builder: MockHttpServletRequestBuilder, who: Caller?, body: String? = null): ResultActions {
        if (who != null) builder.header("Authorization", who.bearer)
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(builder)
    }

    private fun create(role: String = "EMPLOYEE", e: String = email(), password: String = "Passw0rd!"): Long =
        t.body(call(post(BASE), admin, """{"email":"$e","name":"New User","password":"$password","role":"$role"}""")
            .andExpect(status().isCreated)).get("id").asLong()

    private fun version(id: Long) = jdbc.queryForObject("select version from users where id = ?", Long::class.java, id)!!
    private fun tokenVersion(id: Long) = jdbc.queryForObject("select token_version from users where id = ?", Int::class.java, id)!!
    private fun meStatus(bearer: String) = mvc.perform(get("/api/users/me").header("Authorization", bearer)).andReturn().response.status

    // --- authorization ---------------------------------------------------------------------------------------------

    @Test fun `401 without authentication and 403 for employees and technicians on every endpoint`() {
        val target = create()
        val requests = listOf<Pair<() -> MockHttpServletRequestBuilder, String?>>(
            { get(BASE) } to null, { get("$BASE/$target") } to null,
            { post(BASE) } to """{"email":"${email()}","name":"X","password":"Passw0rd!","role":"ADMIN"}""",
            { put("$BASE/$target") } to """{"email":"${email()}","name":"X","version":0}""",
            { put("$BASE/$target/role") } to """{"role":"ADMIN","version":0}""",
            { post("$BASE/$target/activate") } to """{"version":0}""",
            { post("$BASE/$target/deactivate") } to """{"version":0}""",
            { post("$BASE/$target/reset-password") } to """{"password":"Passw0rd!2"}""",
            { post("$BASE/$target/revoke-sessions") } to null
        )
        val employee = t.caller(Role.EMPLOYEE); val technician = t.caller(Role.TECHNICIAN)
        for ((builder, body) in requests) {
            call(builder(), null, body).andExpect(status().isUnauthorized)
            call(builder(), employee, body).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            call(builder(), technician, body).andExpect(status().isForbidden)
        }
        assertEquals("EMPLOYEE", jdbc.queryForObject("select role from users where id = ?", String::class.java, target))
    }

    // --- create / read / update / list -----------------------------------------------------------------------------

    @Test fun `admin creates users of every role, reads, lists with filters and updates details`() {
        val e = email()
        val created = t.body(call(post(BASE), admin, """{"email":"${e.uppercase()}","name":" Tess Tech ","password":"Passw0rd!","role":"technician"}""")
            .andExpect(status().isCreated).andExpect(jsonPath("$.role").value("TECHNICIAN")).andExpect(jsonPath("$.email").value(e))
            .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.tokenVersion").doesNotExist()))
        val id = created["id"].asLong()
        val hash = jdbc.queryForObject("select password from users where id = ?", String::class.java, id)!!
        assertTrue(hash.startsWith("\$2") && !hash.contains("Passw0rd"))

        call(get("$BASE/$id"), admin).andExpect(status().isOk).andExpect(jsonPath("$.name").value("Tess Tech"))
        call(get("$BASE/999999999"), admin).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        call(get("$BASE?role=TECHNICIAN&active=true&search=${e.take(10)}"), admin).andExpect(status().isOk)
            .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(id))
        call(get("$BASE?role=ADMIN&search=${e.take(10)}"), admin).andExpect(jsonPath("$.totalItems").value(0))
        call(get("$BASE?role=WIZARD"), admin).andExpect(status().isBadRequest)
        call(get("$BASE?size=1000"), admin).andExpect(status().isBadRequest)
        call(get("$BASE?sort=password"), admin).andExpect(status().isBadRequest)

        val newEmail = email()
        call(put("$BASE/$id"), admin, """{"email":"$newEmail","name":"Tess T.","version":${version(id)}}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.email").value(newEmail)).andExpect(jsonPath("$.name").value("Tess T."))
        // the user can sign in with the created password
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$newEmail","password":"Passw0rd!"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.user.role").value("TECHNICIAN"))
    }

    @Test fun `validation errors, duplicate emails and a stale version are 400 and 409`() {
        val e = email(); val id = create(e = e)
        call(post(BASE), admin, """{"email":"x","name":"X","password":"Passw0rd!","role":"EMPLOYEE"}""").andExpect(status().isBadRequest)
        call(post(BASE), admin, """{"email":"${email()}","name":"X","password":"Passw0rd!","role":"USER"}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        call(post(BASE), admin, """{"email":"${email()}","name":"X","password":"short","role":"EMPLOYEE"}""").andExpect(status().isBadRequest)
        call(post(BASE), admin, """{"email":"${e.uppercase()}","name":"X","password":"Passw0rd!","role":"EMPLOYEE"}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))

        val other = email(); create(e = other)
        call(put("$BASE/$id"), admin, """{"email":"$other","name":"X","version":${version(id)}}""").andExpect(status().isConflict)

        val stale = version(id)
        call(put("$BASE/$id"), admin, """{"email":"$e","name":"Renamed","version":$stale}""").andExpect(status().isOk)
        call(put("$BASE/$id"), admin, """{"email":"$e","name":"Again","version":$stale}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        call(put("$BASE/$id/role"), admin, """{"role":"ADMIN","version":$stale}""").andExpect(status().isConflict)
        call(post("$BASE/$id/deactivate"), admin, """{"version":$stale}""").andExpect(status().isConflict)
        assertEquals("Renamed", jdbc.queryForObject("select name from users where id = ?", String::class.java, id))
    }

    // --- token invalidation ----------------------------------------------------------------------------------------

    @Test fun `role change, deactivation, password reset and explicit revoke each invalidate existing tokens`() {
        fun session(): Pair<Long, String> {
            val id = create(); val e = jdbc.queryForObject("select email from users where id = ?", String::class.java, id)!!
            val token = t.body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$e","password":"Passw0rd!"}""")).andExpect(status().isOk))["token"].asText()
            assertEquals(200, meStatus("Bearer $token")); return id to "Bearer $token"
        }

        val (roleId, roleToken) = session()
        call(put("$BASE/$roleId/role"), admin, """{"role":"TECHNICIAN","version":${version(roleId)}}""").andExpect(status().isOk).andExpect(jsonPath("$.role").value("TECHNICIAN"))
        assertEquals(401, meStatus(roleToken))

        val (offId, offToken) = session()
        call(post("$BASE/$offId/deactivate"), admin, """{"version":${version(offId)}}""").andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        assertEquals(401, meStatus(offToken))
        call(post("$BASE/$offId/activate"), admin, """{"version":${version(offId)}}""").andExpect(status().isOk).andExpect(jsonPath("$.active").value(true))
        assertEquals(401, meStatus(offToken), "reactivation must not revive old tokens")

        val (pwId, pwToken) = session()
        call(post("$BASE/$pwId/reset-password"), admin, """{"password":"BrandNew#42"}""").andExpect(status().isOk)
        assertEquals(401, meStatus(pwToken))
        val e = jdbc.queryForObject("select email from users where id = ?", String::class.java, pwId)!!
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$e","password":"Passw0rd!"}""")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$e","password":"BrandNew#42"}""")).andExpect(status().isOk)

        val (revId, revToken) = session()
        call(post("$BASE/$revId/revoke-sessions"), admin).andExpect(status().isOk)
        assertEquals(401, meStatus(revToken))
        call(post("$BASE/$revId/reset-password"), admin, """{"password":"x"}""").andExpect(status().isBadRequest)
    }

    // --- last admin and self-lockout -------------------------------------------------------------------------------

    @Test fun `the last active admin cannot be demoted or deactivated, and admins cannot do it to themselves`() {
        jdbc.update("update users set active = false where role = 'ADMIN' and id <> ?", admin.id)   // admin is now the only active ADMIN
        val v = version(admin.id)
        call(put("$BASE/${admin.id}/role"), admin, """{"role":"EMPLOYEE","version":$v}""").andExpect(status().isConflict)
        call(post("$BASE/${admin.id}/deactivate"), admin, """{"version":$v}""").andExpect(status().isConflict)

        val second = t.caller(Role.ADMIN)
        // with a second active admin the SELF rule still applies...
        call(post("$BASE/${admin.id}/deactivate"), admin, """{"version":$v}""").andExpect(status().isConflict)
        call(put("$BASE/${admin.id}/role"), admin, """{"role":"EMPLOYEE","version":$v}""").andExpect(status().isConflict)
        // ...but the other admin can do it
        call(post("$BASE/${admin.id}/deactivate"), second, """{"version":$v}""").andExpect(status().isOk)
        // and now `second` is the last one
        call(post("$BASE/${second.id}/deactivate"), second, """{"version":${version(second.id)}}""").andExpect(status().isConflict)
        assertEquals(1L, users.countByRoleAndActiveTrue("ADMIN"))
    }

    @Test fun `concurrent demotions of the two remaining admins cannot leave zero admins`() {
        jdbc.update("update users set active = false where role = 'ADMIN'")
        val a = t.caller(Role.ADMIN); val b = t.caller(Role.ADMIN)
        val va = version(a.id); val vb = version(b.id)
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val results = listOf(a to (b to vb), b to (a to va)).map { (actor, target) ->
            pool.submit<Int> {
                start.await()
                t.asCaller(actor) {
                    runCatching { service.changeRole(target.first.id, ChangeRoleRequest("EMPLOYEE", target.second)) }.fold({ 200 }, { 409 })
                }
            }
        }
        start.countDown()
        val codes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, codes.count { it == 200 }, "exactly one demotion may succeed: $codes")
        assertEquals(1L, users.countByRoleAndActiveTrue("ADMIN"))
    }

    // --- audit ------------------------------------------------------------------------------------------------------

    @Test fun `every successful mutation writes an audit row with actor, target and before-after, and none contains secrets`() {
        val id = create("EMPLOYEE", password = "Sup3rSecret!pw")
        call(put("$BASE/$id/role"), admin, """{"role":"TECHNICIAN","version":${version(id)}}""").andExpect(status().isOk)
        call(post("$BASE/$id/deactivate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk)
        call(post("$BASE/$id/activate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk)
        call(put("$BASE/$id"), admin, """{"email":"${email()}","name":"Changed Name","version":${version(id)}}""").andExpect(status().isOk)
        call(post("$BASE/$id/reset-password"), admin, """{"password":"N3wSecret!pw"}""").andExpect(status().isOk)
        call(post("$BASE/$id/revoke-sessions"), admin).andExpect(status().isOk)

        val rows = t.auditRows("USER", id)
        assertEquals(
            listOf("USER_CREATED", "USER_ROLE_CHANGED", "USER_DEACTIVATED", "USER_ACTIVATED", "USER_UPDATED", "USER_PASSWORD_RESET", "USER_SESSIONS_REVOKED"),
            rows.map { it.action }
        )
        rows.forEach { assertEquals("USER", it.actorType); assertEquals(admin.id, it.actorId); assertEquals("ADMIN", it.actorRole); assertEquals(id, it.metadata["targetUserId"].asLong()) }
        assertEquals("EMPLOYEE" to "TECHNICIAN", rows[1].previousState to rows[1].newState)
        assertEquals("ACTIVE" to "INACTIVE", rows[2].previousState to rows[2].newState)

        val dump = jdbc.queryForList("select description || ' ' || metadata::text from audit_events where entity_type = 'USER' and entity_id = ?", String::class.java, id.toString()).joinToString(" ")
        for (secret in listOf("Sup3rSecret", "N3wSecret", "\$2a\$", "\$2b\$", "Changed Name", "eyJ")) assertFalse(dump.contains(secret), "audit leaked '$secret'")
    }

    @Test fun `failed requests write no audit row, and a rolled-back transaction removes the audit row with the user`() {
        val before = t.auditCount()
        val id = create()
        val afterCreate = t.auditCount()
        call(post(BASE), admin, """{"email":"bad","name":"X","password":"Passw0rd!","role":"EMPLOYEE"}""").andExpect(status().isBadRequest)
        call(post(BASE), admin, """{"email":"${email()}","name":"X","password":"Passw0rd!","role":"USER"}""").andExpect(status().isBadRequest)
        call(put("$BASE/$id/role"), admin, """{"role":"ADMIN","version":-5}""").andExpect(status().isConflict)
        call(put("$BASE/987654321/role"), admin, """{"role":"ADMIN","version":0}""").andExpect(status().isNotFound)
        assertEquals(afterCreate, t.auditCount())
        assertEquals(before + 1, afterCreate)

        val e = email()
        assertThrows<IllegalStateException> {
            tx.execute {
                t.asCaller(admin) { service.create(CreateUserRequest(e, "Rolled Back", "Passw0rd!", "EMPLOYEE")) }
                throw IllegalStateException("boom")
            }
        }
        assertEquals(afterCreate, t.auditCount())
        assertNull(jdbc.queryForList("select id from users where email = ?", Long::class.java, e).firstOrNull())
    }

    // --- UserDirectory ---------------------------------------------------------------------------------------------

    @Test fun `UserDirectory returns immutable summaries and active admins only`() {
        val emp = t.caller(Role.EMPLOYEE)
        val s = directory.requireById(emp.id)
        assertEquals(emp.email, s.email); assertEquals(Role.EMPLOYEE, s.role); assertTrue(s.active)
        assertNull(directory.findById(987654321))
        assertNull(directory.findTechnician(emp.id))   // technician profiles arrive with B6

        val off = t.caller(Role.ADMIN)
        jdbc.update("update users set active = false where id = ?", off.id)
        val admins = directory.listAdmins()
        assertTrue(admins.any { it.id == admin.id } && admins.none { it.id == off.id } && admins.all { it.role == Role.ADMIN && it.active })
        assertFalse(directory.requireById(off.id).active)
    }
}
