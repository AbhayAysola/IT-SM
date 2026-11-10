package com.itsm.audit

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.IncidentAccessPolicy
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for P2's [IncidentAccessPolicy] (it exists only in this test, never in main). It records every call so
 * the tests can prove the endpoint DELEGATES the decision instead of making its own.
 */
class FakeIncidentAccessPolicy : IncidentAccessPolicy {
    val viewers = ConcurrentHashMap<String, Set<Long>>() // incident id -> user ids allowed; absent key = unknown incident
    val calls = CopyOnWriteArrayList<Pair<Long, String>>()

    override fun canView(user: CurrentUser, incidentId: String): Boolean {
        calls += user.id to incidentId
        val allowed = viewers[incidentId] ?: throw ResourceNotFound("Incident", incidentId)
        return user.id in allowed
    }
}

@TestConfiguration
class FakePolicyConfig {
    @Bean @Primary fun fakeIncidentAccessPolicy() = FakeIncidentAccessPolicy()
}

/** GET /api/incidents/{id}/audit when a provider EXISTS: access is exactly what the policy says. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakePolicyConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class IncidentAuditIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        val T0: Instant = Instant.parse("2026-03-10T12:00:00Z")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var policy: FakeIncidentAccessPolicy

    private lateinit var t: AdminApiTestSupport
    private lateinit var reporter: Caller
    private lateinit var stranger: Caller
    private lateinit var admin: Caller
    private lateinit var incident: String

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        reporter = t.caller(Role.EMPLOYEE); stranger = t.caller(Role.EMPLOYEE); admin = t.caller(Role.ADMIN)
        incident = "INC" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        policy.viewers[incident] = setOf(reporter.id, admin.id)
        policy.calls.clear()
    }

    private fun insert(incidentId: String?, at: Instant, action: String = "INCIDENT_NOTED", entityType: String = "INCIDENT",
                       actorId: Long = 99L, actorRole: String = "TECHNICIAN",
                       description: String = "noted", metadata: String = "{}", actorType: String = "USER",
                       actorName: String? = null, prev: String? = null, next: String? = null): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """insert into audit_events (event_id, occurred_at, actor_type, actor_id, actor_role, actor_name, action, entity_type,
                   entity_id, incident_id, previous_state, new_state, description, metadata)
               values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""",
            id, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), actorType, if (actorType == "USER") actorId else null,
            if (actorType == "USER") actorRole else null, actorName, action, entityType, incidentId, incidentId, prev, next,
            description, metadata
        )
        return id
    }

    private fun trail(id: String, who: Caller?, query: String = ""): ResultActions {
        val builder = get("/api/incidents/$id/audit" + if (query.isEmpty()) "" else "?$query")
        if (who != null) builder.header("Authorization", who.bearer)
        return mvc.perform(builder)
    }

    private fun items(r: ResultActions): List<JsonNode> = t.body(r.andExpect(status().isOk)).get("items").toList()

    @Test fun `a permitted viewer gets only this incident's events, oldest first`() {
        val second = insert(incident, T0.plusSeconds(5), action = "INCIDENT_ASSIGNED")
        val first = insert(incident, T0, action = "INCIDENT_CREATED")
        insert("INC-OTHER", T0) // another incident's row must not leak
        insert(null, T0) // a row without an incident id must not match
        assertEquals(listOf(first, second).map { it.toString() }, items(trail(incident, reporter)).map { it.get("id").asText() })
        assertEquals(listOf(second, first).map { it.toString() }, items(trail(incident, reporter, "dir=desc")).map { it.get("id").asText() })
        assertEquals(2, t.body(trail(incident, reporter, "size=1").andExpect(status().isOk)).get("totalItems").asInt())
    }

    @Test fun `the decision is delegated to the policy with the caller and the incident id`() {
        insert(incident, T0)
        trail(incident, reporter).andExpect(status().isOk)
        trail(incident, stranger).andExpect(status().isForbidden)
        assertEquals(listOf(reporter.id to incident, stranger.id to incident), policy.calls.toList())
    }

    @Test fun `a caller the policy refuses gets 403 and no data, even for an incident full of events`() {
        repeat(3) { insert(incident, T0.plusSeconds(it.toLong())) }
        trail(incident, stranger).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.items").doesNotExist())
        // no filter, sort or page parameter can change that
        trail(incident, stranger, "size=100&sort=action&dir=desc").andExpect(status().isForbidden)
        // refused technicians too: access is not derived from the role
        trail(incident, t.caller(Role.TECHNICIAN)).andExpect(status().isForbidden)
    }

    @Test fun `an unknown incident is 404 from the policy, and a known incident without events is an empty 200`() {
        trail("INC-NOPE", reporter).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        val body = t.body(trail(incident, reporter).andExpect(status().isOk))
        assertEquals(0, body.get("items").size()); assertEquals(0, body.get("totalItems").asInt())
    }

    @Test fun `anonymous callers get 401 and malformed incident ids are rejected before the policy is asked`() {
        trail(incident, null).andExpect(status().isUnauthorized)
        for (bad in listOf("a.b", "x".repeat(31), "INC%20 1")) trail(bad, reporter).andExpect(status().isBadRequest)
        assertTrue(policy.calls.isEmpty())
    }

    @Test fun `bad sort or paging is a 400`() {
        for (q in listOf("sort=description", "dir=up", "size=0", "size=101", "page=-1")) {
            trail(incident, reporter, q).andExpect(status().isBadRequest)
        }
    }

    private val sensitive = """{"password":"hunter2","passwordHash":"${'$'}2a${'$'}10abc","refreshToken":"r-1","contactEmail":"a@b.example",
        "note":"mail bob@corp.example","jwt":"eyJhbGciOi.eyJzdWIiOi.sig","nested":{"k":1},"role":"EMPLOYEE","changedFields":["name"]}"""

    @Test fun `a viewer without AUDIT_VIEW gets the reduced projection and nothing else`() {
        insert(incident, T0, actorId = 7654321L, actorRole = "TECHNICIAN", action = "INCIDENT_ASSIGNED", description = "Assigned to Bob Smith (bob@corp.example)",
            metadata = sensitive, prev = "OPEN", next = "ASSIGNED")
        val node = items(trail(incident, reporter))[0]
        assertEquals(setOf("id", "occurredAt", "action", "actor", "previousState", "newState", "incidentId"), node.fieldNames().asSequence().toSet())
        assertEquals(setOf("type", "role"), node.get("actor").fieldNames().asSequence().toSet())
        assertEquals("TECHNICIAN", node.at("/actor/role").asText())
        assertEquals("OPEN", node.get("previousState").asText()); assertEquals("ASSIGNED", node.get("newState").asText())
        // nothing sensitive can appear anywhere in the raw body, not only under known keys
        val raw = trail(incident, reporter).andReturn().response.contentAsString
        for (leak in listOf("hunter2", "passwordHash", "refresh", "a@b.example", "bob@corp", "Bob Smith", "eyJ", "7654321", "metadata", "description", "entityType"))
            assertTrue(!raw.contains(leak), "leaked '$leak' in $raw")
    }

    @Test fun `system actors show as SYSTEM without their job name`() {
        insert(incident, T0, actorType = "SYSTEM", actorName = "SLA_MONITOR_JOB", action = "SLA_BREACHED")
        val raw = trail(incident, reporter).andReturn().response.contentAsString
        val node = items(trail(incident, reporter))[0]
        assertEquals("SYSTEM", node.at("/actor/type").asText()); assertTrue(node.at("/actor/role").isNull)
        assertTrue(!raw.contains("SLA_MONITOR_JOB"))
    }

    @Test fun `an admin gets the full row with redacted metadata, and the stored row is unchanged`() {
        val id = insert(incident, T0, actorId = 99L, description = "Assigned to a technician", metadata = sensitive, prev = "OPEN", next = "ASSIGNED")
        val node = items(trail(incident, admin))[0]
        assertEquals(99, node.at("/actor/id").asInt())
        assertEquals("Assigned to a technician", node.get("description").asText())
        assertEquals("INCIDENT", node.get("entityType").asText())
        assertEquals(listOf("changedFields", "role"), node.get("metadata").fieldNames().asSequence().toList())
        val raw = trail(incident, admin).andReturn().response.contentAsString
        for (leak in listOf("hunter2", "passwordHash", "r-1", "a@b.example", "bob@corp", "eyJ")) assertTrue(!raw.contains(leak), "leaked '$leak'")
        // redaction happens at the response boundary only
        val stored = jdbc.queryForObject("select metadata::text from audit_events where event_id = ?", String::class.java, id)!!
        assertTrue(stored.contains("hunter2"))
    }

    @Test fun `viewer sorts are limited to fields the viewer receives`() {
        insert(incident, T0)
        trail(incident, reporter, "sort=action").andExpect(status().isOk)
        for (q in listOf("sort=actorRole", "sort=entityType")) trail(incident, reporter, q).andExpect(status().isBadRequest)
        trail(incident, admin, "sort=actorRole").andExpect(status().isOk)
    }

    @Test fun `the trail endpoint offers no write methods`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(org.springframework.http.HttpMethod.valueOf(method), "/api/incidents/$incident/audit")
                .header("Authorization", reporter.bearer)).andExpect(status().isMethodNotAllowed)
        }
    }
}
