package com.itsm.notification

import com.itsm.common.model.Priority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException

class NotificationStreamHubTest {
    /** Records the events' payloads; can be made to fail like a browser that went away. */
    class Recording : SseEmitter(0L) {
        val sent = mutableListOf<Any>()
        var broken = false
        override fun send(builder: SseEventBuilder) {
            if (broken) throw IOException("gone")
            builder.build().map { it.data }.filter { it !is String }.forEach { sent += it } // skip the "event:…\ndata:" framing
        }
    }

    private val store = mock(NotificationStore::class.java).also { `when`(it.unreadCount(7)).thenReturn(3) }
    private val opened = mutableListOf<Recording>()
    private val hub = object : NotificationStreamHub(store, 0, 2) {
        override fun newEmitter(): SseEmitter = Recording().also { opened += it }
    }

    private fun note(to: Long) = Notification(to, NotificationType.SLA_BREACHED, "INC1001", Priority.P1, "INC1001: resolution SLA breached", "VPN down.", setOf(ChannelKey.IN_APP))
    private fun Recording.payloads() = sent.filterIsInstance<LiveNotificationDto>()

    @Test fun `a new stream starts with the unread count, and each notification reaches the recipient's tabs only`() {
        hub.open(7); hub.open(7); hub.open(8)
        assertEquals(StreamReadyDto(3), opened[0].sent.first())
        hub.published(listOf(note(7)))
        assertEquals(listOf(LiveNotificationDto("SLA_BREACHED", "INC1001", "P1", "INC1001: resolution SLA breached", "VPN down.", 3)), opened[0].payloads())
        assertEquals(1, opened[1].payloads().size)
        assertTrue(opened[2].payloads().isEmpty(), "another user's tab hears nothing")
    }

    @Test fun `a tab that went away is dropped, and forgotten tabs beyond the limit are closed`() {
        hub.open(7); hub.open(7)
        opened[0].broken = true
        hub.published(listOf(note(7)))
        assertEquals(1, hub.connections(7))
        hub.open(7); hub.open(7)
        assertEquals(2, hub.connections(7), "at most two per user in this test")
        hub.published(listOf(note(42)))
        assertEquals(0, hub.connections(42), "nobody listening: nothing to do")
    }
}
