package com.itsm.auth

import com.itsm.auth.dto.AuthResponse
import com.itsm.auth.dto.LoginRequest
import com.itsm.auth.dto.LogoutResponse
import com.itsm.auth.dto.RegisterRequest
import com.itsm.auth.dto.RegistrationResponse
import com.itsm.auth.dto.WebLoginResponse
import com.itsm.auth.ratelimit.ClientIpResolver
import com.itsm.auth.web.WebSessionPolicy
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.security.ItsmUserDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val clientIps: ClientIpResolver,
    private val web: WebSessionPolicy
) {

    /** 202 with the same body for a new and an already-registered address; never a token. See [AuthService.register]. */
    @PostMapping("/register")
    fun register(@Valid @RequestBody req: RegisterRequest): ResponseEntity<RegistrationResponse> =
        ResponseEntity.accepted().body(authService.register(req))

    /**
     * Two response modes, selected ONLY by the `X-ITSM-Client` request header (it is not a credential):
     *  - absent: `{token, user}` for Bearer clients (scripts, services, tests); no cookie is set;
     *  - `web`: browser mode. The JWT is set as the HttpOnly session cookie and is NOT in the body (`{user}`).
     * Any other value is a 400 and nothing is authenticated. Web mode is also login-CSRF protected: it needs an `Origin` from the
     * allow-list (checked before any credential or throttle work), and the body must be JSON (`consumes`).
     */
    @PostMapping("/login", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun login(
        @Valid @RequestBody req: LoginRequest,
        http: HttpServletRequest,
        @RequestHeader(name = WebSessionPolicy.CLIENT_HEADER, required = false) client: String?
    ): ResponseEntity<Any> {
        if (client != null && !web.isWebClientValue(client)) {
            throw InvalidInput("Invalid client mode", listOf(FieldError(WebSessionPolicy.CLIENT_HEADER, "must be 'web' when present")))
        }
        if (client != null && !web.originAllowed(http.getHeader("Origin"))) {
            throw ItsmException(ErrorCode.FORBIDDEN, JwtAuthFilter.REJECTED)
        }
        val auth: AuthResponse = authService.login(req, clientIps.resolve(http))
        if (client == null) return ResponseEntity.ok(auth)
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, web.sessionCookie(auth.token).toString()).body(WebLoginResponse(auth.user))
    }

    /**
     * Authenticated. Bumps the caller's token version, which invalidates EVERY token and session of this user (all browsers, all
     * Bearer clients), not only the one making this call, and clears the session cookie. Cookie-authenticated calls are CSRF
     * protected by [JwtAuthFilter].
     */
    @PostMapping("/logout")
    fun logout(): ResponseEntity<LogoutResponse> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? ItsmUserDetails
            ?: throw com.itsm.common.error.AuthenticationFailed()
        authService.logout(principal.id)
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, web.clearingCookie().toString()).body(LogoutResponse())
    }
}
