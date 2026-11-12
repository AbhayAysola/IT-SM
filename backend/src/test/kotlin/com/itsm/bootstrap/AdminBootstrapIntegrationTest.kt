package com.itsm.bootstrap

import com.itsm.admin.AdminApiTestSupport
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One-time ADMIN bootstrap on a real PostgreSQL 16. The shared database is reset to "no users" before every test. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AdminBootstrapIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var service: AdminBootstrapService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var context: ApplicationContext

    @BeforeEach fun reset() { jdbc.update("delete from users") }

    @Test fun `creates the first ADMIN with a hashed password and one audit row, then refuses a second run`() {
        val id = service.bootstrap(" First.Admin@Campus.Example ", "Bootstr4p!pw", " Root Admin ")
        val row = jdbc.queryForMap("select email, name, role, active, password from users where id = ?", id)
        assertEquals("first.admin@campus.example", row["email"]); assertEquals("Root Admin", row["name"])
        assertEquals("ADMIN", row["role"]); assertEquals(true, row["active"])
        val hash = row["password"] as String
        assertTrue(hash.startsWith("\$2") && !hash.contains("Bootstr4p"))

        val audit = jdbc.queryForList(
            "select action, actor_type, description || ' ' || metadata::text from audit_events where entity_type = 'USER' and entity_id = ?",
            id.toString()
        ).single()
        assertEquals("USER_ADMIN_BOOTSTRAPPED", audit["action"]); assertEquals("SYSTEM", audit["actor_type"])
        assertFalse((audit.values.last() as String).contains("Bootstr4p") || (audit.values.last() as String).contains("\$2"))

        assertThrows<BootstrapException> { service.bootstrap("another@campus.example", "Bootstr4p!pw", "Another") }
        assertEquals(1L, jdbc.queryForObject("select count(*) from users where role = 'ADMIN'", Long::class.java))
    }

    @Test fun `refuses when any ADMIN exists, even an inactive one, and never promotes an existing account`() {
        jdbc.update("insert into users (email, password, name, role, active) values ('old@campus.example', 'h', 'Old', 'ADMIN', false)")
        assertThrows<BootstrapException> { service.bootstrap("new@campus.example", "Bootstr4p!pw", "New") }

        jdbc.update("delete from users")
        jdbc.update("insert into users (email, password, name, role) values ('emp@campus.example', 'h', 'Emp', 'EMPLOYEE')")
        assertThrows<BootstrapException> { service.bootstrap("EMP@campus.example", "Bootstr4p!pw", "Emp") }
        assertEquals("EMPLOYEE", jdbc.queryForObject("select role from users where email = 'emp@campus.example'", String::class.java))
    }

    @Test fun `rejects an invalid email, name or password and creates nothing`() {
        assertThrows<BootstrapException> { service.bootstrap("not-an-email", "Bootstr4p!pw", "A") }
        assertThrows<BootstrapException> { service.bootstrap("a@campus.example", "short", "A") }
        assertThrows<BootstrapException> { service.bootstrap("a@campus.example", "é".repeat(40), "A") }
        assertThrows<BootstrapException> { service.bootstrap("a@campus.example", "Bootstr4p!pw", "  ") }
        assertEquals(0L, jdbc.queryForObject("select count(*) from users", Long::class.java))
    }

    @Test fun `concurrent bootstraps create exactly one ADMIN`() {
        val threads = 6
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map { i ->
            pool.submit<Boolean> {
                start.await()
                runCatching { service.bootstrap("admin$i@campus.example", "Bootstr4p!pw", "Admin $i") }.isSuccess
            }
        }
        start.countDown()
        val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, outcomes.count { it }, "exactly one bootstrap may succeed: $outcomes")
        assertEquals(1L, jdbc.queryForObject("select count(*) from users where role = 'ADMIN'", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_events where action = 'USER_ADMIN_BOOTSTRAPPED'", Long::class.java))
    }

    @Test fun `the runner is not part of ordinary startup`() {
        assertEquals(0, context.getBeansOfType(AdminBootstrapRunner::class.java).size)
    }
}
