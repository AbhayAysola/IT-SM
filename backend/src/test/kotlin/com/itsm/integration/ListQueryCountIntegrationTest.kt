package com.itsm.integration

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.admin.technician.StatementCounter
import com.itsm.admin.technician.StatementCountingConfig
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Evidence that the P1 admin list endpoints are constant-query: the number of SQL statements for one page (size 100) is
 * the same with a handful of rows as with several times as many, for the plain listing AND for representative filtered,
 * searched and sorted listings, including rows that carry collections (category keywords, service dependencies,
 * technician skills). Each listing also has its MEASURED statement count pinned, so a listing that stays constant but
 * becomes more expensive is noticed too. If a count changes on purpose, update the number and say why in the commit.
 *
 * Detection power is demonstrated twice: [the detector fails a listing that issues one query per row] proves the
 * harness (counter plus comparison) reports growth, and the B9 review also injected a real per-row query into a
 * production list method and confirmed this class failed (recorded in the B9 report, not kept in the code).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StatementCountingConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class ListQueryCountIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        private const val ADMIN = "/api/admin"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val t by lazy { AdminApiTestSupport(users, jwt, jdbc) }
    private val admin: Caller by lazy { t.caller(Role.ADMIN) }
    private val marker = letters(6)

    /** [grows] is false for a filter whose result set does not grow with the seeded rows (the seeded global SLA policies). */
    private data class Listing(val name: String, val path: String, val statements: Int, val grows: Boolean = true)

    private fun letters(n: Int = 8) = UUID.randomUUID().toString().replace("-", "").take(n).map { 'a' + it.digitToInt(16) }.joinToString("")

    private fun create(path: String, body: Map<String, Any?>): JsonNode = t.body(
        mvc.perform(post("$ADMIN/$path").header("Authorization", admin.bearer).contentType(MediaType.APPLICATION_JSON)
            .content(t.json.writeValueAsString(body))).andExpect(status().isCreated)
    )

    /** One category (2 keywords), one service depending on [dependsOn], one P2 service policy, one technician with 2 skills. */
    private fun seedRow(sharedCategory: Long, dependsOn: Long?): Long {
        val category = create("categories", mapOf("code" to "QC_" + letters().uppercase(), "name" to "Cat $marker " + letters(), "keywords" to listOf("kw" + letters(), "kw" + letters())))
        val service = create("services", mapOf("name" to "Svc $marker " + letters(), "businessCriticality" to 3, "dependsOnServiceIds" to listOfNotNull(dependsOn)))
        create("sla-policies", mapOf("serviceId" to service["id"].asLong(), "priority" to "P2", "responseMinutes" to 30, "resolutionMinutes" to 240))
        val tech = t.caller(Role.TECHNICIAN)
        create(
            "technicians", mapOf(
                "userId" to tech.id, "maxWorkload" to 5, "experienceYears" to 2, "available" to true, "isTeamLead" to false,
                "skills" to listOf(mapOf("categoryId" to category["id"].asLong(), "proficiency" to 3), mapOf("categoryId" to sharedCategory, "proficiency" to 2))
            )
        )
        return service["id"].asLong()
    }

    private fun measure(block: () -> Unit): Int { val before = StatementCounter.count.get(); block(); return StatementCounter.count.get() - before }

    /** The statement count of one request, after a warm-up request, and the number of rows it returned. */
    private fun statementsFor(path: String): Pair<Int, Long> {
        mvc.perform(get(path).header("Authorization", admin.bearer)).andExpect(status().isOk) // warm-up
        var body: JsonNode? = null
        val statements = measure { body = t.body(mvc.perform(get(path).header("Authorization", admin.bearer)).andExpect(status().isOk)) }
        return statements to body!!["totalItems"].asLong()
    }

    /** The detector: fails when the cost grew while the data grew. Shared by the real listings and the sensitivity test. */
    private fun assertConstant(name: String, smallStatements: Int, smallRows: Long, largeStatements: Int, largeRows: Long) {
        assertTrue(largeRows > smallRows, "$name: the larger data set must actually contain more rows ($smallRows -> $largeRows)")
        assertEquals(smallStatements, largeStatements, "$name: statement count grew with the number of rows ($smallRows rows -> $largeRows rows): N+1")
    }

    private fun listings(sharedCategory: Long): List<Listing> = listOf(
        Listing("users", "$ADMIN/users?size=100", 2),
        Listing("users filtered (role, active, dir)", "$ADMIN/users?size=100&role=TECHNICIAN&active=true&dir=desc", 2),
        Listing("users searched", "$ADMIN/users?size=100&search=campus.example", 2),
        Listing("technicians", "$ADMIN/technicians?size=100", 4),
        Listing("technicians filtered (available, teamLead)", "$ADMIN/technicians?size=100&available=true&teamLead=false", 4),
        Listing("technicians by skill category", "$ADMIN/technicians?size=100&categoryId=$sharedCategory", 4),
        Listing("technicians searched", "$ADMIN/technicians?size=100&search=technician", 4),
        Listing("categories", "$ADMIN/categories?size=100", 3),
        Listing("categories searched and sorted", "$ADMIN/categories?size=100&search=$marker&sort=name&dir=desc", 3),
        Listing("categories active only", "$ADMIN/categories?size=100&active=true", 3),
        Listing("services", "$ADMIN/services?size=100", 5),
        Listing("services searched", "$ADMIN/services?size=100&search=$marker", 5),
        Listing("services by criticality", "$ADMIN/services?size=100&criticality=3", 5),
        Listing("sla-policies", "$ADMIN/sla-policies?size=100", 3),
        Listing("sla-policies by priority", "$ADMIN/sla-policies?size=100&priority=P2", 3),
        Listing("sla-policies global only", "$ADMIN/sla-policies?size=100&global=true", 2, grows = false), // 2: no service references to resolve, so one statement fewer
        Listing("sla-policies active only", "$ADMIN/sla-policies?size=100&active=true", 3),
        Listing("audit-events", "$ADMIN/audit-events?size=100", 3),
        Listing("audit-events by actor", "$ADMIN/audit-events?size=100&actorId=${admin.id}", 3),
        Listing("audit-events by time, sorted by action", "$ADMIN/audit-events?size=100&from=2000-01-01T00:00:00Z&sort=action&dir=asc", 3)
    )

    @Test fun `every admin listing, plain or filtered, issues the same pinned number of statements for 3 rows as for 20 rows`() {
        val shared = create("categories", mapOf("code" to "QC_SHARED_" + letters().uppercase(), "name" to "Shared $marker", "keywords" to listOf("shared")))["id"].asLong()
        val all = listings(shared)

        var previous: Long? = null
        repeat(3) { previous = seedRow(shared, previous) }
        val small = all.associateWith { statementsFor(it.path) }

        repeat(17) { previous = seedRow(shared, previous) }
        val large = all.associateWith { statementsFor(it.path) }

        all.forEach { listing ->
            val (smallStatements, smallRows) = small.getValue(listing)
            val (largeStatements, largeRows) = large.getValue(listing)
            println("QUERY-COUNT ${listing.name}: $smallStatements statements for $smallRows rows, $largeStatements statements for $largeRows rows")
            if (listing.grows) assertConstant(listing.name, smallStatements, smallRows, largeStatements, largeRows)
            else assertEquals(smallStatements, largeStatements, "${listing.name}: statement count changed")
            assertTrue(largeRows > 0, "${listing.name}: the listing must return rows, otherwise it measures nothing")
            assertEquals(listing.statements, largeStatements, "${listing.name}: the measured statement count changed (pinned ${listing.statements})")
        }
    }

    /**
     * A listing that fetches the page and then asks the database once per row is exactly an N+1. The same counter and the
     * same detector must flag it; if this test ever passes while the detector stays silent, the harness is blind.
     */
    @Test fun `the detector fails a listing that issues one query per row`() {
        repeat(25) { t.caller(Role.EMPLOYEE) }
        fun perRowListing(n: Int): Pair<Int, Long> {
            var rows = 0L
            val statements = measure {
                val page = users.findAll(PageRequest.of(0, n))
                page.content.forEach { users.existsById(it.id) }   // the per-row query
                rows = page.content.size.toLong()
            }
            return statements to rows
        }
        val (smallStatements, smallRows) = perRowListing(3)
        val (largeStatements, largeRows) = perRowListing(20)
        assertTrue(largeStatements > smallStatements, "the counter must see the per-row queries ($smallStatements -> $largeStatements)")
        val failure = assertThrows(AssertionError::class.java) { assertConstant("per-row listing", smallStatements, smallRows, largeStatements, largeRows) }
        assertTrue(failure.message!!.contains("N+1"), "the detector must name the problem: ${failure.message}")
    }
}
