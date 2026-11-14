package com.itsm.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The opt-in two-role privilege model (db/callback/afterMigrate__runtime_role_grants.sql) against a real PostgreSQL: the owner migrates, the
 * runtime role can do the application's work, and it cannot defeat the append-only audit trail, even by switching the trigger off.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeRoleGrantsIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    private val owner = "itsm_owner"
    private val runtime = "itsm_app"

    private fun connect(user: String, password: String): Connection = DriverManager.getConnection(postgres.jdbcUrl, user, password)

    @BeforeAll fun migrateAsOwner() {
        connect(postgres.username, postgres.password).use { c ->
            c.createStatement().use {
                it.execute("CREATE ROLE $owner LOGIN PASSWORD 'owner-pw'")
                it.execute("CREATE ROLE $runtime LOGIN PASSWORD 'app-pw'")
                it.execute("GRANT ALL ON DATABASE ${postgres.databaseName} TO $owner")
                it.execute("GRANT ALL ON SCHEMA public TO $owner")
            }
        }
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, owner, "owner-pw")
            .locations("classpath:db/migration", "classpath:db/callback")
            .placeholders(mapOf("runtimerole" to runtime))
            .baselineOnMigrate(false)
            .load()
        flyway.migrate()
    }

    private fun insertAudit(c: Connection) = c.createStatement().use {
        it.executeUpdate(
            "INSERT INTO audit_events (occurred_at, actor_type, actor_name, action, entity_type, description) " +
                "VALUES (now(), 'SYSTEM', 'TEST', 'TEST_ACTION', 'TEST', 'row written by the runtime role')"
        )
    }

    @Test fun `the runtime role can do the application's work`() {
        connect(runtime, "app-pw").use { c ->
            assertEquals(1, insertAudit(c))
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM audit_events").use { rs -> rs.next(); assertTrue(rs.getInt(1) >= 1) }
                s.executeUpdate("INSERT INTO it_services (name, business_criticality) VALUES ('role-test', 3)")
                assertEquals(1, s.executeUpdate("UPDATE it_services SET description = 'edited' WHERE name = 'role-test'"))
                s.executeQuery("SELECT nextval(pg_get_serial_sequence('incident_categories', 'id'))").use { it.next() }
            }
        }
    }

    @Test fun `the runtime role cannot change or remove the audit trail, or switch its guard off`() {
        connect(runtime, "app-pw").use { c ->
            insertAudit(c)
            for (sql in listOf(
                "UPDATE audit_events SET description = 'tampered'",
                "DELETE FROM audit_events",
                "TRUNCATE audit_events",
                "ALTER TABLE audit_events DISABLE TRIGGER trg_audit_events_no_update_delete",
                "DROP TRIGGER trg_audit_events_no_update_delete ON audit_events",
                "ALTER TABLE audit_events DISABLE TRIGGER ALL",
                "DROP TABLE audit_events",
            )) {
                val e = assertThrows(SQLException::class.java, { c.createStatement().use { it.execute(sql) } }, sql)
                assertTrue(e.message!!.contains("permission denied") || e.message!!.contains("must be owner"), "$sql -> ${e.message}")
            }
        }
    }

    @Test fun `the runtime role cannot touch the migration history or create objects`() {
        connect(runtime, "app-pw").use { c ->
            for (sql in listOf("SELECT * FROM flyway_schema_history", "CREATE TABLE sneaky (id int)")) {
                assertThrows(SQLException::class.java, { c.createStatement().use { it.execute(sql) } }, sql)
            }
        }
    }

    @Test fun `without the runtime role the callback fails loudly instead of granting nothing`() {
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, owner, "owner-pw")
            .locations("classpath:db/migration", "classpath:db/callback")
            .placeholders(mapOf("runtimerole" to "no_such_role"))
            .load()
        val e = assertThrows(Exception::class.java) { flyway.migrate() }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("does not exist") == true }, e.toString())
    }
}
