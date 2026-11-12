package com.itsm.audit

import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AuditPublisher
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Publishes the event on Spring's in-process event bus (Observer pattern).
 *
 * `MANDATORY` makes calling this outside a transaction fail fast, so an audit event can never
 * be emitted for work that is not transactional. [AuditEventPersistenceListener] receives the event in the
 * BEFORE_COMMIT phase and inserts it into `audit_events` inside that same transaction, so the audit row commits
 * or rolls back with the business change.
 */
@Component
class SpringAuditPublisher(private val events: ApplicationEventPublisher) : AuditPublisher {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(event: AuditableEvent) {
        events.publishEvent(event)
    }
}
