package com.itsm.auth.ratelimit

import com.itsm.common.error.RateLimited
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/** Where throttle events go. The default writes a structured log line and a counter; tests substitute a recorder. */
interface ThrottleEvents {
    /** [scope] is for operators only and is never sent to the client. [emailToken] is a [PrivacyHasher] token, never an address. */
    fun throttled(scope: String, clientIp: String, emailToken: String?, retryAfterSeconds: Long)
}

class LoggingThrottleEvents(private val meters: MeterRegistry?) : ThrottleEvents {
    private val log = LoggerFactory.getLogger("com.itsm.security.throttle")

    override fun throttled(scope: String, clientIp: String, emailToken: String?, retryAfterSeconds: Long) {
        log.warn("event=auth.throttled scope={} clientIp={} emailToken={} retryAfterSeconds={}", scope, clientIp, emailToken ?: "-", retryAfterSeconds)
        meters?.counter("itsm.auth.throttled", "scope", scope)?.increment()
    }
}

data class AuthRateLimitSettings(
    val enabled: Boolean = true,
    val loginPairMaxFailures: Int = 5,
    val loginPairWindow: Duration = Duration.ofMinutes(15),
    val loginIpMaxFailures: Int = 40,
    val loginIpWindow: Duration = Duration.ofMinutes(15),
    val registerIpMaxAttempts: Int = 10,
    val registerIpWindow: Duration = Duration.ofHours(1),
    val maxKeys: Int = 50_000
)

/** Returned by [AuthAttemptLimiter.beforeLogin]; pass it back to [AuthAttemptLimiter.loginSucceeded] or [AuthAttemptLimiter.loginNotCounted]. */
class LoginTicket internal constructor(internal val ipKey: String, internal val pairKey: String, internal val counted: Boolean)

/**
 * Authentication throttles. Approved limits (defaults of [AuthRateLimitSettings]):
 *  - login, per (client IP + normalised email): 5 failed logins / 15 min;
 *  - login, per client IP: 40 failed logins / 15 min;
 *  - registration, per client IP: 10 attempts / hour.
 * There is deliberately NO email-only lockout: it would let anyone lock a victim out from anywhere.
 *
 * Login attempts are counted UP FRONT, atomically, before any password work, and refunded when they turn out fine. That
 * makes the limit hold under concurrency (parallel guesses cannot all slip past a check-then-count race) and means a lockout
 * refuses even the CORRECT password. A success clears only that IP+email pair counter. Keys use the normalised email whether or
 * not an account exists, so known and unknown accounts are throttled identically.
 *
 * State is in memory and PER INSTANCE: it is lost on restart and not shared between replicas (N replicas allow up to N times
 * the limits). It is a brake on online guessing from one place, not a global quota; put an ingress limit in front as well.
 */
class AuthAttemptLimiter(
    private val settings: AuthRateLimitSettings,
    clock: Clock,
    private val events: ThrottleEvents,
    private val hasher: PrivacyHasher = PrivacyHasher()
) {
    private val loginIp = BoundedWindowCounter(settings.loginIpMaxFailures, settings.loginIpWindow, settings.maxKeys, clock)
    private val loginPair = BoundedWindowCounter(settings.loginPairMaxFailures, settings.loginPairWindow, settings.maxKeys, clock)
    private val registerIp = BoundedWindowCounter(settings.registerIpMaxAttempts, settings.registerIpWindow, settings.maxKeys, clock)

    /** Throws [RateLimited] when this attempt must be refused; otherwise counts it. [email] must already be normalised. */
    fun beforeLogin(clientIp: String, email: String): LoginTicket {
        if (!settings.enabled) return LoginTicket(clientIp, "", false)
        val pairKey = "$clientIp|$email"
        // IP first: a single address can then create at most `loginIpMaxFailures` pair entries per window.
        loginIp.hit(clientIp).let { if (it > 0) refuse("login-ip", clientIp, email, it) }
        loginPair.hit(pairKey).let {
            if (it > 0) { loginIp.refund(clientIp); refuse("login-pair", clientIp, email, it) }
        }
        return LoginTicket(clientIp, pairKey, true)
    }

    /** The login succeeded: clear this pair's failures and take the attempt back from the IP counter. */
    fun loginSucceeded(ticket: LoginTicket) {
        if (!ticket.counted) return
        loginPair.reset(ticket.pairKey)
        loginIp.refund(ticket.ipKey)
    }

    /** The attempt failed for a reason that is not a credential failure (e.g. the database is down): do not count it. */
    fun loginNotCounted(ticket: LoginTicket) {
        if (!ticket.counted) return
        loginPair.refund(ticket.pairKey)
        loginIp.refund(ticket.ipKey)
    }

    /** Throws [RateLimited] once the address has used up its registration attempts. Every attempt counts, valid or not. */
    fun beforeRegister(clientIp: String) {
        if (!settings.enabled) return
        registerIp.hit(clientIp).let { if (it > 0) refuse("register-ip", clientIp, null, it) }
    }

    private fun refuse(scope: String, clientIp: String, email: String?, retryAfter: Long): Nothing {
        events.throttled(scope, clientIp, email?.let { hasher.token(it) }, retryAfter)
        throw RateLimited(retryAfter)
    }
}
