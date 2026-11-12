package com.itsm.admin.assignment

import com.itsm.common.spi.AssignmentStrategyCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** The three states of P3's [AssignmentStrategyCatalog], as reported in `strategyCatalogStatus`. */
enum class StrategyCatalogStatus { AVAILABLE, ABSENT, FAILED }

/** What asking the catalog produced. Never serialised directly: only [status] and the keys reach a response. */
sealed interface StrategyCatalogState {
    val status: StrategyCatalogStatus

    /** The catalog answered. [keys] may be empty: a catalog that registers no strategy is still AVAILABLE. */
    data class Available(val keys: Set<String>) : StrategyCatalogState {
        override val status get() = StrategyCatalogStatus.AVAILABLE
    }

    /** No catalog bean exists (P3 not deployed). */
    object Absent : StrategyCatalogState {
        override val status get() = StrategyCatalogStatus.ABSENT
    }

    /** A bean exists but threw. [cause] is for the write path to rethrow; it must never be copied into a response. */
    class Failed(val cause: Exception) : StrategyCatalogState {
        override val status get() = StrategyCatalogStatus.FAILED
    }
}

/**
 * The only place that asks P3's catalog. It is a separate bean with `NOT_SUPPORTED` on purpose.
 *
 * If P3's implementation is itself transactional (for example database backed) it JOINS the caller's transaction, and a
 * RuntimeException leaving it marks that shared transaction rollback-only; the caller then fails at COMMIT with
 * `UnexpectedRollbackException` even though it caught the exception. `NOT_SUPPORTED` suspends the caller's transaction
 * for the duration of the call, so a transactional catalog runs, and if need be rolls back, in a transaction of its own.
 * It must be a different bean from its callers: a call from within the same class would bypass the proxy and the
 * annotation would do nothing.
 *
 * Cost: while the call runs the caller's connection stays checked out, and a transactional catalog may take a second one.
 *
 * Never throws for a catalog failure (except [Error]): the failure is returned as [StrategyCatalogState.Failed].
 */
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StrategyCatalogGateway(private val catalog: ObjectProvider<AssignmentStrategyCatalog>) {

    private val log = LoggerFactory.getLogger(StrategyCatalogGateway::class.java)

    fun inspect(): StrategyCatalogState {
        val bean = catalog.getIfAvailable() ?: return StrategyCatalogState.Absent
        return try {
            StrategyCatalogState.Available(bean.registeredKeys())
        } catch (e: Exception) {
            // Class name only: the message of an infrastructure exception can carry hosts, SQL or credentials.
            log.warn("Assignment strategy catalog failed while listing its strategies ({})", e.javaClass.simpleName)
            StrategyCatalogState.Failed(e)
        }
    }
}
