package com.itsm.audit

import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.time.Instant

/** Database-free guards for the properties the integration test cannot express as easily. */
class AuditEventPersistenceListenerTest {

    private val event = AuditableEvent(
        AuditActor.UserActor(9L, "u@campus.example", Role.TECHNICIAN), "INCIDENT_RESOLVED", "INCIDENT", "INC1",
        incidentId = "INC1", previousState = "IN_PROGRESS", newState = "RESOLVED", description = "done",
        metadata = mapOf("minutes" to 12, "cost" to BigDecimal.ONE)
    )

    @Test fun `the listener runs BEFORE_COMMIT and never after, standalone or in a new transaction`() {
        val method = AuditEventPersistenceListener::class.java.getMethod("persist", AuditableEvent::class.java)
        val annotation = method.getAnnotation(TransactionalEventListener::class.java)
        assertEquals(TransactionPhase.BEFORE_COMMIT, annotation.phase)
        assertFalse(annotation.fallbackExecution, "an event outside a transaction must not be persisted on its own")
        // @Transactional (e.g. REQUIRES_NEW) on the listener would detach the audit row from the business transaction.
        assertTrue(method.getAnnotation(Transactional::class.java) == null)
    }

    /** Hand-written fake: Mockito matchers cannot produce a non-null AuditEventEntity for a Kotlin parameter. */
    private class FakeRepository(private val failure: RuntimeException? = null) : AuditEventRepository {
        val saved = mutableListOf<AuditEventEntity>()
        override fun save(entity: AuditEventEntity): AuditEventEntity {
            failure?.let { throw it }
            saved += entity
            return entity
        }
    }

    @Test fun `a persistence failure propagates and is never swallowed`() {
        val failure = DataIntegrityViolationException("boom")
        val thrown = assertThrows<DataIntegrityViolationException> {
            AuditEventPersistenceListener(FakeRepository(failure)).persist(event)
        }
        assertSame(failure, thrown)
    }

    @Test fun `the listener saves exactly one entity mapped from the event`() {
        val repository = FakeRepository()
        AuditEventPersistenceListener(repository).persist(event)
        assertEquals(1, repository.saved.size)
        assertEquals("INCIDENT_RESOLVED", repository.saved.single().action)
    }

    @Test fun `user events map to id and role only`() {
        val e = AuditEventEntity.from(event)
        assertEquals("USER", e.actorType); assertEquals(9L, e.actorId); assertEquals("TECHNICIAN", e.actorRole)
        assertNull(e.actorName)
        assertEquals("INC1", e.incidentId); assertEquals("IN_PROGRESS", e.previousState); assertEquals("RESOLVED", e.newState)
        assertEquals(event.occurredAt, e.occurredAt); assertEquals(event.metadata, e.metadata)
        assertTrue(AuditEventEntity::class.java.declaredFields.none { it.name.contains("email", ignoreCase = true) },
            "the entity must have no email field")
    }

    @Test fun `system events map to a name only, and every event gets a distinct stable id`() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val sys = AuditableEvent(AuditActor.system("SLA_MONITOR"), "SLA_BREACHED", "INCIDENT", description = "x", occurredAt = at)
        val a = AuditEventEntity.from(sys); val b = AuditEventEntity.from(sys)
        assertEquals("SYSTEM", a.actorType); assertEquals("SLA_MONITOR", a.actorName)
        assertNull(a.actorId); assertNull(a.actorRole); assertEquals(at, a.occurredAt)
        assertTrue(a.eventId != b.eventId)
    }

    @Test fun `the repository exposes no update or delete operations`() {
        val names = AuditEventRepository::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("save"), names.filter { !it.startsWith("$") }.toSet())
    }
}
