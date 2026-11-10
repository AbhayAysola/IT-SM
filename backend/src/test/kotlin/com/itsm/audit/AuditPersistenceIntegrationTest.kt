package com.itsm.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Role
import com.itsm.common.spi.AuditPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Audit persistence against a real PostgreSQL 16: real Flyway migration (V10), Hibernate `ddl-auto: validate`,
 * the real [AuditPublisher] and the real transaction manager. Nothing about the database is mocked.
 *
 * The table is append-only (its trigger even rejects TRUNCATE), so tests cannot clean up after themselves. Each test
 * therefore writes under its own unique entity id and only ever looks at its own rows.
 *
 * Skipped without Docker locally; in CI (ITSM_REQUIRE_DOCKER=true) DockerAvailabilityGuardTest fails the build
 * instead, and the workflow requires this class to have executed.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuditPersistenceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var publisher: AuditPublisher
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var context: ApplicationContext
    private val json = ObjectMapper()

    private fun uniqueId() = "T" + UUID.randomUUID().toString().replace("-", "").take(20)

    private fun userEvent(entityId: String, action: String = "USER_CREATED", description: String = "created") =
        AuditableEvent(
            actor = AuditActor.UserActor(42L, "someone@campus.example", Role.ADMIN),
            action = action, entityType = "USER", entityId = entityId, description = description
        )

    private fun count(entityId: String) =
        jdbc.queryForObject("select count(*) from audit_events where entity_id = ?", Long::class.java, entityId)

    private fun auditRows(entityId: String) =
        jdbc.queryForList("select * from audit_events where entity_id = ? order by id", entityId)

    // 7 + runtime wiring -------------------------------------------------------------------------------------------

    @Test fun `the persistence listener is a bean in the running application`() {
        assertNotNull(context.getBean(AuditEventPersistenceListener::class.java))
    }

    // 1 -------------------------------------------------------------------------------------------------------------

    @Test fun `a published event is persisted when the business transaction commits`() {
        val id = uniqueId()
        tx.executeWithoutResult { publisher.publish(userEvent(id)) }
        assertEquals(1, count(id))
    }

    @Test fun `nothing is persisted before commit, and the row exists once the transaction completes`() {
        val id = uniqueId()
        tx.executeWithoutResult {
            publisher.publish(userEvent(id))
            // Other connections cannot see it yet: it is part of this uncommitted transaction.
            assertEquals(0L, jdbc.dataSource!!.connection.use { c ->
                c.prepareStatement("select count(*) from audit_events where entity_id = ?").use { s ->
                    s.setString(1, id); s.executeQuery().use { it.next(); it.getLong(1) }
                }
            })
        }
        assertEquals(1, count(id))
    }

    // 2 -------------------------------------------------------------------------------------------------------------

    @Test fun `persisted columns faithfully represent a user-actor event`() {
        val id = uniqueId()
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS) // PostgreSQL timestamptz has microsecond precision
        val event = AuditableEvent(
            actor = AuditActor.UserActor(42L, "someone@campus.example", Role.ADMIN),
            action = "INCIDENT_ASSIGNED", entityType = "INCIDENT", entityId = id, incidentId = "INC1024",
            previousState = "CLASSIFIED", newState = "ASSIGNED", description = "Assigned to technician 7",
            metadata = mapOf(
                "technicianId" to 7L, "attempt" to 2, "strategy" to "LOAD_BASED", "manual" to false,
                "score" to 0.5, "cost" to BigDecimal("12.50"), "tags" to listOf("a", "b")
            ),
            occurredAt = at
        )
        tx.executeWithoutResult { publisher.publish(event) }

        val row = auditRows(id).single()
        assertEquals("USER", row["actor_type"])
        assertEquals(42L, row["actor_id"])
        assertEquals("ADMIN", row["actor_role"])
        assertNull(row["actor_name"])
        assertEquals("INCIDENT_ASSIGNED", row["action"])
        assertEquals("INCIDENT", row["entity_type"])
        assertEquals("INC1024", row["incident_id"])
        assertEquals("CLASSIFIED", row["previous_state"])
        assertEquals("ASSIGNED", row["new_state"])
        assertEquals("Assigned to technician 7", row["description"])
        assertNotNull(row["event_id"])
        assertNotNull(row["recorded_at"])
        assertEquals(at, jdbc.queryForObject("select occurred_at from audit_events where entity_id = ?",
            OffsetDateTime::class.java, id).toInstant())

        val metadata = json.readTree(jdbc.queryForObject(
            "select metadata::text from audit_events where entity_id = ?", String::class.java, id))
        assertEquals(7, metadata["technicianId"].asLong())
        assertEquals(2, metadata["attempt"].asInt())
        assertEquals("LOAD_BASED", metadata["strategy"].asText())
        assertTrue(metadata["manual"].isBoolean && !metadata["manual"].asBoolean())
        assertEquals(0.5, metadata["score"].asDouble())
        assertEquals(0, BigDecimal("12.50").compareTo(metadata["cost"].decimalValue()))
        assertEquals(listOf("a", "b"), metadata["tags"].map { it.asText() })
    }

    @Test fun `a system actor is stored by name, with no user id or role`() {
        val id = uniqueId()
        tx.executeWithoutResult {
            publisher.publish(AuditableEvent(AuditActor.system("SLA_MONITOR"), "SLA_BREACHED", "INCIDENT", id,
                description = "breach"))
        }
        val row = auditRows(id).single()
        assertEquals("SYSTEM", row["actor_type"])
        assertEquals("SLA_MONITOR", row["actor_name"])
        assertNull(row["actor_id"])
        assertNull(row["actor_role"])
        assertEquals("{}", jdbc.queryForObject("select metadata::text from audit_events where entity_id = ?",
            String::class.java, id))
    }

    @Test fun `the actor's email address is not stored anywhere in the row`() {
        val id = uniqueId()
        tx.executeWithoutResult { publisher.publish(userEvent(id)) }
        val asText = jdbc.queryForObject("select t::text from audit_events t where entity_id = ?", String::class.java, id)
        assertTrue("someone@campus.example" !in asText, "email leaked into the audit row: $asText")
    }

    // 3 -------------------------------------------------------------------------------------------------------------

    @Test fun `an event is rolled back with its transaction when the business code throws`() {
        val id = uniqueId()
        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                publisher.publish(userEvent(id))
                throw IllegalStateException("business failure")
            }
        }
        assertEquals(0, count(id))
    }

    @Test fun `an event is rolled back when the transaction is marked rollback-only`() {
        val id = uniqueId()
        tx.executeWithoutResult { status ->
            publisher.publish(userEvent(id))
            status.setRollbackOnly()
        }
        assertEquals(0, count(id))
    }

    @Test fun `the audit row and the business change commit or roll back together`() {
        val email = "atomic-${uniqueId()}@campus.example".lowercase()   // V15 stores emails lower-case
        val id = uniqueId()
        fun usersWith(e: String) = jdbc.queryForObject("select count(*) from users where email = ?", Long::class.java, e)
        fun createUser() = jdbc.update("insert into users (email, password, name) values (?, 'x', 'N')", email)

        assertThrows<IllegalStateException> {
            tx.executeWithoutResult { createUser(); publisher.publish(userEvent(id)); throw IllegalStateException() }
        }
        assertEquals(0, usersWith(email)); assertEquals(0, count(id))

        tx.executeWithoutResult { createUser(); publisher.publish(userEvent(id)) }
        assertEquals(1, usersWith(email)); assertEquals(1, count(id))
    }

    @Test fun `publishing outside a transaction is refused and persists nothing`() {
        val id = uniqueId()
        assertThrows<IllegalTransactionStateException> { publisher.publish(userEvent(id)) }
        assertEquals(0, count(id))
    }

    // 4 -------------------------------------------------------------------------------------------------------------

    @Test fun `several events in one transaction are all persisted in publish order`() {
        val id = uniqueId()
        tx.executeWithoutResult {
            publisher.publish(userEvent(id, "USER_CREATED", "first"))
            publisher.publish(userEvent(id, "USER_ROLE_CHANGED", "second"))
            publisher.publish(userEvent(id, "USER_DEACTIVATED", "third"))
        }
        val rows = auditRows(id)
        assertEquals(listOf("first", "second", "third"), rows.map { it["description"] })
        assertEquals(listOf("USER_CREATED", "USER_ROLE_CHANGED", "USER_DEACTIVATED"), rows.map { it["action"] })
        assertEquals(3, rows.map { it["event_id"] }.toSet().size, "each event has its own stable id")
    }

    @Test fun `a failing transaction discards every event it published`() {
        val id = uniqueId()
        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                publisher.publish(userEvent(id, "USER_CREATED")); publisher.publish(userEvent(id, "USER_UPDATED"))
                throw IllegalStateException()
            }
        }
        assertEquals(0, count(id))
    }

    // 5, 6 ----------------------------------------------------------------------------------------------------------

    @Test fun `direct SQL UPDATE of an audit row is rejected by the database trigger`() {
        val id = uniqueId()
        tx.executeWithoutResult { publisher.publish(userEvent(id, description = "original")) }

        val ex = assertThrows<DataAccessException> {
            jdbc.update("update audit_events set description = 'tampered' where entity_id = ?", id)
        }
        assertTrue(ex.mostSpecificCause.message!!.contains("append-only"), ex.mostSpecificCause.message)
        assertEquals("original", auditRows(id).single()["description"])
    }

    @Test fun `direct SQL DELETE of an audit row is rejected by the database trigger`() {
        val id = uniqueId()
        tx.executeWithoutResult { publisher.publish(userEvent(id)) }

        val ex = assertThrows<DataAccessException> { jdbc.update("delete from audit_events where entity_id = ?", id) }
        assertTrue(ex.mostSpecificCause.message!!.contains("append-only"), ex.mostSpecificCause.message)
        assertEquals(1, count(id))
    }

    @Test fun `UPDATE and DELETE are rejected for every row, and TRUNCATE is rejected too`() {
        val id = uniqueId()
        tx.executeWithoutResult { publisher.publish(userEvent(id)) }
        assertThrows<DataAccessException> { jdbc.update("update audit_events set action = action") }
        assertThrows<DataAccessException> { jdbc.update("delete from audit_events") }
        assertThrows<DataAccessException> { jdbc.execute("truncate table audit_events") }
        assertEquals(1, count(id))
    }

    @Test fun `an UPDATE inside a larger transaction aborts that whole transaction`() {
        val id = uniqueId()
        val email = "guard-${uniqueId()}@campus.example".lowercase()
        tx.executeWithoutResult { publisher.publish(userEvent(id)) }
        assertThrows<DataAccessException> {
            tx.executeWithoutResult {
                jdbc.update("insert into users (email, password, name) values (?, 'x', 'N')", email)
                jdbc.update("update audit_events set description = 'x' where entity_id = ?", id)
            }
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from users where email = ?", Long::class.java, email))
    }

    // schema integrity ----------------------------------------------------------------------------------------------

    @Test fun `the database rejects an actor that is neither a complete user nor a complete system actor`() {
        assertThrows<DataAccessException> {
            jdbc.update("""insert into audit_events (occurred_at, actor_type, action, entity_type, description)
                           values (now(), 'USER', 'X_Y_Z', 'USER', 'no actor id')""")
        }
        assertThrows<DataAccessException> {
            jdbc.update("""insert into audit_events (occurred_at, actor_type, actor_id, actor_role, actor_name, action,
                           entity_type, description) values (now(), 'SYSTEM', 1, 'ADMIN', 'JOB', 'X_Y_Z', 'USER', 'mixed')""")
        }
    }
}
