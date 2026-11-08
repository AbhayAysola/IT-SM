package com.itsm.common.error

/** Stable, machine-readable error identifiers returned in the `code` field of every error body. */
enum class ErrorCode(val httpStatus: Int, val title: String) {
    UNAUTHENTICATED(401, "Authentication required"),
    INVALID_CREDENTIALS(401, "Invalid credentials"),
    FORBIDDEN(403, "Operation not permitted"),
    VALIDATION_FAILED(400, "Validation failed"),
    INVALID_INPUT(400, "Invalid input"),
    MALFORMED_REQUEST(400, "Malformed request"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    SERVICE_NOT_FOUND(404, "IT service not found"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
    DUPLICATE_RESOURCE(409, "Duplicate resource"),
    CONFLICT(409, "Conflict"),
    CONCURRENT_MODIFICATION(409, "Concurrent modification"),
    REFERENCE_CONSTRAINT(409, "Resource is referenced elsewhere or references a missing resource"),
    INVALID_STATE_TRANSITION(409, "Invalid state transition"),
    TECHNICIAN_UNAVAILABLE(409, "Technician unavailable"),
    WORKLOAD_EXCEEDED(409, "Technician workload exceeded"),
    MISSING_SLA_CONFIG(422, "SLA configuration missing"),
    EXTERNAL_SERVICE_FAILURE(502, "External service failure"),
    SERVICE_UNAVAILABLE(503, "Service temporarily unavailable"),
    INTERNAL_ERROR(500, "Internal error");

    companion object {
        /** Fallback mapping for framework-generated errors that only carry an HTTP status. */
        fun fromStatus(status: Int): ErrorCode = when (status) {
            401 -> UNAUTHENTICATED
            403 -> FORBIDDEN
            404 -> RESOURCE_NOT_FOUND
            405 -> METHOD_NOT_ALLOWED
            409 -> CONFLICT
            415 -> UNSUPPORTED_MEDIA_TYPE
            503 -> SERVICE_UNAVAILABLE
            in 400..499 -> MALFORMED_REQUEST
            else -> INTERNAL_ERROR
        }
    }
}
