package com.itsm.auth.web

import org.springframework.http.ResponseCookie
import java.time.Duration

/**
 * Everything about the browser (cookie) session in one place: the cookie, the web-client header and the Origin allow-list.
 *
 * **The cookie** carries the same JWT a Bearer client uses: `HttpOnly` (scripts cannot read it), `SameSite=Strict`, `Path=/api`
 * (never sent for pages), `Max-Age` equal to the JWT lifetime, `Secure` unless explicitly (and only for local development) turned
 * off, and no `Domain` (host-only).
 *
 * **The header** [CLIENT_HEADER] `: web` does two jobs, and is never a credential or proof of a trusted origin:
 *  - on `POST /api/auth/login` it SELECTS the response mode (cookie, no token in the body instead of `{token,user}`);
 *  - on every unsafe request authenticated by the cookie it is the CSRF header: a cross-site page cannot send a custom
 *    header without a CORS preflight, and the preflight is answered only for [allowedOrigins].
 *
 * **The Origin check** is the second, independent CSRF defence: an unsafe cookie-authenticated request (and the web login) must
 * carry an `Origin` that EXACTLY equals one entry of the allow-list (same list as CORS, `app.cors.allowed-origins`). A missing
 * Origin, `null` or anything else is refused. SameSite is a third layer, not the defence.
 */
class WebSessionPolicy(
    val cookieName: String,
    val secure: Boolean,
    private val maxAge: Duration,
    allowedOrigins: List<String>
) {
    private val origins: Set<String> = allowedOrigins.map { normalize(it) }.toSet()

    init {
        require(COOKIE_NAME.matches(cookieName)) { "app.auth.cookie.name must match [A-Za-z0-9_-]+" }
        require(!maxAge.isNegative && !maxAge.isZero) { "the session cookie lifetime must be positive" }
    }

    /** Exact match (case-insensitive scheme/host only): no trimming, no trailing slash, no prefix or wildcard matching. */
    fun originAllowed(origin: String?): Boolean = origin != null && origin.lowercase() in origins

    /** A request header value that selects web mode. Case-sensitive: exactly `web`. */
    fun isWebClientValue(value: String?): Boolean = value == CLIENT_WEB

    fun sessionCookie(jwt: String): ResponseCookie = base(jwt).maxAge(maxAge).build()

    /** Same name, path and attributes, empty value, expired: this is what makes the browser delete it. */
    fun clearingCookie(): ResponseCookie = base("").maxAge(Duration.ZERO).build()

    private fun base(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(cookieName, value).httpOnly(true).secure(secure).sameSite("Strict").path(COOKIE_PATH)

    companion object {
        const val CLIENT_HEADER = "X-ITSM-Client"
        const val CLIENT_WEB = "web"
        const val COOKIE_PATH = "/api"
        private val COOKIE_NAME = Regex("^[A-Za-z0-9_-]+$")

        private fun normalize(origin: String) = origin.trim().trimEnd('/').lowercase()

        /** Parses `app.cors.allowed-origins`; shared with the CORS configuration so the two can never disagree. */
        fun parseOrigins(raw: String): List<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .also { origins -> require("*" !in origins) { "app.cors.allowed-origins must list explicit origins, not '*'" } }
    }
}
