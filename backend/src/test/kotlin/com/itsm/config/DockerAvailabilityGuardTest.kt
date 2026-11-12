package com.itsm.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory

/**
 * Stops a missing Docker daemon from turning the PostgreSQL integration tests into silent skips.
 *
 * `ApplicationStartupIntegrationTest` is `@Testcontainers(disabledWithoutDocker = true)` so a developer without
 * Docker can still run the database-free tests. CI sets `ITSM_REQUIRE_DOCKER=true`; with it, this test FAILS when
 * Docker is unreachable, so the build is red instead of green-with-skips. Locally (variable unset) it does nothing.
 */
class DockerAvailabilityGuardTest {

    @Test
    fun `docker is available when the environment requires it`() {
        val required = System.getenv(REQUIRE_DOCKER_ENV)?.equals("true", ignoreCase = true) == true
        if (!required) return
        assertTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "$REQUIRE_DOCKER_ENV=true but no Docker daemon is reachable: the PostgreSQL Testcontainers tests " +
                "cannot run and would have been skipped silently."
        )
    }

    private companion object {
        const val REQUIRE_DOCKER_ENV = "ITSM_REQUIRE_DOCKER"
    }
}
