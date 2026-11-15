package com.itsm.notification

import com.itsm.admin.AdminApiTestSupport
import com.itsm.auth.JwtService
import com.itsm.common.model.Priority
import com.itsm.common.model.Role
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Real-time notifications over the real stack: a signed-in user opens `GET /api/notifications/stream`, and a notification
 * committed for them arrives on it at once, with their new unread count; one rolled back never does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationStreamIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var notifications: NotificationService
    @Autowired lateinit var hub: NotificationStreamHub
    @Autowired lateinit var tx: TransactionTemplate

    private lateinit var t: AdminApiTestSupport
    @BeforeEach fun setUp() { t = AdminApiTestSupport(users, jwt, jdbc) }

    private fun note(to: Long, title: String) =
        Notification(to, NotificationType.ESCALATED, null, Priority.P2, title, "Escalated to you.", setOf(ChannelKey.IN_APP))

    @Test fun `a committed notification reaches the recipient's open stream at once, a rolled-back one never`() {
        val me = t.caller(Role.TECHNICIAN); val other = t.caller(Role.TECHNICIAN)
        val result = mvc.perform(get("/api/notifications/stream").header("Authorization", me.bearer)).andReturn()
        assertTrue(result.request.isAsyncStarted, "the response stays open")
        assertEquals("text/event-stream", result.response.contentType?.substringBefore(';'))
        assertEquals(1, hub.connections(me.id))
        assertTrue(result.response.contentAsString.contains("event:ready"), result.response.contentAsString)

        tx.executeWithoutResult { notifications.send(listOf(note(me.id, "First, committed"), note(other.id, "For someone else"))) }
        try {
            tx.executeWithoutResult { notifications.send(listOf(note(me.id, "Second, rolled back"))); error("roll back") }
        } catch (e: IllegalStateException) { /* expected */ }

        val body = result.response.contentAsString
        assertTrue(body.contains("event:notification") && body.contains("First, committed"), body)
        assertTrue(body.contains("\"unreadCount\":1"), "with the new unread count: $body")
        assertFalse(body.contains("Second, rolled back"), "a rolled-back notification is never pushed")
        assertFalse(body.contains("For someone else"), "nor another user's")
    }

    @Test fun `only a signed-in user can open a stream`() {
        assertEquals(401, mvc.perform(get("/api/notifications/stream")).andReturn().response.status)
    }
}
