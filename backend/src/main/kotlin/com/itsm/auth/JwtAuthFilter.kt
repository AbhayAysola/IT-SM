package com.itsm.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.auth.web.WebSessionPolicy
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.Problems
import com.itsm.common.model.Role
import com.itsm.common.security.ItsmUserDetails
import com.itsm.user.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.transaction.TransactionException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates a request against the CURRENT database state, from a Bearer token or from the browser session cookie.
 *
 * **Precedence (deterministic):**
 *  1. If an `Authorization` header is present, ONLY it is considered: a `Bearer` credential is validated, anything else (Basic, a
 *     malformed value) leaves the request unauthenticated. An invalid Bearer credential never falls back to a valid cookie.
 *  2. Only when there is no `Authorization` header is the session cookie ([WebSessionPolicy.cookieName]) tried.
 *
 * **CSRF for the cookie:** a request authenticated BY THE COOKIE that uses an unsafe method (anything but GET, HEAD, OPTIONS) must
 * carry `X-ITSM-Client: web` and an `Origin` that exactly matches the configured allow-list; otherwise it is refused with a
 * generic 403 and nothing runs. Bearer-authenticated requests are not subject to it (a browser never attaches a Bearer header on
 * its own, so they cannot be forged cross-site).
 *
 *  - A token or account that is not acceptable (bad/expired token, deleted user, inactive account, corrupt role, stale token
 *    version) leaves the request unauthenticated, so protected endpoints answer 401 through the entry point.
 *  - If the user cannot be checked at all (database down, unexpected failure) the request is NOT authenticated and is NOT
 *    passed on: it is answered 503 (data access / transaction failure) or 500 (anything else) with a generic problem body.
 *    An outage must never look like "your token is invalid", and must never let a request through unchecked.
 *
 * Exception messages, SQL and stack traces are logged server-side only (class name at WARN) and never reach the response.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
    private val mapper: ObjectMapper,
    private val web: WebSessionPolicy
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (SecurityContextHolder.getContext().authentication == null) {
            val header = request.getHeader("Authorization")
            val viaCookie = header == null
            val token = if (header != null) header.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim() else cookieToken(request)
            if (token != null) {
                try {
                    if (authenticate(token) && viaCookie) {
                        val rejected = csrfViolation(request)
                        if (rejected != null) {
                            SecurityContextHolder.clearContext()
                            log.warn("Cookie-authenticated {} request refused: {}", request.method, rejected)
                            Problems.write(response, mapper, Problems.of(ErrorCode.FORBIDDEN, REJECTED))
                            return
                        }
                    }
                } catch (e: Exception) {
                    SecurityContextHolder.clearContext()
                    val unavailable = e is DataAccessException || e is TransactionException
                    log.warn("Authentication could not be completed ({}): {}", if (unavailable) "unavailable" else "error", e.javaClass.simpleName)
                    val code = if (unavailable) ErrorCode.SERVICE_UNAVAILABLE else ErrorCode.INTERNAL_ERROR
                    if (unavailable) response.setHeader(HttpHeaders.RETRY_AFTER, "5")
                    Problems.write(
                        response, mapper,
                        Problems.of(code, if (unavailable) "The service is temporarily unavailable; please retry shortly" else "An unexpected error occurred")
                    )
                    return
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun cookieToken(request: HttpServletRequest): String? =
        request.cookies?.firstOrNull { it.name == web.cookieName }?.value?.takeIf { it.isNotBlank() }

    /** Why an unsafe cookie-authenticated request must be refused, or null if it is fine. The reason is for the log only. */
    private fun csrfViolation(request: HttpServletRequest): String? {
        if (request.method in SAFE_METHODS) return null
        if (!web.isWebClientValue(request.getHeader(WebSessionPolicy.CLIENT_HEADER))) return "missing or wrong ${WebSessionPolicy.CLIENT_HEADER} header"
        val origin = request.getHeader("Origin") ?: return "missing Origin"
        if (!web.originAllowed(origin)) return "Origin not allowed"
        return null
    }

    /** True when the request is now authenticated. */
    private fun authenticate(token: String): Boolean {
        val claims = jwtService.parse(token) ?: return false
        val user = userRepository.findById(claims.userId).orElse(null) ?: return false
        if (!user.active || user.tokenVersion != claims.tokenVersion) return false
        val role = try { Role.fromStored(user.role) } catch (e: IllegalStateException) { return false }
        val principal = ItsmUserDetails(user.id, user.email, user.password, role, user.active, user.tokenVersion)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        return true
    }

    companion object {
        /** The generic text of every CSRF refusal: it never says which check failed. */
        const val REJECTED = "Request rejected"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
