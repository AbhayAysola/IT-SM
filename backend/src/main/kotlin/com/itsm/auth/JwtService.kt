package com.itsm.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

/** What a verified token asserts. The role is deliberately NOT a claim: it is always read from the database. */
data class TokenClaims(val userId: Long, val tokenVersion: Int)

/** Fails application startup when the signing secret is missing, short or a known placeholder. */
object JwtSecretPolicy {
    const val MIN_BYTES = 32
    private val PLACEHOLDERS = listOf("change-me", "changeme", "replace-in-production", "your-256-bit", "your-secret", "placeholder")

    fun validate(secret: String?): String {
        val value = secret?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("JWT_SECRET (app.jwt.secret) is not set. Provide at least $MIN_BYTES bytes of random secret material.")
        check(value.toByteArray(Charsets.UTF_8).size >= MIN_BYTES) {
            "JWT_SECRET (app.jwt.secret) must be at least $MIN_BYTES bytes."
        }
        val lower = value.lowercase()
        check(PLACEHOLDERS.none { it in lower }) { "JWT_SECRET (app.jwt.secret) is a known placeholder value; generate a real secret." }
        check(value.toSet().size >= 8) { "JWT_SECRET (app.jwt.secret) has too little entropy; generate a random secret." }
        return value
    }
}

@Service
class JwtService(
    @Value("\${app.jwt.secret:}") secret: String,
    @Value("\${app.jwt.expiration:86400000}") private val expiration: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(JwtSecretPolicy.validate(secret).toByteArray(Charsets.UTF_8))

    init {
        check(expiration > 0) { "app.jwt.expiration must be positive" }
    }

    fun generateToken(userId: Long, tokenVersion: Int): String = Jwts.builder()
        .subject(userId.toString())
        .claim(TOKEN_VERSION, tokenVersion)
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + expiration))
        .signWith(key)
        .compact()

    /** Verified claims, or null for anything invalid: bad signature, expired, malformed, missing claims. */
    fun parse(token: String): TokenClaims? = try {
        val claims: Claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        val userId = claims.subject?.toLongOrNull()
        val version = (claims[TOKEN_VERSION] as? Number)?.toInt()
        if (userId == null || version == null) null else TokenClaims(userId, version)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TOKEN_VERSION = "tv"
    }
}
