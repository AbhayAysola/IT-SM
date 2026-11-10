package com.itsm.admin.technician

import com.itsm.admin.AdminApiTestSupport
import com.itsm.admin.Caller
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserDirectory
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Counts every PreparedStatement the application prepares, so the N+1 test measures queries rather than guessing. */
object StatementCounter { val count = AtomicInteger() }

@TestConfiguration
class StatementCountingConfig {
    @Bean
    fun statementCountingPostProcessor() = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean !is DataSource || Proxy.isProxyClass(bean.javaClass)) return bean
            return Proxy.newProxyInstance(bean.javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
                val result = try { method.invoke(bean, *(args ?: emptyArray())) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
                if (method.name == "getConnection" && result is java.sql.Connection) {
                    Proxy.newProxyInstance(result.javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, m, a ->
                        if (m.name == "prepareStatement") StatementCounter.count.incrementAndGet()
                        try { m.invoke(result, *(a ?: emptyArray())) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
                    }
                } else result
            }
        }
    }
}

/** UserDirectory technician semantics on PostgreSQL 16: usable technicians only, deterministic order, no N+1, no invented values. */
@SpringBootTest
@Import(StatementCountingConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class TechnicianDirectoryIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var directory: UserDirectory
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private lateinit var t: AdminApiTestSupport

    @BeforeEach fun setUp() {
        t = AdminApiTestSupport(users, jwt, jdbc)
        // The directory is global: start every test from "no technicians".
        jdbc.update("delete from technician_skills"); jdbc.update("update technician_profiles set reports_to_user_id = null")
        jdbc.update("delete from technician_profiles")
    }

    private fun tech(name: String, available: Boolean = true, lead: Boolean = false, reportsTo: Long? = null, workload: Int = 5, years: Int = 2): Caller {
        val c = t.caller(Role.TECHNICIAN)
        jdbc.update("update users set name = ? where id = ?", name, c.id)
        jdbc.update(
            "insert into technician_profiles (user_id, max_workload, experience_years, available, is_team_lead, reports_to_user_id) values (?,?,?,?,?,?)",
            c.id, workload, years, available, lead, reportsTo
        )
        return c
    }

    private fun category(active: Boolean = true) = jdbc.queryForObject(
        "insert into incident_categories (code, name, active) values (?, ?, ?) returning id", Long::class.java,
        "D" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase(), "Dir " + UUID.randomUUID(), active
    )!!

    private fun skill(user: Caller, category: Long, level: Int) = jdbc.update("insert into technician_skills values (?,?,?)", user.id, category, level)

    @Test fun `findTechnician returns the usable technician with the stored values and nothing invented`() {
        val cat = category(); val a = tech("Ada", available = false, lead = true, workload = 7, years = 11)
        skill(a, cat, 4)
        val s = directory.findTechnician(a.id)!!
        assertEquals(TechnicianSummary(a.id, "Ada", a.email, false, 7, 11, true, null, mapOf(cat to 4)), s)
        assertEquals(a.id, directory.requireById(a.id).id)
    }

    @Test fun `missing profile, inactive profile, inactive user and non-technician role all give null`() {
        val employee = t.caller(Role.EMPLOYEE); assertNull(directory.findTechnician(employee.id))
        assertNull(directory.findTechnician(987654321))

        val inactiveProfile = tech("P"); jdbc.update("update technician_profiles set active = false where user_id = ?", inactiveProfile.id)
        assertNull(directory.findTechnician(inactiveProfile.id))
        assertNotNull(directory.findById(inactiveProfile.id), "the account itself is still reachable through findById")

        val inactiveUser = tech("U"); jdbc.update("update users set active = false where id = ?", inactiveUser.id)
        assertNull(directory.findTechnician(inactiveUser.id))
        assertTrue(directory.listAvailableTechnicians().isEmpty())
    }

    @Test fun `listAvailable is ordered by name then id, honours the availability flag and never looks at workload`() {
        val zed = tech("Zed", workload = 1); val amy = tech("amy", workload = 100); val bob1 = tech("Bob"); val bob2 = tech("Bob")
        tech("Off", available = false)
        val names = directory.listAvailableTechnicians().map { it.name to it.userId }
        assertEquals(listOf("amy" to amy.id, "Bob" to bob1.id, "Bob" to bob2.id, "Zed" to zed.id), names)
        assertEquals(names, directory.listAvailableTechnicians().map { it.name to it.userId }, "stable across calls")
    }

    @Test fun `category filter needs a skill for an ACTIVE category, and inactive categories are not reported`() {
        val c1 = category(); val c2 = category(); val retired = category()
        val a = tech("A"); val b = tech("B"); val c = tech("C")
        skill(a, c1, 5); skill(a, retired, 3); skill(b, c2, 1); skill(c, retired, 2)
        jdbc.update("update incident_categories set active = false where id = ?", retired)

        assertEquals(listOf(a.id), directory.listAvailableTechnicians(c1).map { it.userId })
        assertEquals(listOf(b.id), directory.listAvailableTechnicians(c2).map { it.userId })
        assertTrue(directory.listAvailableTechnicians(retired).isEmpty())
        assertEquals(mapOf(c1 to 5), directory.findTechnician(a.id)!!.skills, "skills of a deactivated category are not reported")
        assertTrue(directory.findTechnician(c.id)!!.skills.isEmpty())
        assertEquals(3, directory.listAvailableTechnicians(null).size)
    }

    @Test fun `escalationChain walks the reporting line nearest first, skips inactive managers and excludes the technician`() {
        val top = tech("Top", lead = true)
        val mid = tech("Mid", lead = true, reportsTo = top.id)
        val low = tech("Low", reportsTo = mid.id)
        assertEquals(listOf(mid.id, top.id), directory.escalationChain(low.id).map { it.id })
        assertEquals(listOf(top.id), directory.escalationChain(mid.id).map { it.id })
        assertTrue(directory.escalationChain(top.id).isEmpty())
        assertTrue(directory.escalationChain(987654321).isEmpty())
        assertEquals("Mid", directory.escalationChain(low.id).first().name)

        jdbc.update("update users set active = false where id = ?", mid.id)                  // an inactive manager is skipped, the walk continues
        assertEquals(listOf(top.id), directory.escalationChain(low.id).map { it.id })
        jdbc.update("update users set active = true where id = ?", mid.id)
        // A manager's profile can only be inactive while nobody ACTIVE reports to it, i.e. for history: low retired first, then mid.
        jdbc.update("update technician_profiles set active = false where user_id = ?", low.id)
        jdbc.update("update technician_profiles set active = false where user_id = ?", mid.id)
        assertEquals(listOf(top.id), directory.escalationChain(low.id).map { it.id }, "the inactive profile mid is skipped")
        assertNull(directory.findTechnician(low.id))
    }

    @Test fun `the query count does not grow with the number of technicians`() {
        val cat = category()
        fun measure(block: () -> Unit): Int { val before = StatementCounter.count.get(); block(); return StatementCounter.count.get() - before }

        repeat(2) { skill(tech("N$it"), cat, 3) }
        val smallList = measure { directory.listAvailableTechnicians(null) }
        repeat(25) { skill(tech("M$it"), cat, 3) }
        val bigList = measure { assertEquals(27, directory.listAvailableTechnicians(null).size) }
        assertEquals(smallList, bigList, "listAvailableTechnicians must be constant-query")
        assertTrue(bigList <= 3, "expected at most a handful of statements, got $bigList")

        val top = tech("T", lead = true); var parent = top
        repeat(6) { parent = tech("L$it", lead = true, reportsTo = parent.id) }
        val chainStatements = measure { assertEquals(6, directory.escalationChain(parent.id).size) }
        assertTrue(chainStatements <= 3, "escalationChain must be one query, got $chainStatements")
    }
}
