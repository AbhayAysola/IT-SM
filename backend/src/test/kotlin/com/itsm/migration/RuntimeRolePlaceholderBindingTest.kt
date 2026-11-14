package com.itsm.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The runtime-role callback is switched on through environment variables (compose override, Kubernetes), and Spring maps `_` in an environment
 * variable name to `.`, so a placeholder named `runtime_role` could never be set that way. This pins the name the callback uses to what the
 * documented variable actually binds to; a rename of one without the other fails here, not at 3 a.m. on start-up.
 */
class RuntimeRolePlaceholderBindingTest {

    private fun bound(variable: String): Map<String, String> {
        val env = StandardEnvironment()
        // Spring applies the environment-variable name mapping to the source called `systemEnvironment`, which is what the real one is.
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(variable to "itsm_app"))
        )
        return Binder.get(env).bind("spring.flyway.placeholders", Bindable.mapOf(String::class.java, String::class.java)).orElse(emptyMap())
    }

    @Test fun `the documented environment variable binds to the placeholder the callback reads`() {
        val callback = PathMatchingResourcePatternResolver().getResource("classpath:db/callback/afterMigrate__runtime_role_grants.sql")
            .inputStream.bufferedReader().readText()
        val used = Regex("\\$\\{([A-Za-z0-9_.]+)}").findAll(callback).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("runtimerole"), used, "the callback's placeholders")
        val map = bound("SPRING_FLYWAY_PLACEHOLDERS_RUNTIMEROLE")
        assertEquals("itsm_app", map["runtimerole"], "bound map: $map")
    }

    @Test fun `an underscore in the placeholder name would not survive the environment`() {
        assertTrue(bound("SPRING_FLYWAY_PLACEHOLDERS_RUNTIME_ROLE")["runtime_role"] == null, "this is the bug the rename avoids")
    }
}
