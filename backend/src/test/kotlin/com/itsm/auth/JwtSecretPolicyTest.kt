package com.itsm.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The signing secret is mandatory and strong; the service refuses to be constructed (= application refuses to start) otherwise. */
class JwtSecretPolicyTest {

    private val good = "k8s-Pr0d-signing-secret-with-plenty-of-entropy-9f3a7c1e"

    @Test fun `missing, blank, short and placeholder secrets fail fast without echoing the secret`() {
        for (bad in listOf("", "   ", "short-secret", "x".repeat(31), "a".repeat(64), "change-me-in-production-minimum-32-chars-required",
            "your-256-bit-secret-key-replace-in-production-minimum-32-chars", "ChangeMe-ChangeMe-ChangeMe-ChangeMe-1234")) {
            val ex = assertThrows<IllegalStateException>("secret '$bad' must be rejected") { JwtService(bad, 60_000) }
            assertFalse(bad.isNotBlank() && ex.message!!.contains(bad), "the message must not echo the secret")
        }
    }

    @Test fun `a strong secret works and the expiration must be positive`() {
        assertNotNull(JwtService(good, 60_000))
        assertThrows<IllegalStateException> { JwtService(good, 0) }
    }

    @Test fun `tokens carry user id and version, and tampering or another key is rejected`() {
        val jwt = JwtService(good, 60_000)
        val token = jwt.generateToken(42, 7)
        assertEquals(TokenClaims(42, 7), jwt.parse(token))
        assertNull(jwt.parse(token.dropLast(2) + "xx"))
        assertNull(JwtService("another-strong-secret-with-enough-entropy-0987654321", 60_000).parse(token))
        assertNull(jwt.parse(""))
    }
}
