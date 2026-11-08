package com.itsm.common.error

import jakarta.servlet.http.HttpServletResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.MDC
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Builds the one error body shape used everywhere (RFC 7807 `application/problem+json`):
 *
 *   { type, title, status, detail, code, message, traceId, timestamp, errors? }
 *
 * `message` is a legacy alias of `detail` kept because the current frontend client
 * (frontend/src/lib/api.ts) reads `errorData.message`.
 */
object Problems {
    private val BLANK_TYPE: URI = URI.create("about:blank")

    fun of(code: ErrorCode, detail: String? = null): ProblemDetail =
        decorate(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.httpStatus), detail ?: code.title), code)

    /** Adds the standard properties to any ProblemDetail (including framework-generated ones). */
    fun decorate(problem: ProblemDetail, code: ErrorCode): ProblemDetail {
        if (problem.title == null) problem.title = code.title
        if (problem.type == BLANK_TYPE) {
            problem.type = URI.create("urn:itsm:error:" + code.name.lowercase().replace('_', '-'))
        }
        if (problem.properties?.containsKey("code") != true) problem.setProperty("code", code.name)
        problem.setProperty("message", problem.detail ?: code.title)
        problem.setProperty("traceId", currentTraceId())
        problem.setProperty("timestamp", Instant.now().toString())
        return problem
    }

    /** Deterministic map form, used by servlet-filter-level handlers that bypass MVC converters. */
    fun toMap(problem: ProblemDetail): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("type", problem.type.toString())
        put("title", problem.title)
        put("status", problem.status)
        put("detail", problem.detail)
        problem.properties?.let { putAll(it) }
    }

    fun write(response: HttpServletResponse, mapper: ObjectMapper, problem: ProblemDetail) {
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        mapper.writeValue(response.outputStream, toMap(problem))
    }

    fun currentTraceId(): String = MDC.get("traceId") ?: UUID.randomUUID().toString().replace("-", "").take(16)
}
