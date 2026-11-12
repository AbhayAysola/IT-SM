package com.itsm.admin.assignment

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.assignment.AssignmentEngine
import com.itsm.assignment.RegisteredAssignmentStrategyCatalog
import com.itsm.common.spi.AssignmentStrategyCatalog
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Assignment settings in the application's REAL state with nothing replaced: P3's `RegisteredAssignmentStrategyCatalog` is
 * the only catalog bean. The seeded default must be a strategy P3 registers, reads report the catalog as AVAILABLE, and
 * writes validate against what the engine can run. (The ABSENT and FAILED catalog states are covered in
 * `StrategyCatalogGatewayTest` and `ProviderFailureSemanticsIntegrationTest`.) Required to execute in CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AssignmentSettingsRealCatalogIntegrationTest {

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
    @Autowired lateinit var provider: AssignmentSettingsProvider
    @Autowired lateinit var catalog: ObjectProvider<AssignmentStrategyCatalog>
    @Autowired lateinit var engine: AssignmentEngine

    private lateinit var t: AdminApiTestSupport
    private lateinit var admin: Caller

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        admin = t.caller(Role.ADMIN)
        jdbc.update("delete from assignment_category_overrides")
        jdbc.update("update assignment_settings set active_strategy = 'LOAD_BASED', default_max_workload = 5, updated_by = null, version = 0")
    }

    private fun MockHttpServletRequestBuilder.json(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(t.json.writeValueAsString(body))

    private fun call(request: MockHttpServletRequestBuilder, caller: Caller? = admin): ResultActions =
        mvc.perform(if (caller == null) request else request.header("Authorization", caller.bearer))

    private fun putBody(strategy: String = "SKILL_BASED", workload: Int = 8, version: Long = 0) = mapOf(
        "activeStrategy" to strategy, "defaultMaxWorkload" to workload,
        "categoryOverrides" to emptyList<Any>(), "version" to version)

    @Test fun `the application starts with exactly one catalog, P3's real one`() {
        assertInstanceOf(RegisteredAssignmentStrategyCatalog::class.java, catalog.getObject())
        assertEquals(engine.registeredKeys(), catalog.getObject().registeredKeys())
    }

    @Test fun `the seeded default is served, is registered by P3, and the catalog is reported as available`() {
        val keys = engine.registeredKeys().sorted()
        assertTrue("LOAD_BASED" in keys, "the strategy P1 seeds must be registered by P3")
        call(get(BASE)).andExpect(status().isOk)
            .andExpect(jsonPath("$.activeStrategy").value("LOAD_BASED"))
            .andExpect(jsonPath("$.defaultMaxWorkload").value(5))
            .andExpect(jsonPath("$.categoryOverrides").isEmpty)
            .andExpect(jsonPath("$.availableStrategies", org.hamcrest.Matchers.contains(*keys.toTypedArray())))
            .andExpect(jsonPath("$.strategyCatalogStatus").value("AVAILABLE"))
            .andExpect(jsonPath("$.version").value(0))
        val view = provider.current()
        assertEquals("LOAD_BASED", view.activeStrategy)
        assertEquals(5, view.defaultMaxWorkload)
        assertTrue(view.categoryOverrides.isEmpty())
        assertEquals("LOAD_BASED", view.strategyFor(123L))
    }

    @Test fun `every strategy P3 registers can be activated, an unregistered one is refused, and the audit row is written only on success`() {
        val auditBefore = t.auditCount()
        var version = 0L
        for (key in engine.registeredKeys().sorted()) {
            call(put(BASE).json(putBody(strategy = key, workload = 8, version = version))).andExpect(status().isOk)
                .andExpect(jsonPath("$.activeStrategy").value(key))
            version++
            assertEquals(key, provider.current().activeStrategy)
        }
        assertEquals(auditBefore + engine.registeredKeys().size, t.auditCount())

        call(put(BASE).json(putBody(strategy = "TELEPATHY", version = version))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("activeStrategy"))
        assertEquals(version, jdbc.queryForObject("select version from assignment_settings", Long::class.java))
        assertEquals(auditBefore + engine.registeredKeys().size, t.auditCount())
    }

    @Test fun `malformed input is still a 400 and a stale version a 409, before the catalog is consulted`() {
        call(put(BASE).json(putBody(strategy = "load based"))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("activeStrategy"))
        call(put(BASE).json(putBody(workload = 0))).andExpect(status().isBadRequest)
        call(put(BASE).json(putBody(version = 9))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
    }

    @Test fun `anonymous callers get 401 and EMPLOYEE or TECHNICIAN get 403 on read and write`() {
        for (request in listOf({ get(BASE) }, { put(BASE).json(putBody()) })) {
            call(request(), caller = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            for (role in listOf(Role.EMPLOYEE, Role.TECHNICIAN)) {
                call(request(), t.caller(role)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }
    }

    @Test fun `the database keeps exactly one configuration row and refuses to delete it`() {
        assertEquals(1L, jdbc.queryForObject("select count(*) from assignment_settings", Long::class.java))
        val ex = assertThrows<DataAccessException> { jdbc.update("delete from assignment_settings") }
        assertTrue(ex.mostSpecificCause.message!!.contains("cannot be removed"))
        assertThrows<DataAccessException> {
            jdbc.update("insert into assignment_settings (id, active_strategy, default_max_workload) values (2, 'X_Y', 5)")
        }
        assertThrows<DataAccessException> { jdbc.update("update assignment_settings set default_max_workload = 0") }
        assertThrows<DataAccessException> { jdbc.update("update assignment_settings set active_strategy = 'bad key'") }
    }
}
