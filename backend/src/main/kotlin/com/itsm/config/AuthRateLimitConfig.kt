package com.itsm.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.auth.ratelimit.AuthAttemptLimiter
import com.itsm.auth.ratelimit.AuthRateLimitSettings
import com.itsm.auth.ratelimit.ClientIpResolver
import com.itsm.auth.ratelimit.LoggingThrottleEvents
import com.itsm.auth.ratelimit.RegisterRateLimitFilter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/** Wires the authentication throttles. Every number is a property so operators (and tests) can tune it; see docs/architecture/security.md. */
@Configuration
class AuthRateLimitConfig {

    @Bean
    fun authRateLimitSettings(
        @Value("\${app.rate-limit.enabled:true}") enabled: Boolean,
        @Value("\${app.rate-limit.login-pair.max-failures:5}") pairMax: Int,
        @Value("\${app.rate-limit.login-pair.window:15m}") pairWindow: String,
        @Value("\${app.rate-limit.login-ip.max-failures:40}") ipMax: Int,
        @Value("\${app.rate-limit.login-ip.window:15m}") ipWindow: String,
        @Value("\${app.rate-limit.register-ip.max-attempts:10}") registerMax: Int,
        @Value("\${app.rate-limit.register-ip.window:1h}") registerWindow: String,
        @Value("\${app.rate-limit.max-keys:50000}") maxKeys: Int
    ) = AuthRateLimitSettings(
        enabled, pairMax, parseWindow("app.rate-limit.login-pair.window", pairWindow),
        ipMax, parseWindow("app.rate-limit.login-ip.window", ipWindow),
        registerMax, parseWindow("app.rate-limit.register-ip.window", registerWindow), maxKeys
    )

    @Bean
    fun clientIpResolver(
        @Value("\${app.security.trusted-proxy-hops:0}") hops: Int,
        @Value("\${app.security.trusted-proxies:}") proxies: String
    ) = ClientIpResolver(hops, proxies.split(',').map { it.trim() }.filter { it.isNotEmpty() })

    @Bean
    fun authAttemptLimiter(settings: AuthRateLimitSettings, meters: ObjectProvider<MeterRegistry>) =
        AuthAttemptLimiter(settings, Clock.systemUTC(), LoggingThrottleEvents(meters.getIfAvailable()))

    @Bean
    fun registerRateLimitFilter(limiter: AuthAttemptLimiter, ips: ClientIpResolver, mapper: ObjectMapper) =
        RegisterRateLimitFilter(limiter, ips, mapper)

    /** The filter runs inside the security chain (see SecurityConfig); stop Spring Boot also registering it a second time. */
    @Bean
    fun registerRateLimitFilterRegistration(filter: RegisterRateLimitFilter): FilterRegistrationBean<RegisterRateLimitFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    private companion object {
        /** `15m`, `90s`, `2h` or ISO-8601 (`PT15M`). Parsed here (not by Spring's converter) so it also works in a plain Spring context. */
        fun parseWindow(property: String, text: String): Duration {
            val t = text.trim()
            val simple = Regex("""^(\d+)(s|m|h)$""").matchEntire(t)
            val d = try {
                if (simple != null) {
                    val n = simple.groupValues[1].toLong()
                    when (simple.groupValues[2]) { "s" -> Duration.ofSeconds(n); "m" -> Duration.ofMinutes(n); else -> Duration.ofHours(n) }
                } else Duration.parse(t)
            } catch (e: Exception) {
                throw IllegalArgumentException("$property must look like 15m, 90s, 2h or PT15M, not '$text'")
            }
            require(!d.isZero && !d.isNegative) { "$property must be positive" }
            return d
        }
    }
}
