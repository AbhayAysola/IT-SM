package com.itsm.auth.ratelimit

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Turns an identifier (an email address) into a short opaque token for log lines, so throttle events can be correlated
 * without writing the address. HMAC-SHA-256 with a random per-process key: the token cannot be reversed by hashing a list
 * of candidate addresses, and it changes on restart (and differs between replicas) by design.
 */
class PrivacyHasher(key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {
    private val spec = SecretKeySpec(key, "HmacSHA256")

    fun token(value: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(spec) }
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}
