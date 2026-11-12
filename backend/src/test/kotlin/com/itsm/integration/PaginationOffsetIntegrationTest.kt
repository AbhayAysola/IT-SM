package com.itsm.integration

import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * B10-PAGE-001. Before the fix, `page * size` beyond Int.MAX_VALUE made Spring Data throw
 * `InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE` (observed), which surfaced as HTTP 500 on
 * users, categories, services and SLA policies. `PageQuery` now rejects such a request with the standard 400
 * `INVALID_INPUT` before any repository is reached. Real PostgreSQL 16, the production filter chain and error handler.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PaginationOffsetIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
        private val LEAK_MARKERS = listOf("Exception", "jdbc:", "org.springframework", "com.itsm", "hibernate", "stacktrace", "Integer.MAX_VALUE")
        const val USERS = "/api/admin/users"
        const val TECHNICIANS = "/api/admin/technicians"
        const val CATEGORIES = "/api/admin/categories"
        const val SERVICES = "/api/admin/services"
        const val SLA = "/api/admin/sla-policies"
        const val AUDIT = "/api/admin/audit-events"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val bearer by lazy { AdminApiTestSupport(users, jwt, jdbc).caller(Role.ADMIN).bearer }

    private fun list(path: String, page: Any, size: Any) =
        mvc.perform(get(path).param("page", "$page").param("size", "$size").header("Authorization", bearer))

    // Endpoint-specific parameterised sets: each list endpoint gets every case, so a regression names the endpoint.
    private fun assertEndpoint(path: String) {
        // normal pagination is unaffected
        list(path, 0, 20).andExpect(status().isOk).andExpect(jsonPath("$.page").value(0))
        list(path, 1, 20).andExpect(status().isOk)
        // last reachable pages (size 100, size 20, exactly Int.MAX_VALUE) are an empty 200, not an error
        list(path, 21_474_836, 100).andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
        list(path, 107_374_182, 20).andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
        list(path, Int.MAX_VALUE, 1).andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
        // the first unreachable offsets, a huge page, and the largest Int are all a clean 400
        for ((page, size) in listOf(21_474_837 to 100, 107_374_183 to 20, Int.MAX_VALUE to 2, Int.MAX_VALUE to 20, Int.MAX_VALUE to 100)) {
            val result = list(path, page, size)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("is out of range"))
                .andReturn().response.contentAsString
            LEAK_MARKERS.forEach { assertFalse(result.contains(it), "$path page=$page size=$size leaked '$it': $result") }
        }
        // existing validation is unchanged
        list(path, -1, 20).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        list(path, 0, 101).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        // outside Int the HTTP binding layer rejects it first
        list(path, "2147483648", 20).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
    }

    @Test fun `users`() = assertEndpoint(USERS)
    @Test fun `technicians`() = assertEndpoint(TECHNICIANS)
    @Test fun `categories`() = assertEndpoint(CATEGORIES)
    @Test fun `services`() = assertEndpoint(SERVICES)
    @Test fun `sla policies`() = assertEndpoint(SLA)
    @Test fun `audit events keep their behaviour after the guard moved into PageQuery`() = assertEndpoint(AUDIT)

    @ParameterizedTest(name = "no endpoint answers 5xx for page={0}")
    @ValueSource(strings = ["21474837", "2147483647", "2147483648", "9223372036854775807", "9223372036854775808", "-2147483648", "abc", "1.5"])
    fun `no list endpoint answers 5xx for any page value`(page: String) {
        for (path in listOf(USERS, TECHNICIANS, CATEGORIES, SERVICES, SLA, AUDIT)) {
            val code = list(path, page, 100).andReturn().response.status
            assertTrue(code in 400..499, "$path page=$page answered $code")
        }
    }
}
