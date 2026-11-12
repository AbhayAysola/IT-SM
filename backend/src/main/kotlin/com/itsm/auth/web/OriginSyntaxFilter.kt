package com.itsm.auth.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.Problems
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Rejects a syntactically invalid `Origin` header with a generic 400 BEFORE anything parses it.
 *
 * **Why:** Spring's CORS code (`CorsUtils.isCorsRequest`, used by the Security `CorsFilter` and again by MVC's handler mapping)
 * parses the header with `UriComponentsBuilder.fromOriginHeader` and lets the parser's exception escape for values such as
 * `http://localhost:3000.evil.example`, `http://[::1` or `http://`: an uncaught exception, i.e. a 500, on a header any client can
 * send. This filter runs first in the security chain, so neither Spring's CORS processing, the CSRF check, authentication nor a
 * controller ever sees such a request.
 *
 * **What is valid:** no Origin header, the literal `null` (a real value browsers send; it is simply never on the allow-list), or
 * exactly `scheme://host[:port]` with an http/https scheme (any letter case), a DNS-name/IPv4/bracketed-IPv6 host, a port of 1-65535,
 * and no path, query, userinfo, whitespace or second value. One Origin header only. This check is syntax only: whether a
 * well-formed origin is ALLOWED is still decided solely by the allow-list (`app.cors.allowed-origins`) in CORS and in
 * [WebSessionPolicy.originAllowed].
 *
 * The response is the same generic problem for every malformed value; the value, the parser's message and the allow-list are
 * never echoed (the class of failure is logged, the value is not).
 */
@Component
class OriginSyntaxFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(OriginSyntaxFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val values = request.getHeaders("Origin").toList()
        if (values.isNotEmpty() && (values.size > 1 || !wellFormed(values[0]))) {
            log.warn("{} {} refused: malformed Origin header", request.method, request.requestURI.take(80))
            Problems.write(response, mapper, Problems.of(ErrorCode.VALIDATION_FAILED, MESSAGE))
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val MESSAGE = "Invalid Origin header"
        private val ORIGIN = Regex(
            "^https?://" +
                "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*" +
                "|\\[[0-9A-Fa-f:.]{2,45}])" +
                "(?::[0-9]{1,5})?$",
            RegexOption.IGNORE_CASE
        )

        fun wellFormed(value: String): Boolean {
            if (value == "null") return true
            if (value.length > 300 || !ORIGIN.matches(value)) return false
            val port = value.substringAfterLast(':', "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) && !value.endsWith("]") }
            return port == null || port.toInt() in 1..65535
        }
    }
}
