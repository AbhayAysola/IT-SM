package com.itsm.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.auth.AuthService
import com.itsm.auth.JwtAuthFilter
import com.itsm.auth.JwtService
import com.itsm.auth.dto.LoginRequest
import com.itsm.common.error.AuthenticationFailed
import com.itsm.common.error.ErrorCode
import com.itsm.common.model.Role
import com.itsm.common.security.ItsmUserDetails
import com.itsm.common.security.JsonAccessDeniedHandler
import com.itsm.common.security.JsonAuthenticationEntryPoint
import com.itsm.common.spi.AuditPublisher
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import java.util.Optional

/** Unit level (no DB, no HTTP): the auth classes work together with [ItsmUserDetails], the `active` flag and token versions. */
class ExistingAuthFlowCompatibilityTest {

    private val repo: UserRepository = mock(UserRepository::class.java)
    private val config = SecurityConfig(
        mock(JwtAuthFilter::class.java), repo,
        JsonAuthenticationEntryPoint(ObjectMapper()), JsonAccessDeniedHandler(ObjectMapper())
    )
    private val encoder = config.passwordEncoder()
    private val jwt = JwtService("test-secret-test-secret-test-secret-123456", 3_600_000)
    private val uds: UserDetailsService = config.userDetailsService()
    private val authManager = ProviderManager(config.authenticationProvider())
    private val service = AuthService(repo, encoder, jwt, authManager, mock(AuditPublisher::class.java))

    private lateinit var emp: UserEntity
    private lateinit var tech: UserEntity

    @BeforeEach fun seed() {
        emp = UserEntity(id = 7, email = "emp@x.com", password = encoder.encode("password1"), name = "Emp", role = "EMPLOYEE")
        tech = UserEntity(id = 8, email = "tech@x.com", password = encoder.encode("password1"), name = "Tech", role = "TECHNICIAN", tokenVersion = 3)
        `when`(repo.findByEmail("emp@x.com")).thenReturn(emp)
        `when`(repo.findByEmail("tech@x.com")).thenReturn(tech)
        `when`(repo.findById(7L)).thenReturn(Optional.of(emp))
        `when`(repo.findById(8L)).thenReturn(Optional.of(tech))
    }

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    private fun filterWith(token: String): MockHttpServletResponse {
        SecurityContextHolder.clearContext()
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
        val response = MockHttpServletResponse()
        JwtAuthFilter(jwt, repo, ObjectMapper()).doFilter(request, response, MockFilterChain())
        return response
    }

    private fun authenticated() = SecurityContextHolder.getContext().authentication

    @Test fun `user details carry id, canonical role, active flag and token version`() {
        val d = uds.loadUserByUsername(" TECH@x.com ") as ItsmUserDetails   // normalised before lookup
        assertEquals(8L, d.id); assertEquals(Role.TECHNICIAN, d.role); assertEquals(3, d.tokenVersion); assertTrue(d.isEnabled)
        assertTrue("INCIDENT_WORK" in d.authorities.map { it.authority } && "USER_MANAGE" !in d.authorities.map { it.authority })
    }

    @Test fun `an inactive user is not enabled`() {
        emp.active = false
        assertFalse(uds.loadUserByUsername("emp@x.com").isEnabled)
    }

    @Test fun `unknown user and corrupt role both surface as UsernameNotFoundException`() {
        assertThrows<UsernameNotFoundException> { uds.loadUserByUsername("nobody@x.com") }
        emp.role = "WIZARD"
        assertThrows<UsernameNotFoundException> { uds.loadUserByUsername("emp@x.com") }
    }

    @Test fun `login issues a token that authenticates through the filter`() {
        val response = service.login(LoginRequest("Emp@X.com", "password1"))
        assertEquals("EMPLOYEE", response.user.role)
        filterWith(response.token)
        assertEquals("emp@x.com", authenticated()!!.name)
        assertInstanceOf(ItsmUserDetails::class.java, authenticated()!!.principal)
    }

    @Test fun `wrong password, unknown user and inactive account all give the same invalid credentials error`() {
        emp.active = false
        for (req in listOf(LoginRequest("emp@x.com", "password1"), LoginRequest("tech@x.com", "wrong"), LoginRequest("none@x.com", "password1"))) {
            val ex = assertThrows<AuthenticationFailed> { service.login(req) }
            assertEquals(ErrorCode.INVALID_CREDENTIALS, ex.code)
        }
    }

    @Test fun `filter rejects stale token version, inactive user, deleted user, corrupt role and garbage without throwing`() {
        filterWith(jwt.generateToken(8, 2)); assertNull(authenticated())

        val valid = jwt.generateToken(8, 3)
        tech.active = false; filterWith(valid); assertNull(authenticated())
        tech.active = true

        tech.role = "WIZARD"; filterWith(valid); assertNull(authenticated())
        tech.role = "TECHNICIAN"

        filterWith(jwt.generateToken(999, 0)); assertNull(authenticated())        // deleted / unknown user
        filterWith("not.a.jwt"); assertNull(authenticated())

        filterWith(valid); assertEquals("tech@x.com", authenticated()!!.name)      // sanity: the valid one works
    }

    @Test fun `invalid tokens continue the chain unauthenticated so the entry point answers 401`() {
        val chain = MockFilterChain()
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer not.a.jwt") }
        JwtAuthFilter(jwt, repo, ObjectMapper()).doFilter(request, MockHttpServletResponse(), chain)
        assertNull(authenticated()); assertTrue(chain.request != null, "chain must continue for an invalid token")
    }

    @Test fun `a database failure is 503, never authenticates, never continues and leaks nothing`() {
        `when`(repo.findById(8L)).thenThrow(org.springframework.dao.DataAccessResourceFailureException("jdbc:postgresql://secret-host:5432 password=hunter2"))
        val chain = MockFilterChain()
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer ${jwt.generateToken(8, 3)}") }
        val response = MockHttpServletResponse()
        JwtAuthFilter(jwt, repo, ObjectMapper()).doFilter(request, response, chain)
        assertNull(authenticated()); assertNull(chain.request, "request must not reach the controller")
        assertEquals(503, response.status); assertEquals("5", response.getHeader("Retry-After"))
        val body = response.contentAsString
        assertTrue(body.contains("SERVICE_UNAVAILABLE"))
        assertFalse(body.contains("secret-host") || body.contains("hunter2") || body.contains("jdbc") || body.contains("Exception"))
    }

    @Test fun `a transaction failure is 503 and an unexpected failure is a generic 500, both fail closed`() {
        val token = jwt.generateToken(8, 3)
        for ((failure, status) in listOf<Pair<RuntimeException, Int>>(
            org.springframework.transaction.CannotCreateTransactionException("pool exhausted") to 503,
            RuntimeException("boom: internal detail") to 500
        )) {
            org.mockito.Mockito.doThrow(failure).`when`(repo).findById(8L)
            val chain = MockFilterChain(); val response = MockHttpServletResponse()
            val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
            JwtAuthFilter(jwt, repo, ObjectMapper()).doFilter(request, response, chain)
            assertNull(authenticated()); assertNull(chain.request); assertEquals(status, response.status)
            assertFalse(response.contentAsString.contains("internal detail") || response.contentAsString.contains("pool exhausted"))
        }
    }
}
