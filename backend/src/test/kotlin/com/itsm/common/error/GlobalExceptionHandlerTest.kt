package com.itsm.common.error

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.model.Priority
import jakarta.validation.ConstraintViolationException
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.MediaType
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import jakarta.validation.Valid

class GlobalExceptionHandlerTest {

    data class Case(val kind: String, val ex: () -> Throwable, val status: Int, val code: String) {
        override fun toString() = kind
    }

    companion object {
        private const val SECRET = "hunter2"
        private fun sql(state: String) = SQLException("relation secret_table violates $SECRET", state)

        val caseList = listOf(
            Case("notFound", { ResourceNotFound("Incident", "INC1") }, 404, "RESOURCE_NOT_FOUND"),
            Case("serviceNotFound", { ServiceNotFound(5) }, 404, "SERVICE_NOT_FOUND"),
            Case("forbidden", { UnauthorizedOperation() }, 403, "FORBIDDEN"),
            Case("invalidCredentials", { AuthenticationFailed.invalidCredentials() }, 401, "INVALID_CREDENTIALS"),
            Case("duplicate", { DuplicateResource("Email already registered") }, 409, "DUPLICATE_RESOURCE"),
            Case("transition", { InvalidStateTransition("Incident", "NEW", "CLOSED") }, 409, "INVALID_STATE_TRANSITION"),
            Case("missingSla", { MissingSlaConfiguration(1, Priority.P1) }, 422, "MISSING_SLA_CONFIG"),
            Case("techUnavailable", { TechnicianUnavailable(3) }, 409, "TECHNICIAN_UNAVAILABLE"),
            Case("workload", { WorkloadExceeded(3, 5, 5) }, 409, "WORKLOAD_EXCEEDED"),
            Case("external", { ExternalServiceFailure("smtp", RuntimeException(SECRET)) }, 502, "EXTERNAL_SERVICE_FAILURE"),
            Case("springBadCredentials", { BadCredentialsException(SECRET) }, 401, "INVALID_CREDENTIALS"),
            Case("constraintViolation", { ConstraintViolationException(SECRET, emptySet()) }, 400, "VALIDATION_FAILED"),

            // persistence: expected conflicts are NOT 503
            Case("duplicateKey", { DuplicateKeyException("insert into users password=$SECRET") }, 409, "DUPLICATE_RESOURCE"),
            Case("uniqueSqlState", { DataIntegrityViolationException("x", sql("23505")) }, 409, "DUPLICATE_RESOURCE"),
            Case("foreignKey", { DataIntegrityViolationException("x", sql("23503")) }, 409, "REFERENCE_CONSTRAINT"),
            Case("notNull", { DataIntegrityViolationException("x", sql("23502")) }, 400, "INVALID_INPUT"),
            Case("checkViolation", { DataIntegrityViolationException("x", sql("23514")) }, 400, "INVALID_INPUT"),
            Case("valueTooLong", { DataIntegrityViolationException("x", sql("22001")) }, 400, "INVALID_INPUT"),
            Case("unknownIntegrity", { DataIntegrityViolationException("x $SECRET") }, 409, "CONFLICT"),
            Case("optimisticLock", { OptimisticLockingFailureException("version $SECRET") }, 409, "CONCURRENT_MODIFICATION"),
            Case("deadlock", { CannotAcquireLockException("deadlock $SECRET") }, 409, "CONCURRENT_MODIFICATION"),

            // persistence: infrastructure is 503, bugs are 500
            Case("noConnection", { CannotGetJdbcConnectionException("jdbc:postgresql://db:5432/itsm $SECRET", sql("08001")) }, 503, "SERVICE_UNAVAILABLE"),
            Case("queryTimeout", { QueryTimeoutException("select pg_sleep $SECRET") }, 503, "SERVICE_UNAVAILABLE"),
            Case("adminShutdown", { UncategorizedSQLException("task", "select $SECRET", sql("57P01")) }, 503, "SERVICE_UNAVAILABLE"),
            Case("cannotOpenTx", { CannotCreateTransactionException("jdbc:postgresql://db $SECRET") }, 503, "SERVICE_UNAVAILABLE"),
            Case("badGrammar", { BadSqlGrammarException("task", "select $SECRET from t", sql("42601")) }, 500, "INTERNAL_ERROR"),
            Case("unexpected", { IllegalStateException("password=$SECRET") }, 500, "INTERNAL_ERROR")
        )

        @JvmStatic
        fun cases(): List<Case> = caseList
    }

    data class Body(@field:NotBlank val name: String? = null)

    @RestController
    class ThrowingController {
        @GetMapping("/throw")
        fun throwIt(@RequestParam kind: String): String =
            throw caseList.first { it.kind == kind }.ex()

        @PostMapping("/validate", consumes = ["application/json"])
        fun validate(@Valid @RequestBody body: Body): String = "ok"
    }

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val mapper = Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
        mvc = MockMvcBuilders.standaloneSetup(ThrowingController())
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .build()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `maps exception to status and code without leaking internals`(case: Case) {
        val result = mvc.perform(get("/throw").param("kind", case.kind))
            .andExpect(status().`is`(case.status))
            .andExpect(jsonPath("$.code").value(case.code))
            .andExpect(jsonPath("$.status").value(case.status))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
            .andExpect(jsonPath("$.timestamp").isNotEmpty)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.notNullValue()))
            .andReturn()
        assertTrue(result.response.contentType!!.startsWith("application/problem+json"))
        val body = result.response.contentAsString
        for (forbidden in listOf(SECRET, "secret_table", "select ", "jdbc:", "Exception", "violates")) {
            assertFalse(body.contains(forbidden, ignoreCase = true), "response leaked '$forbidden': $body")
        }
    }

    @Test fun `503 carries Retry-After, 409 does not`() {
        mvc.perform(get("/throw").param("kind", "noConnection")).andExpect(header().string("Retry-After", "5"))
        mvc.perform(get("/throw").param("kind", "duplicateKey")).andExpect(header().doesNotExist("Retry-After"))
    }

    @Test fun `bean validation failure lists offending fields`() {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("""{"name":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[0].field").value("name"))
    }

    @Test fun `malformed JSON is a 400 with our error shape`() {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
    }

    @Test fun `wrong HTTP method is 405`() {
        mvc.perform(get("/validate"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
    }

    @Test fun `unsupported media type is 415`() {
        mvc.perform(post("/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test fun `missing request parameter is a 400`() {
        mvc.perform(get("/throw"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
    }
}
