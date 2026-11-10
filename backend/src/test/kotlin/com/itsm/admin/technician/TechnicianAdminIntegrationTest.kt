package com.itsm.admin.technician

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

/** /api/admin/technicians on PostgreSQL 16 (V16, Hibernate validation): authorization, rules, locking, audit, graph integrity. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TechnicianAdminIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/technicians"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var service: TechnicianAdminService

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN)
    }

    private fun call(builder: MockHttpServletRequestBuilder, who: Caller?, body: String? = null): ResultActions {
        if (who != null) builder.header("Authorization", who.bearer)
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(builder)
    }

    private fun category(active: Boolean = true): Long {
        val id = UUID.randomUUID().toString().replace("-", "")
        return jdbc.queryForObject(
            "insert into incident_categories (code, name, active) values (?, ?, ?) returning id", Long::class.java,
            "C" + id.take(10).uppercase(), "Cat $id", active
        )!!
    }

    private fun technician(): Caller = t.caller(Role.TECHNICIAN)

    private fun create(
        who: Caller = technician(), workload: Int = 5, years: Int = 3, available: Boolean = true, lead: Boolean = false,
        reportsTo: Long? = null, skills: String = "[]"
    ): Long = t.body(
        call(post(BASE), admin,
            """{"userId":${who.id},"maxWorkload":$workload,"experienceYears":$years,"available":$available,"isTeamLead":$lead,
               "reportsToUserId":${reportsTo ?: "null"},"skills":$skills}""")
            .andExpect(status().isCreated)
    )["userId"].asLong()

    private fun version(id: Long) = jdbc.queryForObject("select version from technician_profiles where user_id = ?", Long::class.java, id)!!

    private fun details(id: Long, workload: Int = 5, years: Int = 3, available: Boolean = true, lead: Boolean = false, reportsTo: Long? = null, version: Long = version(id)) =
        call(put("$BASE/$id"), admin, """{"maxWorkload":$workload,"experienceYears":$years,"available":$available,"isTeamLead":$lead,"reportsToUserId":${reportsTo ?: "null"},"version":$version}""")

    // --- authorization ---------------------------------------------------------------------------------------------

    @Test fun `401 without authentication and 403 for employees and technicians on every endpoint`() {
        val id = create()
        val requests = listOf<Pair<() -> MockHttpServletRequestBuilder, String?>>(
            { get(BASE) } to null, { get("$BASE/$id") } to null,
            { post(BASE) } to """{"userId":$id,"maxWorkload":5,"experienceYears":1,"available":true}""",
            { put("$BASE/$id") } to """{"maxWorkload":5,"experienceYears":1,"available":true,"isTeamLead":false,"version":0}""",
            { put("$BASE/$id/skills") } to """{"skills":[],"version":0}""",
            { post("$BASE/$id/activate") } to """{"version":0}""",
            { post("$BASE/$id/deactivate") } to """{"version":0}"""
        )
        val employee = t.caller(Role.EMPLOYEE); val technician = technician()
        for ((builder, body) in requests) {
            call(builder(), null, body).andExpect(status().isUnauthorized)
            call(builder(), employee, body).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            call(builder(), technician, body).andExpect(status().isForbidden)
        }
        assertTrue(jdbc.queryForObject("select active from technician_profiles where user_id = ?", Boolean::class.java, id)!!)
    }

    // --- create ----------------------------------------------------------------------------------------------------

    @Test fun `creates a profile for a TECHNICIAN user with skills, and exposes no authentication data`() {
        val who = technician(); val c1 = category(); val c2 = category()
        val body = call(post(BASE), admin, """{"userId":${who.id},"maxWorkload":6,"experienceYears":4,"available":false,
            "skills":[{"categoryId":$c1,"proficiency":5},{"categoryId":$c2,"proficiency":2}]}""")
            .andExpect(status().isCreated).andExpect(jsonPath("$.userId").value(who.id)).andExpect(jsonPath("$.email").value(who.email))
            .andExpect(jsonPath("$.name").value("Test TECHNICIAN")).andExpect(jsonPath("$.userRole").value("TECHNICIAN"))
            .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.maxWorkload").value(6)).andExpect(jsonPath("$.isTeamLead").value(false))
            .andExpect(jsonPath("$.skills.length()").value(2)).andExpect(jsonPath("$.password").doesNotExist())
            .andReturn().response.contentAsString
        assertFalse(body.contains("\$2") || body.contains("tokenVersion") || body.contains("currentWorkload"))
        call(get("$BASE/${who.id}"), admin).andExpect(status().isOk).andExpect(jsonPath("$.skills[0].categoryCode").isNotEmpty)
        assertEquals("TECHNICIAN", jdbc.queryForObject("select role from users where id = ?", String::class.java, who.id))
        call(get("$BASE/987654321"), admin).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test fun `rejects profiles for EMPLOYEE and ADMIN users without changing their role, unknown and deactivated users, and duplicates`() {
        val body = { id: Long -> """{"userId":$id,"maxWorkload":5,"experienceYears":1,"available":true}""" }
        for (role in listOf(Role.EMPLOYEE, Role.ADMIN)) {
            val u = t.caller(role)
            call(post(BASE), admin, body(u.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            assertEquals(role.name, jdbc.queryForObject("select role from users where id = ?", String::class.java, u.id))
            assertEquals(0L, jdbc.queryForObject("select count(*) from technician_profiles where user_id = ?", Long::class.java, u.id))
        }
        call(post(BASE), admin, body(987654321)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        val off = technician(); jdbc.update("update users set active = false where id = ?", off.id)
        call(post(BASE), admin, body(off.id)).andExpect(status().isConflict)

        val who = technician(); create(who)
        call(post(BASE), admin, body(who.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
    }

    @Test fun `validation errors are consistent 400s and create nothing`() {
        val who = technician(); val good = category(); val off = category(active = false)
        fun create(json: String) = call(post(BASE), admin, json)
        create("""{"userId":${who.id}}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        create("""{"userId":${who.id},"maxWorkload":0,"experienceYears":1,"available":true}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        create("""{"userId":${who.id},"maxWorkload":101,"experienceYears":1,"available":true}""").andExpect(status().isBadRequest)
        create("""{"userId":${who.id},"maxWorkload":5,"experienceYears":-1,"available":true}""").andExpect(status().isBadRequest)
        create("""{"userId":${who.id},"maxWorkload":5,"experienceYears":61,"available":true}""").andExpect(status().isBadRequest)
        val base = """"userId":${who.id},"maxWorkload":5,"experienceYears":1,"available":true"""
        create("""{$base,"skills":[{"categoryId":$good,"proficiency":0}]}""").andExpect(status().isBadRequest)
        create("""{$base,"skills":[{"categoryId":$good,"proficiency":6}]}""").andExpect(status().isBadRequest)
        create("""{$base,"skills":[{"categoryId":$good,"proficiency":3},{"categoryId":$good,"proficiency":4}]}""").andExpect(status().isBadRequest)
        create("""{$base,"skills":[{"categoryId":987654321,"proficiency":3}]}""").andExpect(status().isBadRequest)
        create("""{$base,"skills":[{"categoryId":$off,"proficiency":3}]}""").andExpect(status().isBadRequest)
        create("""{$base,"skills":[{"proficiency":3}]}""").andExpect(status().isBadRequest)
        assertEquals(0L, jdbc.queryForObject("select count(*) from technician_profiles where user_id = ?", Long::class.java, who.id))
    }

    // --- update, skills, activation, optimistic locking -------------------------------------------------------------

    @Test fun `updates details, replaces skills, toggles activation, and a stale version is 409 everywhere`() {
        val id = create(); val c1 = category(); val c2 = category()
        val v0 = version(id)
        details(id, workload = 8, years = 10, available = false, version = v0).andExpect(status().isOk)
            .andExpect(jsonPath("$.maxWorkload").value(8)).andExpect(jsonPath("$.experienceYears").value(10)).andExpect(jsonPath("$.available").value(false))
        assertTrue(version(id) > v0)
        details(id, workload = 9, version = v0).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        details(id, workload = 0).andExpect(status().isBadRequest)

        call(put("$BASE/$id/skills"), admin, """{"skills":[{"categoryId":$c1,"proficiency":4}],"version":${version(id)}}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.skills.length()").value(1))
        call(put("$BASE/$id/skills"), admin, """{"skills":[{"categoryId":$c2,"proficiency":1},{"categoryId":$c1,"proficiency":5}],"version":${version(id)}}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.skills.length()").value(2))
        call(put("$BASE/$id/skills"), admin, """{"skills":[],"version":$v0}""").andExpect(status().isConflict)
        call(put("$BASE/$id/skills"), admin, """{"skills":[],"version":${version(id)}}""").andExpect(status().isOk).andExpect(jsonPath("$.skills.length()").value(0))

        call(post("$BASE/$id/deactivate"), admin, """{"version":$v0}""").andExpect(status().isConflict)
        call(post("$BASE/$id/deactivate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        call(post("$BASE/$id/activate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk).andExpect(jsonPath("$.active").value(true))
    }

    @Test fun `skills for a category deactivated later may stay or go, but cannot be newly added`() {
        val id = create(); val c1 = category(); val c2 = category()
        call(put("$BASE/$id/skills"), admin, """{"skills":[{"categoryId":$c1,"proficiency":4}],"version":${version(id)}}""").andExpect(status().isOk)
        jdbc.update("update incident_categories set active = false where id = ?", c1)
        call(put("$BASE/$id/skills"), admin, """{"skills":[{"categoryId":$c1,"proficiency":4},{"categoryId":$c2,"proficiency":2}],"version":${version(id)}}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.skills[?(@.categoryId==$c1)].categoryActive").value(false))
        jdbc.update("update incident_categories set active = false where id = ?", c2)
        val c3 = category(active = false)
        call(put("$BASE/$id/skills"), admin, """{"skills":[{"categoryId":$c1,"proficiency":4},{"categoryId":$c3,"proficiency":2}],"version":${version(id)}}""").andExpect(status().isBadRequest)
    }

    // --- list --------------------------------------------------------------------------------------------------------

    @Test fun `lists with pagination, search, filters and validated sorting`() {
        val cat = category()
        val a = technician(); val b = technician(); val c = technician()
        val ia = create(a, workload = 3, available = true, lead = true, skills = """[{"categoryId":$cat,"proficiency":3}]""")
        val ib = create(b, workload = 9, available = false)
        val ic = create(c, workload = 6, available = true)
        call(post("$BASE/$ic/deactivate"), admin, """{"version":${version(ic)}}""").andExpect(status().isOk)

        fun ids(query: String) = t.body(call(get("$BASE?$query"), admin).andExpect(status().isOk))["items"].map { it["userId"].asLong() }.toSet()
        val mine = setOf(ia, ib, ic)
        assertEquals(setOf(ia, ib), ids("size=100&active=true").intersect(mine))
        assertEquals(setOf(ic), ids("size=100&active=false").intersect(mine))
        assertEquals(setOf(ia), ids("size=100&teamLead=true").intersect(mine))
        assertEquals(setOf(ib), ids("size=100&available=false&active=true").intersect(mine))
        assertEquals(setOf(ia), ids("size=100&categoryId=$cat"))
        assertEquals(setOf(ib), ids("size=100&search=${b.email}"))
        assertEquals(emptySet<Long>(), ids("size=100&search=%25"))     // LIKE wildcards are escaped, not interpreted

        val page0 = t.body(call(get("$BASE?size=2&page=0&sort=maxWorkload&dir=desc"), admin).andExpect(status().isOk))
        assertEquals(2, page0["items"].size()); assertEquals(0, page0["page"].asInt()); assertEquals(2, page0["size"].asInt())
        assertTrue(page0["totalItems"].asLong() >= 3 && page0["totalPages"].asInt() >= 2)
        assertTrue(page0["items"][0]["maxWorkload"].asInt() >= page0["items"][1]["maxWorkload"].asInt())

        call(get("$BASE?sort=password"), admin).andExpect(status().isBadRequest)
        call(get("$BASE?dir=sideways"), admin).andExpect(status().isBadRequest)
        call(get("$BASE?size=1000"), admin).andExpect(status().isBadRequest)
        call(get("$BASE?page=-1"), admin).andExpect(status().isBadRequest)
    }

    // --- team leads and reporting lines ----------------------------------------------------------------------------

    @Test fun `reporting lines need a lead, forbid self and cycles, and protect a lead that still has reports`() {
        val lead = create(lead = true)
        val member = create(reportsTo = lead)
        call(get("$BASE/$member"), admin).andExpect(jsonPath("$.reportsToUserId").value(lead))

        val plain = create()
        details(member, reportsTo = plain).andExpect(status().isBadRequest)                       // not a team lead
        details(member, reportsTo = member).andExpect(status().isBadRequest)                      // self
        details(member, reportsTo = 987654321).andExpect(status().isBadRequest)                   // no profile
        val lead2 = create(lead = true, reportsTo = lead)                                         // a lead may report to a lead
        details(lead, lead = true, reportsTo = lead2).andExpect(status().isConflict)              // cycle lead -> lead2 -> lead
        details(lead, lead = false).andExpect(status().isConflict)                                // others still report to it
        call(post("$BASE/$lead/deactivate"), admin, """{"version":${version(lead)}}""").andExpect(status().isConflict)

        details(member, reportsTo = null).andExpect(status().isOk).andExpect(jsonPath("$.reportsToUserId").doesNotExist())   // move members away...
        call(post("$BASE/$lead2/deactivate"), admin, """{"version":${version(lead2)}}""").andExpect(status().isOk)
        details(lead, lead = true, reportsTo = lead2).andExpect(status().isBadRequest)           // inactive manager
        assertEquals(setOf(lead), setOf(jdbc.queryForObject("select reports_to_user_id from technician_profiles where user_id = ?", Long::class.java, lead2)!!))
    }

    @Test fun `concurrent opposite reporting-line changes cannot create a cycle`() {
        repeat(3) {
            val a = create(lead = true); val b = create(lead = true)
            val va = version(a); val vb = version(b)
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val jobs = listOf(Triple(a, b, va), Triple(b, a, vb)).map { (who, manager, v) ->
                pool.submit<Int> {
                    start.await()
                    t.asCaller(admin) {
                        runCatching { service.update(who, UpdateTechnicianRequest(5, 3, true, true, manager, v)) }.fold({ 200 }, { 409 })
                    }
                }
            }
            start.countDown()
            val codes = jobs.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(1, codes.count { it == 200 }, "exactly one direction may win: $codes")
            val links = jdbc.queryForList("select user_id, reports_to_user_id from technician_profiles where user_id in (?, ?) and reports_to_user_id is not null", a, b)
            assertEquals(1, links.size)
        }
    }

    @Test fun `a manager whose account is inactive cannot be assigned, and concurrent lead changes keep the structure valid`() {
        val leadUser = technician(); val lead = create(leadUser, lead = true); val member = create()
        jdbc.update("update users set active = false where id = ?", leadUser.id)
        details(member, reportsTo = lead).andExpect(status().isBadRequest)
        jdbc.update("update users set active = true where id = ?", leadUser.id)

        // deactivating a lead races adding a report to it, and un-flagging a lead races adding a report: never an invalid result
        repeat(3) {
            val l1 = create(lead = true); val m1 = create(); val l2 = create(lead = true); val m2 = create()
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
            val jobs = listOf(
                { service.setActive(l1, version(l1), false) },
                { service.update(m1, UpdateTechnicianRequest(5, 3, true, false, l1, version(m1))) },
                { service.update(l2, UpdateTechnicianRequest(5, 3, true, false, null, version(l2))) },
                { service.update(m2, UpdateTechnicianRequest(5, 3, true, false, l2, version(m2))) }
            ).map { job -> pool.submit<Boolean> { start.await(); t.asCaller(admin) { runCatching { job() }.isSuccess } } }
            start.countDown(); jobs.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(0L, jdbc.queryForObject(
                "select count(*) from technician_profiles r join technician_profiles m on m.user_id = r.reports_to_user_id where r.active and not m.active", Long::class.java),
                "an active technician must never report to an inactive manager")
            assertEquals(0L, jdbc.queryForObject(
                "select count(*) from technician_profiles r join technician_profiles m on m.user_id = r.reports_to_user_id where not m.is_team_lead", Long::class.java),
                "nobody may report to a profile that is not a team lead")
        }
    }

    // --- role interplay (V16 triggers behind the API) ----------------------------------------------------------------

    @Test fun `a user with an active profile cannot lose the TECHNICIAN role, and a deactivated profile is kept as history`() {
        val who = technician(); val id = create(who)
        call(put("/api/admin/users/${who.id}/role"), admin, """{"role":"EMPLOYEE","version":${jdbc.queryForObject("select version from users where id = ?", Long::class.java, who.id)}}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.detail").value("This user has an active technician profile; deactivate the profile before changing the role."))
        assertEquals("TECHNICIAN", jdbc.queryForObject("select role from users where id = ?", String::class.java, who.id))

        call(post("$BASE/$id/deactivate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk)
        call(put("/api/admin/users/${who.id}/role"), admin, """{"role":"EMPLOYEE","version":${jdbc.queryForObject("select version from users where id = ?", Long::class.java, who.id)}}""")
            .andExpect(status().isOk)
        assertEquals(1L, jdbc.queryForObject("select count(*) from technician_profiles where user_id = ?", Long::class.java, id), "history kept")
        call(post("$BASE/$id/activate"), admin, """{"version":${version(id)}}""").andExpect(status().isConflict)   // no longer a technician
        call(get("$BASE/$id"), admin).andExpect(jsonPath("$.userRole").value("EMPLOYEE")).andExpect(jsonPath("$.active").value(false))
    }

    // --- audit --------------------------------------------------------------------------------------------------------

    @Test fun `every mutation writes an audit row with actor and target and metadata holds no personal data`() {
        val who = technician(); val c1 = category(); val c2 = category()
        val lead = create(lead = true)
        val id = create(who, skills = """[{"categoryId":$c1,"proficiency":2}]""")
        details(id, workload = 7, years = 9, available = false, lead = false, reportsTo = lead).andExpect(status().isOk)
        call(put("$BASE/$id/skills"), admin, """{"skills":[{"categoryId":$c2,"proficiency":5}],"version":${version(id)}}""").andExpect(status().isOk)
        call(post("$BASE/$id/deactivate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk)
        call(post("$BASE/$id/activate"), admin, """{"version":${version(id)}}""").andExpect(status().isOk)

        val rows = t.auditRows("TECHNICIAN", id)
        assertEquals(
            listOf("TECHNICIAN_PROFILE_CREATED", "TECHNICIAN_PROFILE_UPDATED", "TECHNICIAN_REPORTING_CHANGED", "TECHNICIAN_SKILLS_CHANGED",
                "TECHNICIAN_PROFILE_DEACTIVATED", "TECHNICIAN_PROFILE_ACTIVATED"),
            rows.map { it.action }
        )
        rows.forEach { assertEquals("USER", it.actorType); assertEquals(admin.id, it.actorId); assertEquals("ADMIN", it.actorRole); assertEquals(id, it.metadata["targetUserId"].asLong()) }
        assertEquals("ACTIVE" to "INACTIVE", rows[4].previousState to rows[4].newState)
        assertEquals(lead, rows[2].metadata["reportsToAfter"].asLong())
        assertEquals(7, rows[1].metadata["maxWorkloadAfter"].asInt())

        val dump = jdbc.queryForList("select description || ' ' || metadata::text from audit_events where entity_type = 'TECHNICIAN' and entity_id = ?", String::class.java, id.toString()).joinToString(" ")
        for (secret in listOf(who.email, "Test TECHNICIAN", "\$2a\$", "eyJ", "password")) assertFalse(dump.contains(secret, ignoreCase = true), "audit leaked '$secret'")
    }

    @Test fun `failed requests write no audit row and a rolled-back transaction removes both profile and audit row`() {
        val who = technician(); val id = create(who)
        val before = t.auditCount()
        details(id, workload = 0).andExpect(status().isBadRequest)
        details(id, workload = 5, version = -1).andExpect(status().isConflict)
        call(post(BASE), admin, """{"userId":${t.caller(Role.EMPLOYEE).id},"maxWorkload":5,"experienceYears":1,"available":true}""").andExpect(status().isConflict)
        assertEquals(before, t.auditCount())

        val other = technician()
        assertThrows<IllegalStateException> {
            tx.execute {
                t.asCaller(admin) { service.create(CreateTechnicianRequest(other.id, 5, 1, true, false, null, null)) }
                throw IllegalStateException("boom")
            }
        }
        assertEquals(before, t.auditCount())
        assertNull(jdbc.queryForList("select user_id from technician_profiles where user_id = ?", Long::class.java, other.id).firstOrNull())
        assertNotNull(create(other), "the user can still get a profile afterwards")
    }
}
