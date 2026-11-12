package com.itsm.integration

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.AdminApiTestSupport.Companion.unique
import com.itsm.admin.category.CategoryRepository
import com.itsm.admin.itservice.ItServiceRepository
import com.itsm.auth.JwtService
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Role
import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.ServiceCatalog
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** What the simulated caller learned from a catalog call. */
data class CatalogCallResult(val value: String?, val outcome: String)

/**
 * TEST-ONLY stand-in for P2/P4: an `@Transactional` business operation that makes an UNRELATED write, calls the REAL
 * catalog, catches the documented outcomes, makes a second write, and returns normally.
 */
@Transactional
class SimulatedCatalogCaller(
    private val categories: CategoryCatalog,
    private val services: ServiceCatalog,
    private val jdbc: JdbcTemplate
) {
    private fun write(tag: String, note: String) { jdbc.update("insert into b12_caller_write (note) values (?)", "$tag:$note") }

    private fun guarded(note: String, call: () -> String?): CatalogCallResult {
        write("before", note)
        val result = try {
            CatalogCallResult(call(), "ok")
        } catch (e: InvalidInput) {
            CatalogCallResult(null, "InvalidInput")
        } catch (e: ServiceNotFound) {   // a ResourceNotFound subclass: must be caught first
            CatalogCallResult(null, "ServiceNotFound")
        } catch (e: ResourceNotFound) {
            CatalogCallResult(null, "ResourceNotFound")
        }
        write("after", note)
        return result
    }

    fun requireActiveCategory(note: String, id: Long) = guarded(note) { categories.requireActive(id).code }
    fun requireActiveService(note: String, id: Long) = guarded(note) { services.requireActive(id).name }
    fun findCategory(note: String, id: Long) = guarded(note) { categories.findById(id)?.code }
    fun findService(note: String, id: Long) = guarded(note) { services.findById(id)?.name }

    /** A sloppy caller that swallows EVERY failure. */
    fun requireActiveCategorySwallowingEverything(note: String, id: Long): String {
        write("before", note)
        val r = try { categories.requireActive(id).code } catch (e: Exception) { e.javaClass.simpleName }
        write("after", note)
        return r
    }

    fun requireActiveServiceSwallowingEverything(note: String, id: Long): String {
        write("before", note)
        val r = try { services.requireActive(id).name } catch (e: Exception) { e.javaClass.simpleName }
        write("after", note)
        return r
    }
}

@TestConfiguration
class SimulatedCatalogCallerConfig {
    @Bean fun simulatedCatalogCaller(c: CategoryCatalog, s: ServiceCatalog, j: JdbcTemplate) = SimulatedCatalogCaller(c, s, j)
}

