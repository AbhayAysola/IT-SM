package com.itsm.common.error

import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLException

/**
 * Decides how a persistence failure is reported to the client.
 *
 * Only the [ErrorCode] leaves this class; messages, SQL text, constraint and table names
 * never do. Expected, client-caused conflicts get 4xx; infrastructure failures get 503;
 * everything unrecognised is a 500 (a bug), not a 503.
 *
 * PostgreSQL SQLSTATE reference: 23505 unique, 23503 foreign key, 23502 not null,
 * 23514 check, 22xxx data exception, 08xxx connection, 53xxx resources, 57P0x shutdown.
 */
object DatabaseErrorClassifier {

    fun classify(ex: Throwable): ErrorCode {
        val sqlState = findSqlState(ex)
        return when {
            // Optimistic/pessimistic lock failures and deadlocks: retryable client conflict.
            ex is ConcurrencyFailureException -> ErrorCode.CONCURRENT_MODIFICATION

            ex is DuplicateKeyException || sqlState == "23505" -> ErrorCode.DUPLICATE_RESOURCE
            sqlState == "23503" -> ErrorCode.REFERENCE_CONSTRAINT
            sqlState == "23502" || sqlState == "23514" || sqlState?.startsWith("22") == true -> ErrorCode.INVALID_INPUT
            ex is DataIntegrityViolationException -> ErrorCode.CONFLICT   // e.g. 23P01 exclusion, unknown state

            isInfrastructureFailure(ex, sqlState) -> ErrorCode.SERVICE_UNAVAILABLE
            else -> ErrorCode.INTERNAL_ERROR
        }
    }

    private fun isInfrastructureFailure(ex: Throwable, sqlState: String?): Boolean =
        ex is DataAccessResourceFailureException ||     // incl. CannotGetJdbcConnectionException
            ex is TransientDataAccessException ||       // incl. QueryTimeoutException
            ex is CannotCreateTransactionException ||
            sqlState?.let { it.startsWith("08") || it.startsWith("53") || it.startsWith("57P0") } == true

    /** Walks the cause chain (bounded) for the first SQLSTATE. */
    fun findSqlState(ex: Throwable): String? {
        var current: Throwable? = ex
        var depth = 0
        while (current != null && depth < 10) {
            if (current is SQLException) {
                current.sqlState?.let { return it }
            }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return null
    }
}
