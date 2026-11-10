package com.itsm.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ConfigurableApplicationContext

/** The command wrapper's exit codes and fail-fast behaviour (no database; the service is mocked). */
class AdminBootstrapRunnerTest {

    private val service: AdminBootstrapService = mock(AdminBootstrapService::class.java)
    private val context: ConfigurableApplicationContext = mock(ConfigurableApplicationContext::class.java)

    private fun runner(email: String, password: String) = AdminBootstrapRunner(service, context, email, password, "Administrator")

    @Test fun `missing email or password exits 2 without touching the service`() {
        assertEquals(AdminBootstrapRunner.EXIT_MISSING_CONFIG, runner("", "Bootstr4p!pw").execute())
        assertEquals(AdminBootstrapRunner.EXIT_MISSING_CONFIG, runner("a@campus.example", "  ").execute())
        verify(service, never()).bootstrap(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test fun `success exits 0, refusal and unexpected failures exit 1`() {
        `when`(service.bootstrap("a@campus.example", "Bootstr4p!pw", "Administrator")).thenReturn(1L)
        assertEquals(0, runner("a@campus.example", "Bootstr4p!pw").execute())

        `when`(service.bootstrap("b@campus.example", "Bootstr4p!pw", "Administrator")).thenThrow(BootstrapException("An administrator already exists; bootstrap refused."))
        assertEquals(AdminBootstrapRunner.EXIT_FAILED, runner("b@campus.example", "Bootstr4p!pw").execute())

        `when`(service.bootstrap("c@campus.example", "Bootstr4p!pw", "Administrator")).thenThrow(IllegalStateException("db password=hunter2"))
        assertEquals(AdminBootstrapRunner.EXIT_FAILED, runner("c@campus.example", "Bootstr4p!pw").execute())
    }
}
