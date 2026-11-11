package com.itsm.lifecycle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.spi.TechnicianSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** The HTTP surface over the real stack: JWT login, JSON, status codes and RFC 7807 errors. P1's providers are stubbed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IncidentFlowIntegrationTest.P1Stubs::class)
@Testcontainers(disabledWithoutDocker = true)
class IncidentApiIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var directory: IncidentFlowIntegrationTest.MutableDirectory
    private val json = ObjectMapper()

    private class Account(val id: Long, val token: String)

    private fun account(role: String): Account {
        val email = "api-${UUID.randomUUID()}@campus.example"
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"Passw0rd!","name":"Tester"}""")).andExpect(status().isOk)
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role, email)
        val token = json.readTree(
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"Passw0rd!"}""")).andReturn().response.contentAsString
        )["token"].asText()
        return Account(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long::class.java, email)!!, token)
    }

    private fun incident(reporter: Long): String {
        val id = "INC" + UUID.randomUUID().toString().take(8)
        jdbc.update("INSERT INTO incidents (id, title, description, state, reporter_id, category_id, service_id, priority) VALUES (?, 'VPN down', 'Cannot connect', 'CLASSIFIED', ?, 7, 3, 'P2')", id, reporter)
        return id
    }

    private fun ResultActions.body(): JsonNode = json.readTree(andReturn().response.contentAsString)
    private fun call(a: Account, method: String, path: String, body: String? = null): ResultActions {
        val req = if (method == "GET") get(path) else post(path)
        req.header("Authorization", "Bearer ${a.token}")
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(req)
    }
    private fun move(a: Account, id: String, t: String, reason: String? = null) =
        call(a, "POST", "/api/incidents/$id/transitions", """{"transition":"$t"${reason?.let { ""","reason":"$it"""" } ?: ""}}""")

    @Test fun `an incident is worked from assignment to rated feedback over HTTP`() {
        val reporter = account("USER"); val tech = account("TECHNICIAN"); val admin = account("ADMIN")
        directory.technicians = listOf(TechnicianSummary(tech.id, "Tech", "t@campus.example", true, 5, 3, false, null, mapOf(7L to 3)))
        val id = incident(reporter.id)

        val assigned = call(admin, "POST", "/api/incidents/$id/assignment", "{}").andExpect(status().isOk).body()
        assertEquals("ASSIGNED", assigned["outcome"].asText()); assertEquals(tech.id, assigned["incident"]["assigneeId"].asLong())
        assertEquals("LOAD_BASED", assigned["strategy"].asText())

        val shown = call(tech, "GET", "/api/incidents/$id").andExpect(status().isOk).body()
        assertEquals(listOf("ACKNOWLEDGE"), shown["availableTransitions"].map { it.asText() })

        move(tech, id, "ACKNOWLEDGE").andExpect(status().isOk).andExpect(jsonPath("$.state").value("ACKNOWLEDGED"))
        move(tech, id, "START_WORK").andExpect(status().isOk)
        call(tech, "POST", "/api/incidents/$id/timeline", """{"type":"NOTE","body":"gateway cert expired"}""")
            .andExpect(status().isCreated).andExpect(jsonPath("$.visibility").value("INTERNAL"))
        move(tech, id, "RESOLVE", "Renewed the gateway certificate").andExpect(status().isOk).andExpect(jsonPath("$.state").value("RESOLVED"))

        call(reporter, "GET", "/api/incidents/$id/timeline").andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].type").value("RESOLUTION"))
        call(reporter, "GET", "/api/incidents/$id/feedback").andExpect(status().isNotFound)
        move(reporter, id, "VERIFY").andExpect(status().isOk).andExpect(jsonPath("$.state").value("VERIFIED"))
        call(reporter, "POST", "/api/incidents/$id/feedback", """{"rating":5,"comment":"Quick"}""").andExpect(status().isCreated).andExpect(jsonPath("$.rating").value(5))
        call(reporter, "POST", "/api/incidents/$id/feedback", """{"rating":1}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
        call(reporter, "GET", "/api/incidents/$id/feedback").andExpect(status().isOk).andExpect(jsonPath("$.rating").value(5))
        move(admin, id, "CLOSE").andExpect(status().isOk).andExpect(jsonPath("$.state").value("CLOSED"))
    }

    @Test fun `errors are problem json with stable codes`() {
        val reporter = account("USER"); val tech = account("TECHNICIAN"); val stranger = account("USER"); val admin = account("ADMIN")
        val id = incident(reporter.id)
        mvc.perform(get("/api/incidents/$id")).andExpect(status().isUnauthorized)
        call(stranger, "GET", "/api/incidents/$id").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        call(admin, "GET", "/api/incidents/NOSUCH").andExpect(status().isNotFound)
        move(admin, id, "RESOLVE", "x").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
        call(admin, "POST", "/api/incidents/$id/transitions", "{}").andExpect(status().isBadRequest)
        call(admin, "POST", "/api/incidents/$id/transitions", """{"transition":"TELEPORT"}""").andExpect(status().isBadRequest)
        move(admin, id, "ASSIGN").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        call(reporter, "GET", "/api/incidents/$id/recommendations").andExpect(status().isForbidden)
        call(admin, "GET", "/api/incidents/$id/recommendations").andExpect(status().isOk)
        call(reporter, "POST", "/api/incidents/$id/feedback", """{"rating":9}""").andExpect(status().isConflict) // not verified yet: state first
        call(tech, "GET", "/api/incidents/$id").andExpect(status().isNotFound) // a technician who is not the assignee
    }
}
