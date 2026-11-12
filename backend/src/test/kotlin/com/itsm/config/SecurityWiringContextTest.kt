package com.itsm.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.auth.JwtAuthFilter
import com.itsm.auth.JwtService
import com.itsm.auth.web.OriginSyntaxFilter
import com.itsm.common.security.JsonAccessDeniedHandler
import com.itsm.common.security.JsonAuthenticationEntryPoint
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * Regression guard for the pre-existing JwtAuthFilter <-> SecurityConfig constructor cycle
 * (see docs/architecture/foundation.md section 8). Wires the REAL security beans, no database,
 * no Docker. The filter must depend only on UserRepository/JwtService/ObjectMapper (not on beans declared in SecurityConfig), otherwise this
 * context fails to load with a cycle, which is what the former `@Lazy` workaround hid.
 */
@SpringJUnitWebConfig(SecurityWiringContextTest.Config::class)
@TestPropertySource(properties = ["app.jwt.secret=test-secret-test-secret-test-secret-123456", "app.jwt.expiration=60000"])
class SecurityWiringContextTest {

    @Configuration
    @EnableWebMvc   // HttpSecurity.requestMatchers needs the MVC HandlerMappingIntrospector
    @Import(SecurityConfig::class, AuthRateLimitConfig::class, WebSessionConfig::class, OriginSyntaxFilter::class, JwtAuthFilter::class, JwtService::class,
        JsonAuthenticationEntryPoint::class, JsonAccessDeniedHandler::class)
    class Config {
        @Bean fun objectMapper() = ObjectMapper()
        @Bean fun userRepository(): UserRepository = mock(UserRepository::class.java)
    }

    @Autowired lateinit var chain: SecurityFilterChain
    @Autowired lateinit var filter: JwtAuthFilter

    @Test fun `security beans wire without a dependency cycle`() {
        assertNotNull(chain)
        assertNotNull(filter)
    }
}