/**
 * B10-TX-001. `requireActive` is an interface default method; Kotlin compiles a public bridge into each catalog class, so
 * the class-level `@Transactional` proxy wraps it and a thrown RuntimeException marks the CALLER's transaction
 * rollback-only. The documented outcomes (unknown, inactive) must leave the caller's transaction committable, while a
 * real infrastructure failure must still doom it.
 *
 * Real PostgreSQL 16, real catalogs, real transaction manager. The only double is a spy that injects a genuine
 * infrastructure exception at the repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SimulatedCatalogCallerConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class CatalogCallerTransactionIntegrationTest {

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
    @Autowired lateinit var categories: CategoryCatalog
    @Autowired lateinit var services: ServiceCatalog
    @Autowired lateinit var caller: SimulatedCatalogCaller
    @SpyBean lateinit var categoryRepo: CategoryRepository
    @SpyBean lateinit var serviceRepo: ItServiceRepository

    @BeforeEach fun setUp() {
        jdbc.execute("create table if not exists b12_caller_write (id bigserial primary key, note text not null)")
    }

    private fun newCategory(active: Boolean): Long = jdbc.queryForObject(
        "insert into incident_categories (code, name, active) values (?, ?, ?) returning id", Long::class.java,
        "B12_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase(), unique("B12 cat "), active
    )!!

    private fun newService(active: Boolean): Long = jdbc.queryForObject(
        "insert into it_services (name, business_criticality, active) values (?, 3, ?) returning id", Long::class.java,
        unique("B12 svc "), active
    )!!

    private fun committed(note: String): List<String> =
        jdbc.queryForList("select note from b12_caller_write where note like ? order by id", String::class.java, "%:$note")

    private fun assertBothWritesCommitted(note: String) =
        assertEquals(listOf("before:$note", "after:$note"), committed(note), "both unrelated writes were committed")

    // --- the six required scenarios ------------------------------------------------------------------------------

    @Test fun `category active commits`() {
        val note = unique("cat-ok-")
        val r = caller.requireActiveCategory(note, newCategory(true))
        assertEquals("ok", r.outcome)
        assertBothWritesCommitted(note)
    }

    @Test fun `category inactive commits after catching InvalidInput`() {
        val note = unique("cat-inactive-")
        assertEquals("InvalidInput", caller.requireActiveCategory(note, newCategory(false)).outcome)
        assertBothWritesCommitted(note)
    }

    @Test fun `category unknown commits after catching ResourceNotFound`() {
        val note = unique("cat-unknown-")
        assertEquals("ResourceNotFound", caller.requireActiveCategory(note, 9_999_999).outcome)
        assertBothWritesCommitted(note)
    }

    @Test fun `service active commits`() {
        val note = unique("svc-ok-")
        assertEquals("ok", caller.requireActiveService(note, newService(true)).outcome)
        assertBothWritesCommitted(note)
    }

    @Test fun `service inactive commits after catching InvalidInput`() {
        val note = unique("svc-inactive-")
        assertEquals("InvalidInput", caller.requireActiveService(note, newService(false)).outcome)
        assertBothWritesCommitted(note)
    }

    @Test fun `service unknown commits after catching ServiceNotFound`() {
        val note = unique("svc-unknown-")
        assertEquals("ServiceNotFound", caller.requireActiveService(note, 9_999_999).outcome)
        assertBothWritesCommitted(note)
    }

    @Test fun `repeated expected failures each commit`() {
        val note = unique("repeat-")
        val inactive = newCategory(false)
        val svc = newService(false)
        repeat(3) {
            assertEquals("InvalidInput", caller.requireActiveCategory("$note-c$it", inactive).outcome)
            assertEquals("InvalidInput", caller.requireActiveService("$note-s$it", svc).outcome)
        }
        assertEquals(12, jdbc.queryForObject("select count(*) from b12_caller_write where note like ?", Long::class.java, "%:$note-%"))
    }

    // --- findById is unaffected ----------------------------------------------------------------------------------

    @Test fun `findById returning null does not disturb the caller`() {
        val a = unique("cat-find-")
        val b = unique("svc-find-")
        assertNull(caller.findCategory(a, 9_999_999).value)
        assertNull(caller.findService(b, 9_999_999).value)
        assertBothWritesCommitted(a)
        assertBothWritesCommitted(b)
    }

    // --- infrastructure failures keep normal behaviour -------------------------------------------------------------

    @Test fun `a database failure in the category lookup propagates as itself and dooms the caller`() {
        val id = newCategory(true)
        doThrow(DataAccessResourceFailureException("simulated outage")).`when`(categoryRepo).findById(id)
        try {
            assertThrows<DataAccessResourceFailureException> { categories.requireActive(id) }
            val note = unique("cat-db-")
            assertThrows<UnexpectedRollbackException> { caller.requireActiveCategorySwallowingEverything(note, id) }
            assertEquals(emptyList<String>(), committed(note), "nothing committed: a swallowed database error is not success")
        } finally {
            org.mockito.Mockito.reset(categoryRepo)
        }
    }

    @Test fun `a database failure in the service lookup propagates as itself and dooms the caller`() {
        val id = newService(true)
        doThrow(DataAccessResourceFailureException("simulated outage")).`when`(serviceRepo).findById(id)
        try {
            assertThrows<DataAccessResourceFailureException> { services.requireActive(id) }
            val note = unique("svc-db-")
            assertThrows<UnexpectedRollbackException> { caller.requireActiveServiceSwallowingEverything(note, id) }
            assertEquals(emptyList<String>(), committed(note))
        } finally {
            org.mockito.Mockito.reset(serviceRepo)
        }
    }

    // --- uncaught domain exceptions keep their application behaviour ----------------------------------------------

    @Test fun `without a surrounding transaction the same exceptions are thrown`() {
        assertThrows<InvalidInput> { categories.requireActive(newCategory(false)) }
        assertThrows<ResourceNotFound> { categories.requireActive(9_999_999) }
        assertThrows<InvalidInput> { services.requireActive(newService(false)) }
        assertThrows<ServiceNotFound> { services.requireActive(9_999_999) }
    }

    @Test fun `an admin mutation that lets the exception propagate still answers 400 or 404 and rolls back`() {
        val admin = AdminApiTestSupport(users, jwt, jdbc).caller(Role.ADMIN)
        val before = jdbc.queryForObject("select count(*) from sla_policies", Long::class.java)
        fun create(serviceId: Long) = mvc.perform(
            post("/api/admin/sla-policies").header("Authorization", admin.bearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"serviceId":$serviceId,"priority":"P1","responseMinutes":10,"resolutionMinutes":60}""")
        )
        create(newService(false)).andExpect(status().isBadRequest)
        create(9_999_999).andExpect(status().isNotFound)
        assertEquals(before, jdbc.queryForObject("select count(*) from sla_policies", Long::class.java), "nothing was created")
    }
}
