package com.itsm.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.GlobalExceptionHandler
import com.itsm.common.model.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/** Verifies the real filter-chain + @PreAuthorize + GlobalExceptionHandler working together. */
@SpringJUnitWebConfig(MethodSecurityTest.Config::class)
class MethodSecurityTest {

    @RestController
    class SecuredController {
        @GetMapping("/t/manage-users")
        @PreAuthorize("hasAuthority('USER_MANAGE')")
        fun manageUsers() = "ok"

        @GetMapping("/t/technician")
        @PreAuthorize("hasRole('TECHNICIAN')")
        fun technician() = "ok"

        @GetMapping("/t/open-but-protected")
        @PreAuthorize("isAuthenticated()")
        fun openButProtected() = "ok"

        @GetMapping("/t/url-admin")
        fun urlAdmin() = "ok"
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    class Config {
        @Bean fun mapper(): ObjectMapper = Jackson2ObjectMapperBuilder.json().build()
        @Bean fun entryPoint(m: ObjectMapper) = JsonAuthenticationEntryPoint(m)
        @Bean fun denied(m: ObjectMapper) = JsonAccessDeniedHandler(m)
        @Bean fun advice() = GlobalExceptionHandler()
        @Bean fun controller() = SecuredController()

        @Bean
        fun chain(http: HttpSecurity, ep: JsonAuthenticationEntryPoint, dh: JsonAccessDeniedHandler): SecurityFilterChain {
            http.csrf { it.disable() }
                .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
                .authorizeHttpRequests {
                    it.requestMatchers("/t/open-but-protected").permitAll()   // forces the method-level check to decide
                    it.requestMatchers("/t/url-admin").hasRole("ADMIN")
                    it.anyRequest().authenticated()
                }
                .exceptionHandling { it.authenticationEntryPoint(ep); it.accessDeniedHandler(dh) }
            return http.build()
        }
    }

    @Autowired lateinit var context: WebApplicationContext
    private lateinit var mvc: MockMvc

    @BeforeEach fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun asRole(role: Role) = ItsmUserDetails(1, "u@x.com", "hash", role).let {
        authentication(UsernamePasswordAuthenticationToken(it, null, it.authorities))
    }

    @Test fun `unauthenticated request gets JSON 401 from the filter chain`() {
        mvc.perform(get("/t/manage-users"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test fun `employee is denied a USER_MANAGE method with JSON 403`() {
        mvc.perform(get("/t/manage-users").with(asRole(Role.EMPLOYEE)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
    }

    @Test fun `admin may call a USER_MANAGE method`() {
        mvc.perform(get("/t/manage-users").with(asRole(Role.ADMIN)))
            .andExpect(status().isOk).andExpect(content().string("ok"))
    }

    @Test fun `hasRole works with the ROLE_ authority`() {
        mvc.perform(get("/t/technician").with(asRole(Role.TECHNICIAN))).andExpect(status().isOk)
        mvc.perform(get("/t/technician").with(asRole(Role.EMPLOYEE))).andExpect(status().isForbidden)
    }

    @Test fun `anonymous caller denied by a method rule is 401 not 403`() {
        mvc.perform(get("/t/open-but-protected"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test fun `URL-level denial also returns the JSON body`() {
        mvc.perform(get("/t/url-admin").with(asRole(Role.EMPLOYEE)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
    }
}
