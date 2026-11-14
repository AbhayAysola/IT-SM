package com.itsm.intake

import com.fasterxml.jackson.databind.JsonNode
import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.event.IncidentClassified
import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import com.itsm.common.spi.PriorityPolicy
import com.itsm.priority.CatalogPriorityPolicy
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Incident intake over the real stack: the reporter answers impact and urgency, P2 asks P4's real [PriorityPolicy] (with
 * P1's real service catalog) for the priority, and stores the answers (V22) and the reasons (audit). Nobody else
 * computes a priority.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@RecordApplicationEvents
class IncidentIntakePriorityIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var events: ApplicationEvents

    private lateinit var t: AdminApiTestSupport
    private lateinit var reporter: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        reporter = t.caller(Role.EMPLOYEE)
    }

    private val category: Long by lazy { jdbc.queryForObject("SELECT id FROM incident_categories WHERE code = 'NETWORK'", Long::class.java)!! }

    private fun service(criticality: Int): Long = jdbc.queryForObject(
        "INSERT INTO it_services (name, business_criticality) VALUES (?, ?) RETURNING id", Long::class.java,
        "intake-svc-" + UUID.randomUUID().toString().take(8), criticality
    )!!

    private fun create(body: Map<String, Any?>, expect: Int): JsonNode {
        val response = mvc.perform(
            post("/api/incidents").header("Authorization", reporter.bearer)
                .contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))
        ).andReturn().response
        assertEquals(expect, response.status, "POST /api/incidents -> ${response.contentAsString}")
        return t.json.readTree(response.contentAsString)
    }

    private fun report(serviceId: Long, impact: String?, urgency: String?) = mapOf(
        "title" to "Wi-Fi down", "description" to "No connection in Block C",
        "categoryId" to category, "serviceId" to serviceId, "impact" to impact, "urgency" to urgency
    )

    @Test fun `the only priority policy is P4's`() {
        val beans = context.getBeansOfType(PriorityPolicy::class.java).values
        assertEquals(1, beans.size)
        assertTrue(beans.single() is CatalogPriorityPolicy, "got ${beans.single().javaClass}")
    }

    @Test fun `the priority is P4's assessment of the answers, and the answers are stored`() {
        // MEDIUM impact and HIGH urgency give P2; a mission-critical service (5) raises it to P1.
        val critical = create(report(service(criticality = 5), "MEDIUM", "HIGH"), 201)
        assertEquals("P1", critical["priority"].asText())
        assertEquals("MEDIUM", critical["impact"].asText()); assertEquals("HIGH", critical["urgency"].asText())
        assertEquals("CLASSIFIED", critical["state"].asText())

        val id = critical["id"].asText()
        val row = jdbc.queryForMap("SELECT priority, impact, urgency FROM incidents WHERE id = ?", id)
        assertEquals(mapOf("priority" to "P1", "impact" to "MEDIUM", "urgency" to "HIGH"), row)

        val reasons = t.json.readTree(jdbc.queryForObject(
            "SELECT metadata::text FROM audit_events WHERE action = 'INCIDENT_CLASSIFIED' AND entity_id = ?", String::class.java, id
        ))["priorityReasons"]
        assertTrue(reasons.isArray && reasons.size() == 2, "base reason plus the criticality adjustment: $reasons")
        assertTrue(reasons[1].asText().contains("business criticality 5"), reasons.toString())

        // The same answers on an ordinary service stay P2.
        assertEquals("P2", create(report(service(criticality = 3), "MEDIUM", "HIGH"), 201)["priority"].asText())
        assertEquals("P4", create(report(service(criticality = 3), "low", "Low"), 201)["priority"].asText(), "codes are case-insensitive")
    }

    @Test fun `a new incident publishes IncidentClassified once, with ids and the priority only`() {
        val serviceId = service(criticality = 3)
        val id = create(report(serviceId, "MEDIUM", "HIGH"), 201)["id"].asText()
        val published = events.stream(IncidentClassified::class.java).toList()
        assertEquals(1, published.size, published.toString())
        val e = published.single()
        assertEquals(listOf(id, reporter.id, serviceId, category, Priority.P2), listOf(e.incidentId, e.reporterId, e.serviceId, e.categoryId, e.priority))

        create(report(serviceId, "MEDIUM", null), 400)
        assertEquals(1, events.stream(IncidentClassified::class.java).count(), "a refused report publishes nothing")
    }

    @Test fun `the reporter cannot choose the priority, and answers are validated`() {
        val svc = service(criticality = 3)
        // The old contract (a priority instead of answers) is refused, so nobody can pick P1 directly.
        val old = mapOf("title" to "x", "description" to "y", "categoryId" to category, "serviceId" to svc, "priority" to "P1")
        assertEquals(400, create(old, 400)["status"].asInt())
        assertEquals("INVALID_INPUT", create(report(svc, "SEVERE", "HIGH"), 400)["code"].asText())
        assertEquals("INVALID_INPUT", create(report(svc, "HIGH", "NOW"), 400)["code"].asText())
        create(report(svc, null, "HIGH"), 400)
    }

    @Test fun `incidents created before answers were stored still edit, with no answers`() {
        val svc = service(criticality = 3)
        val id = "INC" + UUID.randomUUID().toString().replace("-", "").take(10)
        jdbc.update(
            "INSERT INTO incidents (id, title, description, state, reporter_id, category_id, service_id, priority) VALUES (?, 'Old', 'Old one', 'CLASSIFIED', ?, ?, ?, 'P3')",
            id, reporter.id, category, svc
        )
        val response = mvc.perform(
            patch("/api/incidents/$id").header("Authorization", reporter.bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"title":"Old, edited","description":"Still old"}""")
        ).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        val body = t.json.readTree(response.contentAsString)
        assertTrue(body["impact"].isNull && body["urgency"].isNull)
        assertEquals("P3", body["priority"].asText())
    }

    @Test fun `the database refuses an impact without an urgency, or an unknown value`() {
        val svc = service(criticality = 3)
        fun insert(impact: String?, urgency: String?) = runCatching {
            jdbc.update(
                "INSERT INTO incidents (title, description, state, reporter_id, category_id, service_id, priority, impact, urgency) VALUES ('t', 'd', 'CLASSIFIED', ?, ?, ?, 'P3', ?, ?)",
                reporter.id, category, svc, impact, urgency
            )
        }
        assertTrue(insert("HIGH", null).isFailure)
        assertTrue(insert("SEVERE", "HIGH").isFailure)
        assertTrue(insert("HIGH", "LOW").isSuccess)
    }
}
