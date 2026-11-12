package com.itsm.config

import com.itsm.auth.web.WebSessionPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.time.Duration

/**
 * Wires the browser session cookie. `Secure` is ON unless `app.auth.cookie.secure=false`, which is accepted ONLY when the
 * `dev` or `local` Spring profile is active; in any other configuration (production has neither) the application refuses to
 * start, so an insecure cookie can never be applied to a real deployment by accident or by a stray environment variable.
 */
@Configuration
class WebSessionConfig {

    @Bean
    fun webSessionPolicy(
        env: Environment,
        @Value("\${app.auth.cookie.name:itsm_session}") name: String,
        @Value("\${app.auth.cookie.secure:true}") secure: Boolean,
        @Value("\${app.jwt.expiration:86400000}") jwtExpirationMs: Long,
        @Value("\${app.cors.allowed-origins:http://localhost:3000}") allowedOrigins: String
    ): WebSessionPolicy {
        check(secure || env.acceptsProfiles(Profiles.of("dev", "local"))) {
            "app.auth.cookie.secure=false (AUTH_COOKIE_SECURE=false) is only allowed with the 'dev' or 'local' Spring profile. " +
                "Production must serve the app over HTTPS and keep the session cookie Secure."
        }
        return WebSessionPolicy(name, secure, Duration.ofMillis(jwtExpirationMs), WebSessionPolicy.parseOrigins(allowedOrigins))
    }
}
