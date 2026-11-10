package com.itsm.auth

import com.fasterxml.jackson.databind.ObjectMapper
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
 * Authenticates a Bearer token against the CURRENT database state.
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
    private val mapper: ObjectMapper
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().authentication == null) {
            try {
                authenticate(header.substring(7).trim())
            } catch (e: Exception) {
                SecurityContextHolder.clearContext()
                val unavailable = e is DataAccessException || e is TransactionException
                log.warn("Bearer authentication could not be completed ({}): {}", if (unavailable) "unavailable" else "error", e.javaClass.simpleName)
                val code = if (unavailable) ErrorCode.SERVICE_UNAVAILABLE else ErrorCode.INTERNAL_ERROR
                if (unavailable) response.setHeader(HttpHeaders.RETRY_AFTER, "5")
                Problems.write(
                    response, mapper,
                    Problems.of(code, if (unavailable) "The service is temporarily unavailable; please retry shortly" else "An unexpected error occurred")
                )
                return
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(token: String) {
        val claims = jwtService.parse(token) ?: return
        val user = userRepository.findById(claims.userId).orElse(null) ?: return
        if (!user.active || user.tokenVersion != claims.tokenVersion) return
        val role = try { Role.fromStored(user.role) } catch (e: IllegalStateException) { return }
        val principal = ItsmUserDetails(user.id, user.email, user.password, role, user.active, user.tokenVersion)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }
}
