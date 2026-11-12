package com.itsm.auth.ratelimit

import com.itsm.common.error.RateLimited
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class AuthAttemptLimiterTest {

    private class Recorder : ThrottleEvents {
        data class E(val scope: String, val ip: String, val emailToken: String?, val retry: Long)
        val events = mutableListOf<E>()
        override fun throttled(scope: String, clientIp: String, emailToken: String?, retryAfterSeconds: Long) {
            events += E(scope, clientIp, emailToken, retryAfterSeconds)
        }
    }

    private val clock = MutableClock()
    private val events = Recorder()
    private fun limiter(settings: AuthRateLimitSettings = AuthRateLimitSettings()) =
        AuthAttemptLimiter(settings, clock, events, PrivacyHasher(ByteArray(32) { 7 }))

    private fun fail(l: AuthAttemptLimiter, ip: String, email: String) { l.beforeLogin(ip, email) }   // counted, never succeeds

    @Test fun `the sixth failed login for one address and email is refused, the fifth is not`() {
        val l = limiter()
        repeat(5) { fail(l, "1.1.1.1", "a@x.com") }
        val e = assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "a@x.com") }
        assertEquals(900, e.retryAfterSeconds)
    }

    @Test fun `the limit applies to the pair, so the same email from another address and another email from this address are unaffected`() {
        val l = limiter()
        repeat(5) { fail(l, "1.1.1.1", "a@x.com") }
        l.beforeLogin("2.2.2.2", "a@x.com")                      // a legitimate user elsewhere is not locked out
        l.beforeLogin("1.1.1.1", "b@x.com")                      // nor another account from the same address
        assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "a@x.com") }
    }

    @Test fun `the per-address limit of 40 catches credential stuffing across many emails`() {
        val l = limiter()
        repeat(40) { fail(l, "9.9.9.9", "user$it@x.com") }       // 40 different accounts, one failure each
        assertThrows<RateLimited> { l.beforeLogin("9.9.9.9", "user41@x.com") }
        l.beforeLogin("8.8.8.8", "user41@x.com")                 // another address is fine
        assertEquals("login-ip", events.events.last().scope)
    }

    @Test fun `a success clears only that pair's failures and gives the attempt back to the address counter`() {
        val l = limiter()
        repeat(4) { fail(l, "1.1.1.1", "a@x.com") }
        repeat(3) { fail(l, "1.1.1.1", "b@x.com") }
        l.loginSucceeded(l.beforeLogin("1.1.1.1", "a@x.com").also { /* the 5th attempt of a@ succeeds */ })
        repeat(5) { fail(l, "1.1.1.1", "a@x.com") }              // a@ has a fresh budget of five
        assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "a@x.com") }
        // b@ was not cleared by a@'s success: it still has its three failures on record (2 more allowed)
        fail(l, "1.1.1.1", "b@x.com"); fail(l, "1.1.1.1", "b@x.com")
        assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "b@x.com") }
    }

    @Test fun `an attempt that fails for a non-credential reason is not counted`() {
        val l = limiter()
        repeat(10) { l.loginNotCounted(l.beforeLogin("1.1.1.1", "a@x.com")) }
        repeat(5) { fail(l, "1.1.1.1", "a@x.com") }
        assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "a@x.com") }
    }

    @Test fun `when the pair is blocked the attempt is not charged to the address counter either`() {
        val l = limiter(AuthRateLimitSettings(loginPairMaxFailures = 1, loginIpMaxFailures = 3))
        fail(l, "1.1.1.1", "a@x.com")
        repeat(20) { assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "a@x.com") } }
        l.beforeLogin("1.1.1.1", "b@x.com"); l.beforeLogin("1.1.1.1", "c@x.com")      // 3 address hits so far: a, b, c
    }

    @Test fun `the lockout ends when the window ends`() {
        val l = limiter()
        repeat(5) { fail(l, "1.1.1.1", "a@x.com") }
        assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "a@x.com") }
        clock.advance(Duration.ofMinutes(15))
        l.beforeLogin("1.1.1.1", "a@x.com")
    }

    @Test fun `registration allows ten attempts per address per hour and refuses the eleventh`() {
        val l = limiter()
        repeat(10) { l.beforeRegister("1.1.1.1") }
        val e = assertThrows<RateLimited> { l.beforeRegister("1.1.1.1") }
        assertEquals(3600, e.retryAfterSeconds)
        l.beforeRegister("2.2.2.2")
        clock.advance(Duration.ofHours(1))
        l.beforeRegister("1.1.1.1")
    }

    @Test fun `disabling the limiter allows everything`() {
        val l = limiter(AuthRateLimitSettings(enabled = false))
        repeat(500) { l.beforeLogin("1.1.1.1", "a@x.com"); l.beforeRegister("1.1.1.1") }
    }

    @Test fun `throttle events carry a hashed email token, never the address, and never a password`() {
        val l = limiter()
        repeat(5) { fail(l, "1.1.1.1", "secret.person@campus.example") }
        assertThrows<RateLimited> { l.beforeLogin("1.1.1.1", "secret.person@campus.example") }
        val e = events.events.single()
        assertEquals("login-pair", e.scope)
        assertEquals("1.1.1.1", e.ip)
        assertTrue(e.emailToken!!.matches(Regex("[0-9a-f]{16}")))
        assertFalse(e.toString().contains("secret.person"), e.toString())
        repeat(10) { l.beforeRegister("3.3.3.3") }
        assertThrows<RateLimited> { l.beforeRegister("3.3.3.3") }
        assertEquals(null, events.events.last().emailToken, "registration has no email at this point")
    }

    @Test fun `the privacy token is stable per key, differs per key and per process key`() {
        val a = PrivacyHasher(ByteArray(32) { 1 }); val b = PrivacyHasher(ByteArray(32) { 2 })
        assertEquals(a.token("x@y.z"), a.token("x@y.z"))
        assertFalse(a.token("x@y.z") == a.token("x@y.w"))
        assertFalse(a.token("x@y.z") == b.token("x@y.z"), "an attacker cannot precompute tokens without the process key")
    }
}
