package com.itsm.admin.assignment

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.AssignmentStrategyCatalog
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** A stand-in for P3's catalog. Test code only: the real implementation is P3's and must not be written in src/main. */
@TestConfiguration
class FakeStrategyCatalogConfig {
    @Bean
    fun fakeStrategyCatalog(): AssignmentStrategyCatalog = object : AssignmentStrategyCatalog {
        override fun registeredKeys(): Set<String> = setOf("LOAD_BASED", "SKILL_BASED", "ROUND_ROBIN")
    }
}

/**
 * Assignment settings with a registered-strategy catalog available, on a real PostgreSQL 16 (V14, Hibernate
 * validation). The configuration is a singleton, so every test resets it first. Required to execute in CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStrategyCatalogConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class AssignmentSettingsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        const val BASE = "/api/admin/assignment-settings"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var service: AssignmentSettingsAdminService
    @Autowired lateinit var provider: AssignmentSettingsProvider

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN)
        jdbc.update("delete from assignment_category_overrides")
        jdbc.update("update assignment_settings set active_strategy = 'LOAD_BASED', default_max_workload = 5, updated_by = null, version = 0")
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun MockHttpServletRequestBuilder.json(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))

    private fun call(request: MockHttpServletRequestBuilder, caller: Caller? = admin): ResultActions =
        mvc.perform(if (caller == null) request else request.header("Authorization", caller.bearer))

    private fun current() = t.body(call(get(BASE)).andExpect(status().isOk))

    private fun categoryId(code: String): Long =
        jdbc.queryForObject("select id from incident_categories where code = ?", Long::class.java, code)

    private fun override(categoryId: Long?, strategy: String?) = mapOf("categoryId" to categoryId, "strategy" to strategy)

    private fun putBody(
        strategy: String = "LOAD_BASED", workload: Int = 5, overrides: List<Map<String, Any?>> = emptyList(),
        version: Long? = null
    ) = mapOf("activeStrategy" to strategy, "defaultMaxWorkload" to workload, "categoryOverrides" to overrides,
        "version" to (version ?: current()["version"].asLong()))

    private fun lastAudit() = t.auditRows("ASSIGNMENT_SETTINGS", 1).last()

    private fun assertUnchanged() {
        val view = provider.current()
        assertEquals("LOAD_BASED", view.activeStrategy)
        assertEquals(5, view.defaultMaxWorkload)
        assertTrue(view.categoryOverrides.isEmpty())
        assertEquals(0L, jdbc.queryForObject("select version from assignment_settings", Long::class.java))
    }

    // --- read ------------------------------------------------------------------------------------------------------

    @Test fun `the default settings are served with the registered strategies`() {
        call(get(BASE)).andExpect(status().isOk)
            .andExpect(jsonPath("$.activeStrategy").value("LOAD_BASED"))
            .andExpect(jsonPath("$.defaultMaxWorkload").value(5))
            .andExpect(jsonPath("$.categoryOverrides").isEmpty)
            .andExpect(jsonPath("$.availableStrategies[0]").value("LOAD_BASED"))
            .andExpect(jsonPath("$.availableStrategies.length()").value(3))
            .andExpect(jsonPath("$.strategyCatalogStatus").value("AVAILABLE"))
    }

    // --- authorization ---------------------------------------------------------------------------------------------

    @Test fun `anonymous callers get 401 and EMPLOYEE or TECHNICIAN get 403, with no side effects`() {
        val auditBefore = t.auditCount()
        for (request in listOf({ get(BASE) }, { put(BASE).json(putBody(strategy = "SKILL_BASED")) })) {
            call(request(), caller = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            for (role in listOf(Role.EMPLOYEE, Role.TECHNICIAN)) {
                call(request(), t.caller(role)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }
        assertEquals(auditBefore, t.auditCount())
        assertUnchanged()
    }

    // --- update ----------------------------------------------------------------------------------------------------

    @Test fun `ADMIN updates the strategy, and the audit row records actor, before and after`() {
        val body = t.body(call(put(BASE).json(putBody(strategy = "SKILL_BASED"))).andExpect(status().isOk))
        assertEquals("SKILL_BASED", body["activeStrategy"].asText())
        assertEquals(1, body["version"].asLong())
        assertEquals(admin.id, body["updatedByUserId"].asLong())
        assertEquals("SKILL_BASED", provider.current().activeStrategy)

        val audit = lastAudit()
        assertEquals("ASSIGNMENT_SETTINGS_UPDATED", audit.action)
        assertEquals("USER", audit.actorType)
        assertEquals(admin.id, audit.actorId)
        assertEquals("ADMIN", audit.actorRole)
        assertEquals(listOf("activeStrategy"), audit.metadata["changedFields"].map { it.asText() })
        assertEquals("LOAD_BASED", audit.metadata["activeStrategyBefore"].asText())
        assertEquals("SKILL_BASED", audit.metadata["activeStrategyAfter"].asText())
    }

    @Test fun `ADMIN updates the default workload`() {
        call(put(BASE).json(putBody(workload = 12))).andExpect(status().isOk).andExpect(jsonPath("$.defaultMaxWorkload").value(12))
        assertEquals(12, provider.current().defaultMaxWorkload)
        val audit = lastAudit()
        assertEquals(5, audit.metadata["defaultMaxWorkloadBefore"].asInt())
        assertEquals(12, audit.metadata["defaultMaxWorkloadAfter"].asInt())
    }

    @Test fun `overrides can be added, retargeted and removed, and the provider resolves strategyFor`() {
        val network = categoryId("NETWORK"); val hardware = categoryId("HARDWARE"); val cloud = categoryId("CLOUD")
        val added = t.body(call(put(BASE).json(putBody(overrides = listOf(
            override(network, "SKILL_BASED"), override(hardware, "ROUND_ROBIN"))))).andExpect(status().isOk))
        assertEquals(listOf("Hardware", "Network"), added["categoryOverrides"].map { it["categoryName"].asText() })
        assertTrue(added["categoryOverrides"].all { it["categoryActive"].asBoolean() })
        val view = provider.current()
        assertEquals("SKILL_BASED", view.strategyFor(network))
        assertEquals("ROUND_ROBIN", view.strategyFor(hardware))
        assertEquals("LOAD_BASED", view.strategyFor(cloud), "no override: the active strategy applies")
        assertEquals("LOAD_BASED", view.strategyFor(null))
        assertEquals(setOf(network, hardware), lastAudit().metadata["overridesAddedCategoryIds"].map { it.asLong() }.toSet())

        call(put(BASE).json(putBody(overrides = listOf(override(network, "ROUND_ROBIN"), override(cloud, "SKILL_BASED")))))
            .andExpect(status().isOk)
        val after = provider.current().categoryOverrides
        assertEquals(mapOf(network to "ROUND_ROBIN", cloud to "SKILL_BASED"), after)
        val audit = lastAudit()
        assertEquals(listOf(cloud), audit.metadata["overridesAddedCategoryIds"].map { it.asLong() })
        assertEquals(listOf(hardware), audit.metadata["overridesRemovedCategoryIds"].map { it.asLong() })
        assertEquals(listOf(network), audit.metadata["overridesChangedCategoryIds"].map { it.asLong() })
        assertEquals(2, audit.metadata["overrideCountBefore"].asInt())

        call(put(BASE).json(putBody())).andExpect(status().isOk).andExpect(jsonPath("$.categoryOverrides").isEmpty)
        assertTrue(provider.current().categoryOverrides.isEmpty())
        assertEquals(0L, jdbc.queryForObject("select count(*) from assignment_category_overrides", Long::class.java))
    }

    @Test fun `the provider returns an immutable view`() {
        call(put(BASE).json(putBody(overrides = listOf(override(categoryId("NETWORK"), "SKILL_BASED"))))).andExpect(status().isOk)
        val view = provider.current()
        assertThrows<UnsupportedOperationException> { (view.categoryOverrides as MutableMap<Long, String>)[1L] = "X_Y" }
    }

    // --- rejected input persists nothing ---------------------------------------------------------------------------

    @Test fun `invalid workloads are rejected, and nothing is persisted or audited`() {
        val auditBefore = t.auditCount()
        for (bad in listOf(0, -3, 101)) {
            call(put(BASE).json(putBody(workload = bad))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.errors[0].field").value("defaultMaxWorkload"))
        }
        call(put(BASE).json(mapOf("activeStrategy" to "LOAD_BASED", "version" to 0))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        assertEquals(auditBefore, t.auditCount())
        assertUnchanged()
    }

    @Test fun `duplicate category overrides and incomplete entries are rejected`() {
        val network = categoryId("NETWORK")
        val auditBefore = t.auditCount()
        call(put(BASE).json(putBody(overrides = listOf(override(network, "SKILL_BASED"), override(network, "ROUND_ROBIN")))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("categoryOverrides"))
        call(put(BASE).json(putBody(overrides = listOf(override(null, "SKILL_BASED"))))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("categoryOverrides[0]"))
        call(put(BASE).json(putBody(overrides = listOf(override(network, null))))).andExpect(status().isBadRequest)
        call(put(BASE).json(putBody(overrides = listOf(override(network, "not a key"))))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("categoryOverrides[0].strategy"))
        assertEquals(auditBefore, t.auditCount())
        assertUnchanged()
    }

    @Test fun `unknown categories are rejected`() {
        call(put(BASE).json(putBody(overrides = listOf(override(categoryId("NETWORK"), "SKILL_BASED"), override(999_999L, "SKILL_BASED")))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("999999")))
        assertUnchanged()
    }

    @Test fun `strategy keys the catalog does not register are rejected, for the active strategy and for overrides`() {
        val auditBefore = t.auditCount()
        call(put(BASE).json(putBody(strategy = "TELEPATHY"))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT")).andExpect(jsonPath("$.errors[0].field").value("activeStrategy"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("LOAD_BASED")))
        call(put(BASE).json(putBody(overrides = listOf(override(categoryId("NETWORK"), "RANDOM_PICK"))))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("categoryOverrides[0].strategy"))
        assertEquals(auditBefore, t.auditCount())
        assertUnchanged()
    }

    @Test fun `a stale version is a 409, and an unchanged update is a silent no-op`() {
        val stale = putBody(strategy = "SKILL_BASED")
        call(put(BASE).json(putBody(workload = 7))).andExpect(status().isOk)
        call(put(BASE).json(stale)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        assertEquals(7, provider.current().defaultMaxWorkload)
        assertEquals("LOAD_BASED", provider.current().activeStrategy)

        val auditBefore = t.auditCount()
        call(put(BASE).json(putBody(workload = 7))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        assertEquals(auditBefore, t.auditCount())
    }

    // --- transactions ----------------------------------------------------------------------------------------------

    @Test fun `a rolled-back transaction leaves neither the new settings nor their audit row`() {
        val auditBefore = t.auditCount()
        tx.executeWithoutResult { status ->
            t.asCaller(admin) {
                service.update(UpdateAssignmentSettingsRequest(
                    "SKILL_BASED", 9, listOf(OverrideRequest(categoryId("NETWORK"), "ROUND_ROBIN")), 0))
            }
            status.setRollbackOnly()
        }
        assertEquals(auditBefore, t.auditCount())
        assertUnchanged()
    }
}
