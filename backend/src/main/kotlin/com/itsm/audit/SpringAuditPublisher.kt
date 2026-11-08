package com.itsm.audit

import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AuditPublisher
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Publishes the event on Spring's in-process event bus (Observer pattern).
 *
 * `MANDATORY` makes calling this outside a transaction fail fast, so an audit event can never
 * be emitted for work that is not transactional. The persisting listener
 * (`@TransactionalEventListener(BEFORE_COMMIT)`) is added in the audit branch; until then
 * published events are NOT stored anywhere.
 */
@Component
class SpringAuditPublisher(private val events: ApplicationEventPublisher) : AuditPublisher {

    init {
        // Make the limitation visible at runtime, not only in docs. Remove with the audit branch.
        LoggerFactory.getLogger(SpringAuditPublisher::class.java)
            .warn("Audit persistence is not implemented yet: AuditableEvents are published but NOT stored.")
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(event: AuditableEvent) {
        events.publishEvent(event)
    }
}
