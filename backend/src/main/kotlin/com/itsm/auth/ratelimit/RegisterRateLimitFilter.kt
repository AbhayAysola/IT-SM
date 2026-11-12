package com.itsm.auth.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.Problems
import com.itsm.common.error.RateLimited
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Charges every `POST /api/auth/register` to the caller's address BEFORE the body is read, so invalid and valid attempts
 * both count and a refused attempt costs almost nothing. It runs ahead of authentication in the security filter chain.
 */
class RegisterRateLimitFilter(
    private val limiter: AuthAttemptLimiter,
    private val clientIps: ClientIpResolver,
    private val mapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !(request.method == "POST" && request.requestURI.trimEnd('/') == REGISTER_PATH)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            limiter.beforeRegister(clientIps.resolve(request))
        } catch (e: RateLimited) {
            response.setHeader(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            Problems.write(response, mapper, Problems.of(ErrorCode.RATE_LIMITED, e.message))
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val REGISTER_PATH = "/api/auth/register"
    }
}
