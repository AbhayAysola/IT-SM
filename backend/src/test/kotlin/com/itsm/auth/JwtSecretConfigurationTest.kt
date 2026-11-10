package com.itsm.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource

/** The secret is wired from JWT_SECRET with no default, and the application context refuses to start without a good one. */
class JwtSecretConfigurationTest {

    private val runner = ApplicationContextRunner().withBean(JwtService::class.java)

    @Test fun `application yml maps JWT_SECRET into app jwt secret with an empty default`() {
        val source = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).first()
        assertEquals("\${JWT_SECRET:}", source.getProperty("app.jwt.secret"))
        assertEquals("\${JWT_EXPIRATION:86400000}", source.getProperty("app.jwt.expiration"))
    }

    @Test fun `the application context fails to start for a missing, blank, short or placeholder secret`() {
        for (bad in listOf(null, "", "   ", "too-short", "change-me-in-production-minimum-32-chars-required",
            "your-256-bit-secret-key-replace-in-production-minimum-32-chars")) {
            runner.apply { if (bad != null) withPropertyValues("app.jwt.secret=$bad") }
                .run { ctx ->
                    assertNotNull(ctx.startupFailure, "secret '$bad' must prevent startup")
                    assertTrue(generateSequence(ctx.startupFailure) { it.cause }.any { it is IllegalStateException && it.message!!.contains("JWT_SECRET") })
                }
        }
    }

    @Test fun `a strong secret starts the context`() {
        runner.withPropertyValues("app.jwt.secret=k8s-Pr0d-signing-secret-with-plenty-of-entropy-9f3a7c1e")
            .run { ctx -> assertEquals(null, ctx.startupFailure) }
    }
}
