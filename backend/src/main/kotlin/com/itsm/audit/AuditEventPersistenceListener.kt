package com.itsm.audit

import com.itsm.common.event.AuditableEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Writes every published [AuditableEvent] to `audit_events`.
 *
 * Phase is BEFORE_COMMIT: the insert runs inside the business transaction, just before it commits. Consequences:
 *  - rollback of the business transaction rolls the audit row back with it (there is nothing to undo);
 *  - if the insert fails, the exception propagates out of the commit and the business change is rolled back too.
 *    That is intentional: an unaudited change must not become permanent. Nothing here catches or logs-and-continues;
 *  - no REQUIRES_NEW and no AFTER_COMMIT, either of which could keep a row for an action that never happened, or
 *    lose one for an action that did.
 *
 * `fallbackExecution` stays false: with no transaction an event is not persisted. That cannot happen through
 * [SpringAuditPublisher], whose MANDATORY propagation refuses to publish outside a transaction.
 * The method is not itself `@Transactional` (Spring forbids that on a transactional listener unless it is
 * REQUIRES_NEW, which we must not use). `repository.save` is REQUIRED, so it joins the transaction that is still
 * open in the BEFORE_COMMIT phase and never starts its own.
 */
@Component
class AuditEventPersistenceListener(private val repository: AuditEventRepository) {

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun persist(event: AuditableEvent) {
        repository.save(AuditEventEntity.from(event))
    }
}
