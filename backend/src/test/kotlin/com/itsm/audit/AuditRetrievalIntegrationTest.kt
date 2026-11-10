package com.itsm.audit

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * GET /api/admin/audit-events on PostgreSQL 16: authorization, filters, boundaries, ordering, paging, detail, metadata
 * safety and the absence of any write path.
 *
 * The audit table is append-only, so tests cannot clean up. Each test therefore writes rows under its OWN unique
 * entity type (the "marker") and every query filters by it, so tests never see each other's rows.
 */
@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuditRetrievalIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/audit-events"
        val T0: Instant = Instant.parse("2026-03-10T12:00:00Z")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var emf: EntityManagerFactory

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller
    private lateinit var marker: String

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN)
        marker = "T" + UUID.randomUUID().toString().replace("-", "").uppercase().take(24)
    }

    private fun insert(
        occurredAt: Instant = T0, action: String = "THING_CREATED", entityType: String = marker, entityId: String? = "1",
        incidentId: String? = null, actorType: String = "USER", actorId: Long? = 7L, actorRole: String? = "ADMIN",
        actorName: String? = null, previousState: String? = null, newState: String? = null,
        description: String = "did a thing", metadata: String = "{}"
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """insert into audit_events (event_id, occurred_at, actor_type, actor_id, actor_role, actor_name, action, entity_type,
                   entity_id, incident_id, previous_state, new_state, description, metadata)
               values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""",
            id, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC), actorType, actorId, actorRole, actorName, action,
            entityType, entityId, incidentId, previousState, newState, description, metadata
        )
        return id
    }

    private fun list(query: String = "", who: Caller? = admin): ResultActions {
        val sep = if (query.isEmpty()) "" else "&"
        val builder = get(URI.create("$BASE?entityType=$marker$sep$query")) // URI: keeps %2B/%20 as written
        if (who != null) builder.header("Authorization", who.bearer)
        return mvc.perform(builder)
    }

    /** Like [list] but WITHOUT the marker filter, for tests of the filters that replace it. */
    private fun raw(path: String, who: Caller? = admin): ResultActions {
        val builder = get(URI.create(path))
        if (who != null) builder.header("Authorization", who.bearer)
        return mvc.perform(builder)
    }

    private fun items(result: ResultActions): List<JsonNode> = t.body(result.andExpect(status().isOk)).get("items").toList()
    private fun ids(result: ResultActions) = items(result).map { it.get("id").asText() }

    // authorization ----------------------------------------------------------------------------------------------

    @Test fun `anonymous callers get 401 and non-admins get 403 on the list and the detail`() {
        val id = insert()
        for (path in listOf("$BASE?entityType=$marker", "$BASE/$id")) {
            raw(path, who = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            for (role in listOf(Role.EMPLOYEE, Role.TECHNICIAN)) {
                raw(path, who = t.caller(role)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }
    }

    @Test fun `an admin gets the page envelope and the documented row shape`() {
        val id = insert(
            action = "THING_UPDATED", entityId = "42", incidentId = "INC7", previousState = "OPEN", newState = "CLOSED",
            description = "changed it", metadata = """{"changedFields":["name","code"],"count":3,"flag":true,"label":"x"}"""
        )
        val body = t.body(list().andExpect(status().isOk))
        assertEquals(0, body.get("page").asInt()); assertEquals(20, body.get("size").asInt())
        assertEquals(1, body.get("totalItems").asInt()); assertEquals(1, body.get("totalPages").asInt())
        val row = body.get("items")[0]
        assertEquals(id.toString(), row.get("id").asText())
        assertEquals("2026-03-10T12:00:00Z", row.get("occurredAt").asText())
        assertEquals("USER", row.at("/actor/type").asText()); assertEquals(7, row.at("/actor/id").asInt())
        assertEquals("ADMIN", row.at("/actor/role").asText()); assertTrue(row.at("/actor/name").isNull)
        assertEquals("THING_UPDATED", row.get("action").asText()); assertEquals(marker, row.get("entityType").asText())
        assertEquals("42", row.get("entityId").asText()); assertEquals("INC7", row.get("incidentId").asText())
        assertEquals("OPEN", row.get("previousState").asText()); assertEquals("CLOSED", row.get("newState").asText())
        assertEquals("changed it", row.get("description").asText())
        assertEquals(listOf("name", "code"), row.at("/metadata/changedFields").map { it.asText() })
        assertEquals(3, row.at("/metadata/count").asInt()); assertTrue(row.at("/metadata/flag").asBoolean())
        // The internal bigint id and the unmapped recorded_at are never exposed; neither is anything email-like.
        assertEquals(setOf("id", "occurredAt", "actor", "action", "entityType", "entityId", "incidentId", "previousState", "newState", "description", "metadata"),
            row.fieldNames().asSequence().toSet())
        assertFalse(row.toString().contains("@"))
    }

    @Test fun `system actors are included and shown by job name`() {
        insert(actorType = "SYSTEM", actorId = null, actorRole = null, actorName = "SLA_MONITOR", action = "SLA_WARNING_RAISED")
        val row = items(list())[0]
        assertEquals("SYSTEM", row.at("/actor/type").asText()); assertEquals("SLA_MONITOR", row.at("/actor/name").asText())
        assertTrue(row.at("/actor/id").isNull); assertTrue(row.at("/actor/role").isNull)
    }

    // paging and ordering ----------------------------------------------------------------------------------------

    @Test fun `pages are disjoint and complete, with totals`() {
        val all = (0 until 5).map { insert(occurredAt = T0.plusSeconds(it.toLong()), entityId = "e$it") }
        val seen = mutableListOf<String>()
        for (page in 0..2) {
            val body = t.body(list("size=2&page=$page").andExpect(status().isOk))
            assertEquals(5, body.get("totalItems").asInt()); assertEquals(3, body.get("totalPages").asInt())
            assertEquals(if (page == 2) 1 else 2, body.get("items").size())
            seen += body.get("items").map { it.get("id").asText() }
        }
        assertEquals(all.reversed().map { it.toString() }, seen) // newest first by default
        assertEquals(0, items(list("size=2&page=3")).size) // past the end: empty page, not an error
    }

    @Test fun `invalid paging is a 400 and a page that cannot be addressed is rejected`() {
        for (q in listOf("size=0", "size=101", "page=-1", "size=abc")) list(q).andExpect(status().isBadRequest)
        list("page=2000000000&size=100").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("page"))
        list("size=100").andExpect(status().isOk)
    }

    @Test fun `rows with the same timestamp keep a stable total order across pages and directions`() {
        val created = (0 until 6).map { insert(occurredAt = T0, entityId = "e$it") } // identical occurred_at
        val desc = (0..2).flatMap { ids(list("size=2&page=$it&sort=occurredAt&dir=desc")) }
        val asc = (0..2).flatMap { ids(list("size=2&page=$it&sort=occurredAt&dir=asc")) }
        assertEquals(created.reversed().map { it.toString() }, desc) // tie-break is insertion order (id), same direction
        assertEquals(created.map { it.toString() }, asc)
        assertEquals(desc, (0..2).flatMap { ids(list("size=2&page=$it")) }) // default == occurredAt desc
        assertEquals(desc, (0..2).flatMap { ids(list("size=2&page=$it")) }) // repeatable
    }

    @Test fun `sorting by another key falls back to time then id, and bad sort input is a 400`() {
        val b1 = insert(action = "THING_B", occurredAt = T0.plusSeconds(1))
        val a = insert(action = "THING_A", occurredAt = T0)
        val b2 = insert(action = "THING_B", occurredAt = T0.plusSeconds(2))
        assertEquals(listOf(a, b2, b1).map { it.toString() }, ids(list("sort=action&dir=asc")))
        assertEquals(listOf(b2, b1, a).map { it.toString() }, ids(list("sort=action&dir=desc")))
        for (q in listOf("sort=description", "sort=id", "sort=occurred_at", "sort=metadata;drop", "dir=sideways")) {
            list(q).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }
        for (key in AuditQueryService.SORTS.keys) list("sort=$key").andExpect(status().isOk)
    }

    // filters ----------------------------------------------------------------------------------------------------

    @Test fun `every filter narrows the result, exactly, and they combine with AND`() {
        val created = insert(action = "THING_CREATED", entityId = "1", actorId = 7L, actorRole = "ADMIN")
        val updated = insert(action = "THING_UPDATED", entityId = "1", actorId = 8L, actorRole = "TECHNICIAN", occurredAt = T0.plusSeconds(1))
        val sys = insert(action = "THING_EXPIRED", entityId = "2", actorType = "SYSTEM", actorId = null, actorRole = null,
            actorName = "SLA_MONITOR", incidentId = "INC9", occurredAt = T0.plusSeconds(2))
        fun q(query: String) = ids(list(query)).toSet()
        assertEquals(setOf(updated.toString()), q("action=THING_UPDATED"))
        assertEquals(setOf(created.toString()), q("actorId=7"))
        assertEquals(setOf(updated.toString()), q("actorRole=TECHNICIAN"))
        assertEquals(setOf(updated.toString()), q("actorRole=technician")) // role is case-insensitive
        assertEquals(setOf(sys.toString()), q("actorType=SYSTEM"))
        assertEquals(setOf(created.toString(), updated.toString()), q("entityId=1"))
        assertEquals(setOf(sys.toString()), q("entityId=2"))
        assertEquals(setOf(sys.toString()), q("incidentId=INC9"))
        assertEquals(setOf(created.toString(), updated.toString(), sys.toString()), q(""))
        assertEquals(setOf(updated.toString()), q("entityId=1&actorRole=TECHNICIAN&action=THING_UPDATED"))
        assertEquals(emptySet<String>(), q("entityId=1&actorRole=TECHNICIAN&action=THING_CREATED"))
        assertEquals(emptySet<String>(), q("actorId=999"))
        assertEquals(emptySet<String>(), q("action=THING_NOPE"))
        // entityType is the marker filter list() already adds; a different type matches nothing of ours.
        assertEquals(0, t.body(raw("$BASE?entityType=OTHER_TYPE_X&entityId=1").andExpect(status().isOk)).get("totalItems").asInt())
    }

    @Test fun `entity filters are direct matches only, an incident id never matches entityId`() {
        val onIncident = insert(entityType = marker, entityId = "INC77", incidentId = "INC77")
        val aboutIncident = insert(entityType = marker, entityId = "5", incidentId = "INC77", action = "THING_NOTED")
        assertEquals(setOf(onIncident.toString()), ids(list("entityId=INC77")).toSet())
        assertEquals(setOf(onIncident.toString(), aboutIncident.toString()), ids(list("incidentId=INC77")).toSet())
        assertEquals(emptySet<String>(), ids(list("entityId=INC7")).toSet()) // no prefix / substring matching
        assertEquals(emptySet<String>(), ids(list("entityId=inc77")).toSet()) // case-sensitive, like the stored id
    }

    @Test fun `malformed filters are field-level 400s and nothing is silently ignored`() {
        for ((q, field) in listOf(
            "action=lower_case" to "action", "action=AB" to "action", "entityType=has-dash" to "entityType",
            "entityId=a%20b" to "entityId", "incidentId=" + "X".repeat(31) to "incidentId", "actorId=abc" to "actorId",
            "actorId=0" to "actorId", "actorId=-3" to "actorId", "actorRole=HACKER" to "actorRole", "actorType=ROBOT" to "actorType",
            "from=yesterday" to "from", "to=2026-13-45T00:00:00Z" to "to", "from=2026-03-10" to "from"
        )) {
            raw("$BASE?$q").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
        }
        raw("$BASE?from=2026-03-11T00:00:00Z&to=2026-03-10T00:00:00Z").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("to"))
    }

    @Test fun `blank filters mean no filter`() {
        insert()
        assertEquals(1, items(list("action=&actorId=&actorRole=%20&from=&to=")).size)
    }

    // date boundaries --------------------------------------------------------------------------------------------

    @Test fun `from is inclusive and to is exclusive, with offsets normalised to UTC`() {
        val before = insert(occurredAt = T0.minusMillis(1))
        val atFrom = insert(occurredAt = T0)
        val inside = insert(occurredAt = T0.plusSeconds(30))
        val beforeTo = insert(occurredAt = T0.plusSeconds(60).minusMillis(1))
        val atTo = insert(occurredAt = T0.plusSeconds(60))
        fun q(query: String) = ids(list(query)).toSet()
        val range = "from=2026-03-10T12:00:00Z&to=2026-03-10T12:01:00Z"
        assertEquals(setOf(atFrom, inside, beforeTo).map { it.toString() }.toSet(), q(range))
        assertEquals(setOf(atTo.toString()), q("from=2026-03-10T12:01:00Z&to=2026-03-10T12:01:00.001Z"))
        assertEquals(setOf(before, atFrom, inside, beforeTo).map { it.toString() }.toSet(), q("to=2026-03-10T12:01:00Z"))
        assertEquals(setOf(atTo.toString()), q("from=2026-03-10T12:01:00Z"))
        // same instants written with a +02:00 offset (encoded) select the same rows
        assertEquals(q(range), q("from=2026-03-10T14:00:00%2B02:00&to=2026-03-10T14:01:00%2B02:00"))
        assertEquals(emptySet<String>(), q("from=2026-03-10T12:00:00Z&to=2026-03-10T12:00:00Z")) // empty half-open range
    }

    @Test fun `a whole UTC day is from midnight to the next midnight`() {
        val lastOfPrevious = insert(occurredAt = Instant.parse("2026-03-09T23:59:59.999Z"))
        val first = insert(occurredAt = Instant.parse("2026-03-10T00:00:00Z"))
        val last = insert(occurredAt = Instant.parse("2026-03-10T23:59:59.999Z"))
        val nextDay = insert(occurredAt = Instant.parse("2026-03-11T00:00:00Z"))
        assertEquals(setOf(first, last).map { it.toString() }.toSet(),
            ids(list("from=2026-03-10T00:00:00Z&to=2026-03-11T00:00:00Z")).toSet())
        assertFalse(ids(list()).isEmpty() || lastOfPrevious == nextDay)
    }

    // detail -----------------------------------------------------------------------------------------------------

    @Test fun `detail returns the same shape as a list row, 404 for an unknown id and 400 for a malformed one`() {
        val id = insert(description = "detail please", metadata = """{"k":"v"}""")
        val detail = t.body(raw("$BASE/$id").andExpect(status().isOk))
        assertEquals(items(list())[0], detail)
        assertEquals("detail please", detail.get("description").asText())
        raw("$BASE/${UUID.randomUUID()}").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        raw("$BASE/not-a-uuid").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        raw("$BASE/1").andExpect(status().isBadRequest) // the internal bigint is not an accepted identifier
    }

    // metadata ---------------------------------------------------------------------------------------------------

    @Test fun `metadata outside the documented shape is dropped on the way out and the rest is kept`() {
        insert(metadata = """{"ok":"v","n":1.5,"b":false,"list":["a",2,null,{"x":1},[3]],"nested":{"secret":"s"},"nothing":null}""")
        val metadata = items(list())[0].get("metadata")
        assertEquals(setOf("ok", "n", "b", "list"), metadata.fieldNames().asSequence().toSet())
        assertEquals("v", metadata.get("ok").asText()); assertEquals(1.5, metadata.get("n").asDouble())
        assertEquals(listOf("a", "2"), metadata.get("list").map { it.asText() })
        assertFalse(metadata.toString().contains("secret"))
    }

    @Test fun `an empty metadata object is returned as an empty object`() {
        insert()
        val metadata = items(list())[0].get("metadata")
        assertTrue(metadata.isObject); assertEquals(0, metadata.size())
    }

    @Test fun `markup in stored text is returned as inert JSON text, never interpreted`() {
        insert(description = "<script>alert(1)</script>", metadata = """{"html":"<img src=x onerror=alert(1)>"}""")
        val row = items(list())[0]
        assertEquals("<script>alert(1)</script>", row.get("description").asText())
        assertEquals("<img src=x onerror=alert(1)>", row.at("/metadata/html").asText())
    }

    // no write path ----------------------------------------------------------------------------------------------

    @Test fun `there is no way to create, change or delete an audit event through the API`() {
        val id = insert()
        val before = t.auditCount()
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (path in listOf(BASE, "$BASE/$id")) {
                mvc.perform(request(method, path).header("Authorization", admin.bearer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed)
            }
        }
        assertEquals(before, t.auditCount())
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_events where event_id = ?", Long::class.java, id))
    }

    // efficiency -------------------------------------------------------------------------------------------------

    @Test fun `a page costs one select and one count and loads only the requested rows`() {
        repeat(30) { insert(occurredAt = T0.plusSeconds(it.toLong())) }
        val stats = emf.unwrap(SessionFactory::class.java).statistics
        stats.clear()
        val body = t.body(list("size=5").andExpect(status().isOk))
        assertEquals(30, body.get("totalItems").asInt()); assertEquals(5, body.get("items").size())
        assertEquals(5, stats.getEntityStatistics(AuditEventEntity::class.java.name).loadCount, "only the page's rows are hydrated")
        assertEquals(2, stats.queryExecutionCount, "one count + one page select, no per-row queries")
    }
}
