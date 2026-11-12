package com.itsm.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** V16 on real PostgreSQL 16: existing data untouched, nothing auto-created, and the database itself refuses invalid profiles. */
@Testcontainers(disabledWithoutDocker = true)
class TechnicianProfilesMigrationIntegrationTest {

    private fun withDatabase(block: (Flyway, JdbcTemplate) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16").use { pg ->
            pg.start()
            val flyway = Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load()
            block(flyway, JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)))
        }
    }

    private fun sqlState(block: () -> Unit): String? {
        try { block() } catch (e: DataAccessException) {
            var c: Throwable? = e
            while (c != null) { if (c is SQLException) return c.sqlState; c = c.cause }
        }
        return null
    }

    private fun user(jdbc: JdbcTemplate, email: String, role: String): Long =
        jdbc.queryForObject("insert into users (email, password, name, role) values (?, 'h', ?, ?) returning id", Long::class.java, email, email, role)!!

    private fun profile(jdbc: JdbcTemplate, user: Long, lead: Boolean = false, reportsTo: Long? = null, workload: Int = 5, years: Int = 1) =
        jdbc.update("insert into technician_profiles (user_id, max_workload, experience_years, available, is_team_lead, reports_to_user_id) values (?,?,?,true,?,?)", user, workload, years, lead, reportsTo)

    @Test fun `existing accounts are untouched, no profile is created, and a second run is a no-op`() = withDatabase { flyway, jdbc ->
        Flyway.configure().configuration(flyway.configuration).target("15").load().migrate()
        val emp = user(jdbc, "emp@campus.example", "EMPLOYEE"); val adm = user(jdbc, "adm@campus.example", "ADMIN"); val tech = user(jdbc, "tech@campus.example", "TECHNICIAN")
        val before = jdbc.queryForList("select * from users order by id")

        // This test is about V16 only: later migrations (other teams' V30+) must not change what it asserts.
        val v16 = Flyway.configure().configuration(flyway.configuration).target("16").load()
        assertEquals(1, v16.migrate().migrationsExecuted)
        assertEquals(before, jdbc.queryForList("select * from users order by id"), "users must be bit-for-bit unchanged")
        assertEquals(0L, jdbc.queryForObject("select count(*) from technician_profiles", Long::class.java), "no profile may be invented, even for the TECHNICIAN")
        assertEquals(0L, jdbc.queryForObject("select count(*) from technician_skills", Long::class.java))
        assertEquals(setOf(emp, adm, tech), jdbc.queryForList("select id from users", Long::class.java).toSet())

        assertEquals(0, v16.migrate().migrationsExecuted); v16.validate()
        assertEquals("16", v16.info().current().version.version)
    }

    @Test fun `the database refuses profiles for non-technicians, duplicates, bad values, self-reporting and non-leads as managers`() = withDatabase { flyway, jdbc ->
        flyway.migrate()
        val emp = user(jdbc, "e@campus.example", "EMPLOYEE"); val adm = user(jdbc, "a@campus.example", "ADMIN")
        val t1 = user(jdbc, "t1@campus.example", "TECHNICIAN"); val t2 = user(jdbc, "t2@campus.example", "TECHNICIAN")

        assertEquals("IT002", sqlState { profile(jdbc, emp) }, "EMPLOYEE")
        assertEquals("IT002", sqlState { profile(jdbc, adm) }, "ADMIN")
        assertEquals("IT002", sqlState { profile(jdbc, 987654321) }, "unknown user (rejected by the role guard before the FK)")

        profile(jdbc, t1)
        assertEquals("23505", sqlState { profile(jdbc, t1) }, "duplicate profile")
        assertEquals("23514", sqlState { profile(jdbc, t2, workload = 0) })
        assertEquals("23514", sqlState { profile(jdbc, t2, workload = 101) })
        assertEquals("23514", sqlState { profile(jdbc, t2, years = -1) })
        assertEquals("23514", sqlState { profile(jdbc, t2, years = 61) })
        assertTrue(sqlState { profile(jdbc, t2, reportsTo = t2) } in setOf("IT002", "23514"), "self reporting")
        assertEquals("23503", sqlState { profile(jdbc, t2, reportsTo = t1) }, "manager is not flagged as a team lead")
        assertEquals("IT002", sqlState { profile(jdbc, t2, reportsTo = emp) }, "manager has no profile (guard)")
        assertEquals("IT002", sqlState { jdbc.update("update technician_profiles set user_id = ? where user_id = ?", t2, t1) }, "user_id immutable")
        assertEquals(1L, jdbc.queryForObject("select count(*) from technician_profiles", Long::class.java))
    }

    @Test fun `skills are unique per technician, bounded, and tied to real profiles and categories`() = withDatabase { flyway, jdbc ->
        flyway.migrate()
        val t1 = user(jdbc, "t1@campus.example", "TECHNICIAN"); profile(jdbc, t1)
        val cat = jdbc.queryForObject("insert into incident_categories (code, name) values ('SKILLTEST_A', 'Skill test A') returning id", Long::class.java)!!
        jdbc.update("insert into technician_skills values (?,?,3)", t1, cat)
        assertEquals("23505", sqlState { jdbc.update("insert into technician_skills values (?,?,4)", t1, cat) })
        val cat2 = jdbc.queryForObject("insert into incident_categories (code, name) values ('SKILLTEST_B', 'Skill test B') returning id", Long::class.java)!!
        assertEquals("23514", sqlState { jdbc.update("insert into technician_skills values (?,?,0)", t1, cat2) })
        assertEquals("23514", sqlState { jdbc.update("insert into technician_skills values (?,?,6)", t1, cat2) })
        assertEquals("23503", sqlState { jdbc.update("insert into technician_skills values (?,?,3)", t1, 987654321) })
        assertEquals("23503", sqlState { jdbc.update("insert into technician_skills values (?,?,3)", 987654321, cat2) })
    }

    @Test fun `reporting lines reject cycles, inactive managers and deactivating a manager with active reports`() = withDatabase { flyway, jdbc ->
        flyway.migrate()
        val ids = (1..3).map { user(jdbc, "l$it@campus.example", "TECHNICIAN") }
        val (a, b, c) = ids
        profile(jdbc, a, lead = true); profile(jdbc, b, lead = true, reportsTo = a); profile(jdbc, c, reportsTo = b)

        assertEquals("IT002", sqlState { jdbc.update("update technician_profiles set reports_to_user_id = ? where user_id = ?", b, a) }, "a -> b -> a")
        assertEquals("23503", sqlState { jdbc.update("update technician_profiles set is_team_lead = false where user_id = ?", b) }, "b still has a report")
        assertEquals("IT002", sqlState { jdbc.update("update technician_profiles set active = false where user_id = ?", b) }, "b has an active report")
        jdbc.update("update technician_profiles set active = false where user_id = ?", c)
        jdbc.update("update technician_profiles set active = false where user_id = ?", b)         // now allowed
        val d = user(jdbc, "d@campus.example", "TECHNICIAN")
        assertEquals("IT002", sqlState { profile(jdbc, d, reportsTo = b) }, "inactive manager")
        assertEquals("IT002", sqlState { jdbc.update("update technician_profiles set active = true where user_id = ?", c) }, "reactivating under an inactive manager")
    }

    @Test fun `role changes are blocked while the profile is active and allowed once it is history`() = withDatabase { flyway, jdbc ->
        flyway.migrate()
        val t1 = user(jdbc, "t1@campus.example", "TECHNICIAN"); profile(jdbc, t1)
        assertEquals("IT001", sqlState { jdbc.update("update users set role = 'EMPLOYEE' where id = ?", t1) })
        assertEquals("IT001", sqlState { jdbc.update("update users set role = 'ADMIN' where id = ?", t1) })
        jdbc.update("update users set name = 'Renamed', active = false where id = ?", t1)                // unrelated changes are fine
        jdbc.update("update technician_profiles set active = false where user_id = ?", t1)
        jdbc.update("update users set role = 'EMPLOYEE' where id = ?", t1)
        assertEquals(1L, jdbc.queryForObject("select count(*) from technician_profiles where user_id = ?", Long::class.java, t1), "history is kept")
        assertEquals("IT002", sqlState { jdbc.update("update technician_profiles set active = true where user_id = ?", t1) }, "no longer a TECHNICIAN")
        jdbc.update("update users set role = 'TECHNICIAN' where id = ?", t1)
        jdbc.update("update technician_profiles set active = true where user_id = ?", t1)
        assertNotNull(jdbc.queryForObject("select version from technician_profiles where user_id = ?", Long::class.java, t1))
        assertTrue(true)
    }

    @Test fun `an inactive manager account cannot be a reporting manager, and activating a report under one is refused`() = withDatabase { flyway, jdbc ->
        flyway.migrate()
        val lead = user(jdbc, "lead@campus.example", "TECHNICIAN"); val member = user(jdbc, "member@campus.example", "TECHNICIAN")
        profile(jdbc, lead, lead = true)
        jdbc.update("update users set active = false where id = ?", lead)
        assertEquals("IT002", sqlState { profile(jdbc, member, reportsTo = lead) }, "manager account inactive")
        jdbc.update("update users set active = true where id = ?", lead)
        profile(jdbc, member, reportsTo = lead)
        assertEquals(1L, jdbc.queryForObject("select count(*) from technician_profiles where reports_to_user_id = ?", Long::class.java, lead))
    }

    @Test fun `a role change racing a profile insert cannot leave a profile on a non-technician`() = withDatabase { flyway, jdbc ->
        flyway.migrate()
        val ds = jdbc.dataSource!!
        val u = user(jdbc, "race@campus.example", "TECHNICIAN")
        val pool = Executors.newSingleThreadExecutor()
        ds.connection.use { c1 ->
            c1.autoCommit = false
            c1.prepareStatement("insert into technician_profiles (user_id, max_workload, experience_years, available) values (?, 5, 1, true)").use { it.setLong(1, u); it.executeUpdate() }
            // c1 now holds the profile row and a FOR SHARE lock on the user row; a role change must wait for it.
            val change = pool.submit<String?> {
                ds.connection.use { c2 ->
                    try { c2.createStatement().use { it.executeUpdate("update users set role = 'EMPLOYEE' where id = $u") }; null }
                    catch (e: SQLException) { e.sqlState }
                }
            }
            Thread.sleep(700)
            assertTrue(!change.isDone, "the role change must block while the profile insert is uncommitted")
            c1.commit()
            assertEquals("IT001", change.get(15, TimeUnit.SECONDS), "after the commit the guard sees the active profile and refuses")
        }
        pool.shutdown()
        assertEquals("TECHNICIAN", jdbc.queryForObject("select role from users where id = ?", String::class.java, u))
    }
}
