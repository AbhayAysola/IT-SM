package com.itsm.investigation

import com.itsm.common.error.DatabaseErrorClassifier
import com.itsm.common.error.ErrorCode
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.feedback.Feedback
import com.itsm.feedback.JpaFeedbackStore
import com.itsm.lifecycle.IncidentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * V30 and V31 on a real PostgreSQL 16: migrations apply, Hibernate validates the schema, the stores round-trip
 * the domain objects, writes demand a transaction, and the database itself enforces what the domain promises
 * (append-only, one feedback per incident, visibility, ranges). The tables are append-only, so tests cannot clean up:
 * every test uses its own user and incident id. Skipped (not failed) when Docker is unavailable.
 */
@SpringBootTest // full context, so @Repository exception translation is wired exactly as in production
@Testcontainers(disabledWithoutDocker = true)
class P3PersistenceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var log: JpaInvestigationLog
    @Autowired lateinit var feedback: JpaFeedbackStore
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate

    private val t0 = Instant.parse("2026-11-11T10:00:00Z")
    private fun userId(): Long = jdbc.queryForObject(
        "INSERT INTO users (email, password, name, role) VALUES (?, 'x', 'Tester', 'EMPLOYEE') RETURNING id", Long::class.java, "p3-${UUID.randomUUID()}@campus.example"
    )!!
    private val reporter: Long by lazy { userId() }

    /** A real incident row: V32 makes the timeline and feedback tables reference `incidents`. */
    private fun incident(): String {
        val id = "INC" + UUID.randomUUID().toString().take(8)
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id) VALUES (?, 'Printer is down', 'Nothing prints', ?)", id, reporter)
        return id
    }

    private fun entry(
        inc: String, author: Long, type: InvestigationEntryType = InvestigationEntryType.NOTE, visibility: Visibility = type.defaultVisibility,
        body: String = "checked the logs", at: Instant = t0, state: IncidentState = IncidentState.IN_PROGRESS
    ) = InvestigationEntry(inc, author, type, visibility, body, state, at)

    private fun <T> inTx(block: () -> T): T = tx.execute { block() }!!

    // ---- investigation log
    @Test fun `entries round-trip and come back oldest first`() {
        val inc = incident(); val a = userId()
        val first = entry(inc, a, InvestigationEntryType.FINDING, Visibility.REPORTER_VISIBLE, "root cause: expired secret", t0.plusSeconds(60), IncidentState.IN_PROGRESS)
        val second = entry(inc, a, InvestigationEntryType.ACTION_TAKEN, body = "rotated the secret", at = t0.plusSeconds(120))
        inTx { log.append(second); log.append(first) } // inserted out of order on purpose
        assertEquals(listOf(first, second), log.list(inc))
    }

    @Test fun `entries with the same instant keep insertion order`() {
        val inc = incident(); val a = userId()
        val e = (1..3).map { entry(inc, a, body = "note $it") }
        inTx { e.forEach(log::append) }
        assertEquals(e.map { it.body }, log.list(inc).map { it.body })
    }

    @Test fun `listing is per incident, and unknown incidents are empty`() {
        val a = userId(); val one = incident(); val two = incident()
        inTx { log.append(entry(one, a)); log.append(entry(two, a, body = "other")) }
        assertEquals(listOf("checked the logs"), log.list(one).map { it.body })
        assertTrue(log.list(incident()).isEmpty())
    }

    @Test fun `every entry type and state survives a round trip`() {
        val a = userId(); val inc = incident()
        val all = InvestigationEntryType.entries.mapIndexed { i, t -> entry(inc, a, t, t.defaultVisibility, "body $i", t0.plusSeconds(i.toLong())) } +
            IncidentState.entries.mapIndexed { i, s -> entry(inc, a, state = s, at = t0.plusSeconds(100L + i), body = "state $i") }
        inTx { all.forEach(log::append) }
        assertEquals(all, log.list(inc))
    }

    @Test fun `the policy's visibility filter works on stored entries`() {
        val a = userId(); val inc = incident()
        inTx {
            log.append(entry(inc, a, body = "internal"))
            log.append(entry(inc, a, InvestigationEntryType.INFO_REQUEST, body = "question"))
        }
        val reporter = CurrentUser(3, "e@campus.example", Role.EMPLOYEE)
        assertEquals(listOf("question"), InvestigationPolicy.visibleTo(reporter, log.list(inc)).map { it.body })
    }

    @Test fun `appending outside a transaction fails fast`() {
        assertThrows<IllegalTransactionStateException> { log.append(entry(incident(), userId())) }
    }

    @Test fun `the table is append-only so update, delete and truncate are rejected`() {
        val a = userId(); val inc = incident()
        inTx { log.append(entry(inc, a)) }
        for (sql in listOf("UPDATE investigation_entries SET body = 'x' WHERE incident_id = '$inc'",
                           "DELETE FROM investigation_entries WHERE incident_id = '$inc'",
                           "TRUNCATE investigation_entries")) {
            val e = assertThrows<DataAccessException>(sql) { jdbc.update(sql) }
            assertTrue(e.mostSpecificCause.message!!.contains("append-only"), sql)
        }
        assertEquals(1, log.list(inc).size)
    }

    @Test fun `the database enforces the visibility rule the domain promises`() {
        val a = userId(); val inc = incident()
        fun insert(type: String, visibility: String) = jdbc.update(
            "INSERT INTO investigation_entries (incident_id, author_id, entry_type, visibility, body, incident_state) VALUES (?, ?, ?, ?, 'x', 'IN_PROGRESS')",
            inc, a, type, visibility)
        assertThrows<DataIntegrityViolationException> { insert("INFO_REQUEST", "INTERNAL") }
        assertThrows<DataIntegrityViolationException> { insert("REASSIGNMENT", "REPORTER_VISIBLE") }
        assertEquals(1, insert("NOTE", "REPORTER_VISIBLE"))
        assertEquals(1, insert("NOTE", "INTERNAL"))
    }

    @Test fun `the database rejects bad values and unknown authors`() {
        val a = userId(); val inc = incident()
        fun insert(id: String = inc, author: Long = a, type: String = "NOTE", body: String = "x", state: String = "IN_PROGRESS") = jdbc.update(
            "INSERT INTO investigation_entries (incident_id, author_id, entry_type, visibility, body, incident_state) VALUES (?, ?, ?, 'INTERNAL', ?, ?)",
            id, author, type, body, state)
        assertThrows<DataIntegrityViolationException> { insert(body = "   ") }
        assertThrows<DataIntegrityViolationException> { insert(body = "x".repeat(4001)) }
        assertThrows<DataIntegrityViolationException> { insert(id = "bad id!") }
        assertThrows<DataIntegrityViolationException> { insert(id = "x".repeat(31)) }
        assertThrows<DataIntegrityViolationException> { insert(type = "GOSSIP") }
        assertThrows<DataIntegrityViolationException> { insert(state = "LIMBO") }
        assertThrows<DataIntegrityViolationException> { insert(author = Long.MAX_VALUE) }
        assertEquals(1, insert(body = "x".repeat(4000)))
    }

    // ---- feedback
    private fun fb(inc: String, reporter: Long, rating: Int = 4, comment: String? = "quick fix") = Feedback(inc, reporter, rating, comment, t0)

    @Test fun `feedback round-trips, with and without a comment`() {
        val r = userId(); val one = incident(); val two = incident()
        inTx { feedback.save(fb(one, r)); feedback.save(fb(two, r, 5, null)) }
        assertEquals(fb(one, r), feedback.find(one))
        assertNull(feedback.find(two)!!.comment)
        assertNull(feedback.find(incident()))
    }

    @Test fun `a second feedback for the same incident is rejected, never silently ignored`() {
        val r = userId(); val inc = incident()
        inTx { feedback.save(fb(inc, r, 4)) }
        val e = assertThrows<DataAccessException> { inTx { feedback.save(fb(inc, r, 1, "changed my mind")) } }
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, DatabaseErrorClassifier.classify(e))
        assertEquals(4, feedback.find(inc)!!.rating) // the first one stands
    }

    @Test fun `saving outside a transaction fails fast`() {
        assertThrows<IllegalTransactionStateException> { feedback.save(fb(incident(), userId())) }
    }

    @Test fun `feedback is final so update, delete and truncate are rejected`() {
        val r = userId(); val inc = incident()
        inTx { feedback.save(fb(inc, r)) }
        for (sql in listOf("UPDATE incident_feedback SET rating = 1 WHERE incident_id = '$inc'",
                           "DELETE FROM incident_feedback WHERE incident_id = '$inc'", "TRUNCATE incident_feedback")) {
            assertTrue(assertThrows<DataAccessException>(sql) { jdbc.update(sql) }.mostSpecificCause.message!!.contains("append-only"), sql)
        }
    }

    @Test fun `the database rejects ratings out of range, blank comments and unknown reporters`() {
        val r = userId()
        fun insert(rating: Int = 3, comment: String? = null, reporter: Long = r, id: String = incident()) =
            jdbc.update("INSERT INTO incident_feedback (incident_id, reporter_id, rating, comment) VALUES (?, ?, ?, ?)", id, reporter, rating, comment)
        for (bad in listOf(0, 6)) assertThrows<DataIntegrityViolationException>("rating $bad") { insert(rating = bad) }
        assertThrows<DataIntegrityViolationException> { insert(comment = "   ") }
        assertThrows<DataIntegrityViolationException> { insert(comment = "x".repeat(1001)) }
        assertThrows<DataIntegrityViolationException> { insert(reporter = Long.MAX_VALUE) }
        assertThrows<DataIntegrityViolationException> { insert(id = "bad id!") }
        assertEquals(1, insert(rating = 1)); assertEquals(1, insert(rating = 5, comment = "x".repeat(1000)))
    }

    @Test fun `entries and feedback for an unknown incident are rejected by the foreign key`() {
        val a = userId()
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO investigation_entries (incident_id, author_id, entry_type, visibility, body, incident_state) VALUES ('NOSUCH1', ?, 'NOTE', 'INTERNAL', 'x', 'IN_PROGRESS')", a)
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO incident_feedback (incident_id, reporter_id, rating) VALUES ('NOSUCH1', ?, 3)", a)
        }
    }

    @Test fun `database failures map to the right API errors`() {
        val a = userId()
        val check = assertThrows<DataAccessException> { jdbc.update("INSERT INTO investigation_entries (incident_id, author_id, entry_type, visibility, body, incident_state) VALUES ('X1', ?, 'NOTE', 'INTERNAL', ' ', 'NEW')", a) }
        assertEquals(ErrorCode.INVALID_INPUT, DatabaseErrorClassifier.classify(check))
        val fk = assertThrows<DataAccessException> { jdbc.update("INSERT INTO incident_feedback (incident_id, reporter_id, rating) VALUES ('X2', ?, 3)", Long.MAX_VALUE) }
        assertEquals(ErrorCode.REFERENCE_CONSTRAINT, DatabaseErrorClassifier.classify(fk))
    }
}
