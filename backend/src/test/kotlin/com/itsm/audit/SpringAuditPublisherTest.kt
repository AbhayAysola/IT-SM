package com.itsm.audit

import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.spi.AuditPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList

@SpringJUnitConfig(SpringAuditPublisherTest.Config::class)
class SpringAuditPublisherTest {

    class Collector {
        val received = CopyOnWriteArrayList<AuditableEvent>()
        @EventListener fun on(e: AuditableEvent) { received += e }
    }

    /** Minimal manager so @Transactional semantics (MANDATORY) are real without a database. */
    class NoopTransactionManager : AbstractPlatformTransactionManager() {
        private val active = ThreadLocal.withInitial { false }
        override fun doGetTransaction(): Any = Any()
        override fun isExistingTransaction(transaction: Any): Boolean = active.get()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) { active.set(true) }
        override fun doCommit(status: DefaultTransactionStatus) {}
        override fun doRollback(status: DefaultTransactionStatus) {}
        override fun doCleanupAfterCompletion(transaction: Any) { active.set(false) }
    }

    @Configuration
    @EnableTransactionManagement
    class Config {
        @Bean fun tm(): PlatformTransactionManager = NoopTransactionManager()
        @Bean fun publisher(events: ApplicationEventPublisher): AuditPublisher = SpringAuditPublisher(events)
        @Bean fun collector() = Collector()
    }

    @Autowired lateinit var publisher: AuditPublisher
    @Autowired lateinit var collector: Collector
    @Autowired lateinit var tm: PlatformTransactionManager

    private val event = AuditableEvent(AuditActor.system("TEST_JOB"), "INCIDENT_CREATED", "INCIDENT", "INC1", description = "created")

    @BeforeEach fun reset() = collector.received.clear()

    @Test fun `publishing outside a transaction fails fast and emits nothing`() {
        assertThrows<IllegalTransactionStateException> { publisher.publish(event) }
        assertEquals(0, collector.received.size)
    }

    @Test fun `publishing inside a transaction reaches listeners`() {
        TransactionTemplate(tm).executeWithoutResult { publisher.publish(event) }
        assertEquals(listOf(event), collector.received.toList())
    }
}
