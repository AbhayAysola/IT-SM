package com.itsm.audit

import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.spi.IncidentAccessPolicy
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

/** Removes every [IncidentAccessPolicy] definition, so this context behaves as if P2 had not shipped (or removed) it. */
class NoIncidentPolicyConfig : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        beanFactory.getBeanNamesForType(IncidentAccessPolicy::class.java, true, false).forEach {
            (beanFactory as BeanDefinitionRegistry).removeBeanDefinition(it)
        }
    }
}

/**
 * With NO IncidentAccessPolicy bean the incident trail must be unavailable (503) for EVERYONE, including admins and
 * including an incident that has events, and must never fall back to an own access rule or to an empty 200.
 * Independent of whether P2 has merged: the provider is removed from the context here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoIncidentPolicyConfig::class)
@Testcontainers(disabledWithoutDocker = true)
class IncidentAuditProviderUnavailableIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var provider: ObjectProvider<IncidentAccessPolicy>

    @Test fun `the application runs without a provider and answers 503 to every role, never an empty page or a 200`() {
        assertNull(provider.getIfAvailable(), "precondition: no IncidentAccessPolicy bean in this context")
        val t = AdminApiTestSupport(users, jwt, jdbc)
        val incident = "INC" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        jdbc.update(
            """insert into audit_events (occurred_at, actor_type, actor_id, actor_role, action, entity_type, entity_id, incident_id,
                   description) values (?, 'USER', 1, 'ADMIN', 'INCIDENT_CREATED', 'INCIDENT', ?, ?, 'created')""",
            OffsetDateTime.now(), incident, incident
        )
        for (role in Role.entries) {
            mvc.perform(get("/api/incidents/$incident/audit").header("Authorization", t.caller(role).bearer))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
        }
        // an incident that does not exist is indistinguishable here: still 503, not 404 and not an empty page
        mvc.perform(get("/api/incidents/INC-NOPE/audit").header("Authorization", t.caller(Role.ADMIN).bearer))
            .andExpect(status().isServiceUnavailable)
        // anonymous callers are still turned away by authentication first
        mvc.perform(get("/api/incidents/$incident/audit")).andExpect(status().isUnauthorized)
    }

    @Test fun `the admin listing is unaffected by the missing provider`() {
        val t = AdminApiTestSupport(users, jwt, jdbc)
        mvc.perform(get("/api/admin/audit-events?size=1").header("Authorization", t.caller(Role.ADMIN).bearer))
            .andExpect(status().isOk)
    }
}
