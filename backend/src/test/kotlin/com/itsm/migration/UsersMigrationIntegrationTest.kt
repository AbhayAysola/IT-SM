package com.itsm.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * V15 against real PostgreSQL 16 with LEGACY data: migrate to V14, insert boilerplate-era users, then apply V15.
 * Each test gets its own throwaway container because the second one deliberately leaves the database un-migrated.
 */
@Testcontainers(disabledWithoutDocker = true)
class UsersMigrationIntegrationTest {

    private fun withDatabase(block: (Flyway, JdbcTemplate) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16").use { pg ->
            pg.start()
            val flyway = Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load()
            block(flyway, JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)))
        }
    }

    private fun migrateTo(flyway: Flyway, version: String) =
        Flyway.configure().configuration(flyway.configuration).target(version).load().migrate()

    @Test fun `legacy users are kept, USER becomes EMPLOYEE, emails are normalised and a second run is a no-op`() = withDatabase { flyway, jdbc ->
        migrateTo(flyway, "14")
        jdbc.update("insert into users (email, password, name) values ('Legacy.User@Campus.Example ', 'hash1', 'Legacy')")   // default role USER
        jdbc.update("insert into users (email, password, name, role) values ('tech@campus.example', 'hash2', 'Tech', 'TECHNICIAN')")
        jdbc.update("insert into users (email, password, name, role) values ('boss@campus.example', 'hash3', 'Boss', ' admin ')")
        assertEquals("USER", jdbc.queryForObject("select role from users where name = 'Legacy'", String::class.java))

        val v15 = Flyway.configure().configuration(flyway.configuration).target("15").load()   // this test is about V15 only
        val result = v15.migrate()
        assertEquals(1, result.migrationsExecuted)
        assertEquals(3L, jdbc.queryForObject("select count(*) from users", Long::class.java), "no user may be lost")
        val rows = jdbc.queryForList("select email, role, active, token_version, version, password from users order by id")
        assertEquals(listOf("legacy.user@campus.example", "tech@campus.example", "boss@campus.example"), rows.map { it["email"] })
        assertEquals(listOf("EMPLOYEE", "TECHNICIAN", "ADMIN"), rows.map { it["role"] })
        assertTrue(rows.all { it["active"] == true && it["token_version"] == 0 && it["version"] == 0L })
        assertEquals(listOf("hash1", "hash2", "hash3"), rows.map { it["password"] }, "password hashes untouched")

        assertEquals(0, v15.migrate().migrationsExecuted)
        v15.validate()
    }

    @Test fun `V15 aborts, changing nothing, when emails collide case-insensitively`() = withDatabase { flyway, jdbc ->
        migrateTo(flyway, "14")
        jdbc.update("insert into users (email, password, name) values ('Dup@Campus.Example', 'h1', 'One')")
        jdbc.update("insert into users (email, password, name) values ('dup@campus.example', 'h2', 'Two')")
        val ex = assertThrows<FlywayException> { flyway.migrate() }
        assertTrue(ex.message!!.contains("share an email"), ex.message)
        assertEquals(2L, jdbc.queryForObject("select count(*) from users", Long::class.java), "nothing merged or dropped")
        assertEquals("USER", jdbc.queryForObject("select role from users where name = 'One'", String::class.java), "transaction rolled back")
    }

    @Test fun `V15 aborts on a role it cannot map`() = withDatabase { flyway, jdbc ->
        migrateTo(flyway, "14")
        jdbc.update("insert into users (email, password, name, role) values ('x@campus.example', 'h', 'X', 'SUPERUSER')")
        val ex = assertThrows<FlywayException> { flyway.migrate() }
        assertNotNull(ex.message)
        assertEquals(1L, jdbc.queryForObject("select count(*) from users", Long::class.java))
    }
}
