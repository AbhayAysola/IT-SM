package com.itsm.common.error

import com.itsm.common.model.Priority

/** One invalid field in a request. */
data class FieldError(val field: String, val message: String)

/**
 * Base of all business/domain errors. Throw a subclass from any module; the
 * [GlobalExceptionHandler] turns it into an RFC 7807 response.
 *
 * SECURITY: [message] is returned to the client verbatim. It must be client-safe:
 * no SQL, stack traces, file paths, internal ids of other tenants, or secrets.
 * The [cause] is logged server-side only and never serialized.
 */
open class ItsmException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    open val fieldErrors: List<FieldError> get() = emptyList()
}

/** 401. Use [invalidCredentials] for failed logins (never reveal which part was wrong). */
class AuthenticationFailed(
    message: String = "Authentication is required",
    code: ErrorCode = ErrorCode.UNAUTHENTICATED
) : ItsmException(code, message) {
    companion object {
        fun invalidCredentials() = AuthenticationFailed("Invalid email or password", ErrorCode.INVALID_CREDENTIALS)
    }
}

/**
 * 429. A rate limit was exceeded. The message is deliberately generic: it never says WHICH limit (per account, per address,
 * registration) was hit. [retryAfterSeconds] becomes the `Retry-After` header.
 */
class RateLimited(val retryAfterSeconds: Long) : ItsmException(ErrorCode.RATE_LIMITED, "Too many attempts. Please try again later.")

/** 403. The caller is authenticated but not allowed to do this. */
class UnauthorizedOperation(
    message: String = "You are not allowed to perform this operation"
) : ItsmException(ErrorCode.FORBIDDEN, message)

/** 404. */
open class ResourceNotFound(
    val resourceType: String,
    val resourceId: Any?,
    code: ErrorCode = ErrorCode.RESOURCE_NOT_FOUND
) : ItsmException(code, "$resourceType '${resourceId.toString().take(50)}' was not found")

/** 404 with the dedicated `SERVICE_NOT_FOUND` code (IT service). */
class ServiceNotFound(serviceId: Any?) :
    ResourceNotFound("IT service", serviceId, ErrorCode.SERVICE_NOT_FOUND)

/** 409. A uniqueness rule was violated (duplicate email, category code, ...). */
class DuplicateResource(message: String) : ItsmException(ErrorCode.DUPLICATE_RESOURCE, message)

/** 400. Business-level input validation failure (bean validation failures use VALIDATION_FAILED). */
class InvalidInput(
    message: String,
    override val fieldErrors: List<FieldError> = emptyList()
) : ItsmException(ErrorCode.INVALID_INPUT, message)

/** 409. States are plain strings so this module needs no knowledge of P3's state enum. */
class InvalidStateTransition(entityType: String, from: String, to: String) :
    ItsmException(ErrorCode.INVALID_STATE_TRANSITION, "$entityType cannot move from $from to $to")

/** 422. No SLA policy covers this service + priority. Callers should degrade gracefully. */
class MissingSlaConfiguration(serviceId: Long?, priority: Priority) :
    ItsmException(
        ErrorCode.MISSING_SLA_CONFIG,
        "No SLA policy is configured for ${if (serviceId == null) "the default scope" else "service $serviceId"} at priority $priority"
    )

/** 409. */
class TechnicianUnavailable(technicianId: Long) :
    ItsmException(ErrorCode.TECHNICIAN_UNAVAILABLE, "Technician $technicianId is not available")

/** 409. */
class WorkloadExceeded(technicianId: Long, current: Int, max: Int) :
    ItsmException(ErrorCode.WORKLOAD_EXCEEDED, "Technician $technicianId is at $current of $max allowed active incidents")

/** 502. An adapter to an external system (email, SMS, Slack, ...) failed. */
class ExternalServiceFailure(system: String, cause: Throwable? = null) :
    ItsmException(ErrorCode.EXTERNAL_SERVICE_FAILURE, "The external service '$system' is currently failing", cause)
