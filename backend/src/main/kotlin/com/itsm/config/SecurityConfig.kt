package com.itsm.config

import com.itsm.auth.JwtAuthFilter
import com.itsm.auth.ratelimit.RegisterRateLimitFilter
import com.itsm.auth.web.OriginSyntaxFilter
import com.itsm.auth.web.WebSessionPolicy
import com.itsm.common.model.Role
import com.itsm.common.security.ItsmUserDetails
import com.itsm.common.security.JsonAccessDeniedHandler
import com.itsm.common.security.JsonAuthenticationEntryPoint
import com.itsm.common.security.Permission
import com.itsm.user.EmailNormalizer
import com.itsm.user.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.filter.CorsFilter
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    // No @Lazy: JwtAuthFilter depends on UserRepository, never on beans of this class, so there is no cycle. A @Lazy
    // parameter would inject a CGLIB proxy of the filter (and log "Unable to proxy ... final" warnings).
    private val jwtAuthFilter: JwtAuthFilter,
    private val registerRateLimitFilter: RegisterRateLimitFilter,
    private val originSyntaxFilter: OriginSyntaxFilter,
    private val userRepository: UserRepository,
    private val authenticationEntryPoint: JsonAuthenticationEntryPoint,
    private val accessDeniedHandler: JsonAccessDeniedHandler
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** Comma-separated browser origins allowed to call the API (`app.cors.allowed-origins` / `CORS_ALLOWED_ORIGINS`). */
    @Value("\${app.cors.allowed-origins:http://localhost:3000}")
    private var allowedOrigins: String = "http://localhost:3000"

    /** Used by the login `AuthenticationManager`. `isEnabled` reflects `users.active`, so inactive accounts cannot log in. */
    @Bean
    fun userDetailsService(): UserDetailsService = UserDetailsService { email ->
        val user = userRepository.findByEmail(EmailNormalizer.normalize(email))
            ?: throw UsernameNotFoundException("User not found")
        val role = try { Role.fromStored(user.role) } catch (e: IllegalStateException) {
            throw UsernameNotFoundException("User not found")   // corrupt role: unauthenticated, never a 500
        }
        ItsmUserDetails(user.id, user.email, user.password, role, user.active, user.tokenVersion)
    }

    @Bean
    fun authenticationProvider(): AuthenticationProvider = DaoAuthenticationProvider().apply {
        setUserDetailsService(userDetailsService())
        setPasswordEncoder(passwordEncoder())
    }

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager =
        config.authenticationManager

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = WebSessionPolicy.parseOrigins(allowedOrigins)
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type", "Accept", WebSessionPolicy.CLIENT_HEADER)
        config.maxAge = 3600
        config.allowCredentials = true
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            // Spring's built-in CSRF is off: there is no server session, and cookie authentication is protected by JwtAuthFilter
            // itself (X-ITSM-Client header + exact Origin allow-list on every unsafe cookie-authenticated request, and on the web
            // login). Bearer-only clients are not subject to it. See docs/architecture/security.md.
            .csrf { it.disable() }
            .authorizeHttpRequests {
                // Only liveness/readiness and build info are public (health details are ADMIN-only, see application.yml).
                it.requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                it.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                it.requestMatchers("/actuator/**").hasRole("ADMIN")
                // Admin APIs are authorised HERE, in the filter chain, in addition to each controller's @PreAuthorize.
                // @PreAuthorize runs after request-body binding, so on its own an authenticated non-admin would get a 400
                // with validation detail instead of a 403. The rules below mirror the controllers' permissions; the
                // controller annotations stay as the second layer. A new /api/admin path that is not listed falls to the
                // ADMIN-only rule (fail closed).
                it.requestMatchers("/api/admin/users/**", "/api/admin/technicians/**").hasAuthority(Permission.USER_MANAGE.name)
                it.requestMatchers("/api/admin/categories/**", "/api/admin/services/**").hasAuthority(Permission.CATALOG_MANAGE.name)
                it.requestMatchers("/api/admin/sla-policies/**").hasAuthority(Permission.SLA_CONFIGURE.name)
                // P4: when SLA escalation fires (docs/architecture/p4.md section 3). Same permission as the SLA policies.
                it.requestMatchers("/api/admin/escalation-settings/**").hasAuthority(Permission.SLA_CONFIGURE.name)
                it.requestMatchers("/api/admin/assignment-settings/**").hasAuthority(Permission.ASSIGNMENT_CONFIGURE.name)
                it.requestMatchers("/api/admin/audit-events/**").hasAuthority(Permission.AUDIT_VIEW.name)
                // P4: the analytics dashboard (docs/architecture/p4.md section 5).
                it.requestMatchers("/api/admin/analytics/**").hasAuthority(Permission.ANALYTICS_VIEW.name)
                it.requestMatchers("/api/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            // Charged before authentication and before the body is read.
            .addFilterBefore(registerRateLimitFilter, JwtAuthFilter::class.java)
            // First of all: a malformed Origin must be refused before Spring's CORS code parses it (that parse throws).
            .addFilterBefore(originSyntaxFilter, CorsFilter::class.java)
        return http.build()
    }
}
