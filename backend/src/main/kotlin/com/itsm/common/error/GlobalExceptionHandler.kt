package com.itsm.common.error

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Single place where exceptions become HTTP responses. Extends Spring's
 * [ResponseEntityExceptionHandler] so framework errors (bad JSON, 405, 415, missing params...)
 * also come out as problem+json with our `code`/`traceId` fields.
 *
 * Because there is a catch-all here, Spring Security's AccessDeniedException and
 * AuthenticationException (thrown by @PreAuthorize / AuthenticationManager inside controllers)
 * are handled explicitly below; otherwise they would be swallowed into a 500.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ItsmException::class)
    fun handleItsm(ex: ItsmException): ResponseEntity<ProblemDetail> {
        val problem = Problems.of(ex.code, ex.message)
        if (ex.fieldErrors.isNotEmpty()) problem.setProperty("errors", ex.fieldErrors)
        if (ex.code.httpStatus >= 500) log.error("traceId={} {}", problem.properties?.get("traceId"), ex.code, ex)
        else log.debug("traceId={} {}: {}", problem.properties?.get("traceId"), ex.code, ex.message)
        return respond(problem)
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException): ResponseEntity<ProblemDetail> =
        when (ex) {
            is BadCredentialsException, is UsernameNotFoundException ->
                respond(Problems.of(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"))
            else -> respond(Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication is required"))
        }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ProblemDetail> {
        // Mirrors Spring Security's own rule: anonymous -> 401, authenticated-but-denied -> 403.
        val auth = SecurityContextHolder.getContext().authentication
        return if (auth == null || auth is AnonymousAuthenticationToken) {
            respond(Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication is required"))
        } else {
            respond(Problems.of(ErrorCode.FORBIDDEN, "You are not allowed to perform this operation"))
        }
    }

    /** jakarta.validation failures outside @RequestBody binding (e.g. entity or parameter validation). */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ProblemDetail> {
        val problem = Problems.of(ErrorCode.VALIDATION_FAILED, "Request validation failed")
        problem.setProperty(
            "errors",
            ex.constraintViolations.map { FieldError(it.propertyPath.toString().substringAfterLast('.'), it.message) }
        )
        return respond(problem)
    }

    @ExceptionHandler(DataAccessException::class)
    fun handleDataAccess(ex: DataAccessException): ResponseEntity<ProblemDetail> = respondPersistence(ex)

    @ExceptionHandler(TransactionException::class)
    fun handleTransaction(ex: TransactionException): ResponseEntity<ProblemDetail> = respondPersistence(ex)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        val problem = Problems.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred")
        log.error("traceId={} unhandled exception", problem.properties?.get("traceId"), ex)
        return respond(problem)
    }

    // ---- framework (Spring MVC) errors ----------------------------------------------------

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val problem = Problems.of(ErrorCode.VALIDATION_FAILED, "Request validation failed")
        val errors = ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage ?: "invalid value") } +
            ex.bindingResult.globalErrors.map { FieldError(it.objectName, it.defaultMessage ?: "invalid value") }
        problem.setProperty("errors", errors)
        return ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    /** Adds `code` / `traceId` / `message` / `timestamp` to every framework-generated ProblemDetail. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        // Spring builds the ProblemDetail inside super when `body` is null (ErrorResponse exceptions),
        // so decorate the body of the RESULT, not the argument.
        val response = super.handleExceptionInternal(ex, body, headers, statusCode, request)
        (response?.body as? ProblemDetail)?.let { Problems.decorate(it, ErrorCode.fromStatus(statusCode.value())) }
        return response
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun respondPersistence(ex: Exception): ResponseEntity<ProblemDetail> {
        val code = DatabaseErrorClassifier.classify(ex)
        // Client-safe, fixed wording per code. Nothing from the exception message is exposed.
        val detail = when (code) {
            ErrorCode.DUPLICATE_RESOURCE -> "A resource with the same unique value already exists"
            ErrorCode.REFERENCE_CONSTRAINT -> "The resource is referenced by other data or refers to data that does not exist"
            ErrorCode.CONCURRENT_MODIFICATION -> "The resource was modified concurrently; please retry"
            ErrorCode.INVALID_INPUT -> "The request contains data that cannot be stored"
            ErrorCode.CONFLICT -> "The request conflicts with the current state of the data"
            ErrorCode.SERVICE_UNAVAILABLE -> "The service is temporarily unavailable; please retry shortly"
            else -> "An unexpected error occurred"
        }
        val problem = Problems.of(code, detail)
        val traceId = problem.properties?.get("traceId")
        if (code.httpStatus >= 500) {
            log.error("traceId={} persistence failure sqlState={}", traceId, DatabaseErrorClassifier.findSqlState(ex), ex)
        } else {
            log.warn("traceId={} persistence conflict {} sqlState={}", traceId, code, DatabaseErrorClassifier.findSqlState(ex))
        }
        val builder = ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
        if (code == ErrorCode.SERVICE_UNAVAILABLE) builder.header(HttpHeaders.RETRY_AFTER, "5")
        return builder.body(problem)
    }

    private fun respond(problem: ProblemDetail): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
}
