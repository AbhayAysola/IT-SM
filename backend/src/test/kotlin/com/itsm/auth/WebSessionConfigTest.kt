package com.itsm.auth

import com.itsm.auth.web.WebSessionPolicy
import com.itsm.config.WebSessionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

/** The Secure flag of the session cookie: on by default, and `false` only for the dev/local profiles, never for production. */
class WebSessionConfigTest {

    private fun policy(secure: Boolean, vararg profiles: String, origins: String = "http://localhost:3000"): WebSessionPolicy {
        val env = MockEnvironment().apply { setActiveProfiles(*profiles) }
        return WebSessionConfig().webSessionPolicy(env, "itsm_session", secure, 3_600_000, origins)
    }

    @Test fun `production-like configuration with Secure disabled refuses to start`() {
        for (profiles in listOf(emptyArray(), arrayOf("prod"), arrayOf("production"), arrayOf("docker"), arrayOf("docker", "staging"), arrayOf("test"))) {
            val e = assertThrows<IllegalStateException>("profiles ${profiles.toList()}") { policy(false, *profiles) }
            assertTrue("dev" in e.message!! && "Secure" in e.message!!, e.message)
        }
    }

    @Test fun `Secure is the default and works with any profile`() {
        assertTrue(policy(true).sessionCookie("x").isSecure)
        assertTrue(policy(true, "prod").sessionCookie("x").isSecure)
        assertTrue(policy(true, "dev").sessionCookie("x").isSecure)
    }

    @Test fun `the dev and local profiles may explicitly turn Secure off, and only Secure changes`() {
        for (profile in listOf("dev", "local")) {
            val c = policy(false, profile).sessionCookie("jwt")
            assertFalse(c.isSecure)
            assertTrue(c.isHttpOnly); assertEquals("Strict", c.sameSite); assertEquals("/api", c.path); assertEquals(Duration.ofHours(1), c.maxAge)
            val cleared = policy(false, profile).clearingCookie()
            assertFalse(cleared.isSecure); assertEquals("/api", cleared.path); assertEquals(Duration.ZERO, cleared.maxAge); assertEquals("", cleared.value)
        }
        // dev next to production profiles is still explicit opt-in by the operator
        assertFalse(policy(false, "docker", "dev").sessionCookie("x").isSecure)
    }

    @Test fun `the origin allow-list is an exact match, never a wildcard or prefix`() {
        val p = policy(true, origins = "http://localhost:3000, https://app.example.com/ ")
        assertTrue(p.originAllowed("http://localhost:3000")); assertTrue(p.originAllowed("HTTPS://APP.EXAMPLE.COM"))
        for (bad in listOf(null, "", "null", "*", "http://localhost", "http://localhost:30001", "http://localhost:3000/", "http://localhost:3000.evil.com",
            "https://app.example.com.evil.com", "http://app.example.com", "https://sub.app.example.com", " http://localhost:3000")) assertFalse(p.originAllowed(bad), "'$bad'")
        assertThrows<IllegalArgumentException> { WebSessionPolicy.parseOrigins("http://a.example,*") }
    }

    @Test fun `the cookie name is restricted and the web header value is exact`() {
        assertThrows<IllegalArgumentException> { WebSessionPolicy("bad name;", true, Duration.ofHours(1), emptyList()) }
        val p = policy(true)
        assertTrue(p.isWebClientValue("web"))
        for (bad in listOf(null, "", "WEB", "Web", "web ", " web", "webx")) assertFalse(p.isWebClientValue(bad), "'$bad'")
    }
}
