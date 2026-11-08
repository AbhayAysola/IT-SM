package com.itsm.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.auth.AuthService
import com.itsm.auth.JwtAuthFilter
import com.itsm.auth.JwtService
import com.itsm.auth.dto.LoginRequest
import com.itsm.auth.dto.RegisterRequest
import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.ErrorCode
import com.itsm.common.model.Role
import com.itsm.common.security.ItsmUserDetails
import com.itsm.common.security.JsonAccessDeniedHandler
import com.itsm.common.security.JsonAuthenticationEntryPoint
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import com.itsm.user.UserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException

/**
 * Proves the existing, out-of-scope auth classes (AuthService, JwtService, JwtAuthFilter, UserService)
 * still work, unmodified, with the new ItsmUserDetails principal. Unit level: no DB, no HTTP server.
 */
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

    @BeforeEach fun seed() {
        // `role = "USER"` is exactly what the boilerplate V1 migration defaults to.
        `when`(repo.findByEmail("emp@x.com"))
            .thenReturn(UserEntity(id = 7, email = "emp@x.com", password = encoder.encode("pw"), name = "Emp", role = "USER"))
        `when`(repo.findByEmail("tech@x.com"))
            .thenReturn(UserEntity(id = 8, email = "tech@x.com", password = encoder.encode("pw"), name = "Tech", role = "TECHNICIAN"))
    }

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @Test fun `legacy USER row loads as an EMPLOYEE principal with email as username`() {
        val d = uds.loadUserByUsername("emp@x.com")
        assertInstanceOf(ItsmUserDetails::class.java, d)
        assertEquals("emp@x.com", d.username)
        val names = d.authorities.map { it.authority }
        assertTrue("ROLE_EMPLOYEE" in names && "INCIDENT_CREATE" in names && "USER_MANAGE" !in names)
        assertEquals(7L, (d as ItsmUserDetails).id)
    }

    @Test fun `unknown user still raises UsernameNotFoundException`() {
        assertThrows<UsernameNotFoundException> { uds.loadUserByUsername("nobody@x.com") }
    }

    @Test fun `AuthService login still works and issues a token keyed by email`() {
        val service = AuthService(repo, encoder, jwt, authManager)
        val response = service.login(LoginRequest("emp@x.com", "pw"))
        assertEquals("emp@x.com", jwt.extractEmail(response.token))
        assertEquals("emp@x.com", response.user.email)
    }

    @Test fun `wrong password still fails authentication`() {
        val service = AuthService(repo, encoder, jwt, authManager)
        assertThrows<BadCredentialsException> { service.login(LoginRequest("emp@x.com", "wrong")) }
    }

    @Test fun `unmodified JwtAuthFilter authenticates with ItsmUserDetails and UserService still resolves the user`() {
        val token = jwt.generateToken("tech@x.com")
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
        JwtAuthFilter(jwt, uds).doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val auth = SecurityContextHolder.getContext().authentication!!
        assertInstanceOf(ItsmUserDetails::class.java, auth.principal)
        assertEquals("tech@x.com", auth.name)                         // UserService relies on this
        assertTrue(auth.authorities.any { it.authority == Role.TECHNICIAN.authority })
        assertEquals("tech@x.com", UserService(repo).getCurrentUser().email)
    }

    @Test fun `registering an existing email raises DuplicateResource and saves nothing`() {
        val service = AuthService(repo, encoder, jwt, authManager)
        val ex = assertThrows<DuplicateResource> { service.register(RegisterRequest("emp@x.com", "pw", "Again")) }
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, ex.code)
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any(UserEntity::class.java))
    }
}
