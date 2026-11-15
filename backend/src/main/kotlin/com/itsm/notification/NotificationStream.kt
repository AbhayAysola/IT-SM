package com.itsm.notification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Told about in-app notifications once they are committed, so they can reach open browsers at once. */
interface NotificationLiveUpdates {
    fun published(notifications: Collection<Notification>)
}

/** What a browser receives for one new notification: enough for the bell and a desktop notification. */
data class LiveNotificationDto(
    val type: String, val incidentId: String?, val priority: String?, val title: String, val body: String,
    /** The recipient's unread count after this notification. */
    val unreadCount: Long
)

data class StreamReadyDto(val unreadCount: Long)

/**
 * Real-time notifications (docs/architecture/p4.md section 4): server-sent events to each signed-in user's open tabs
 * (`GET /api/notifications/stream`). A new notification is pushed after the transaction that created it commits, with the
 * recipient's new unread count; the browser updates the bell and may show a desktop notification. Nothing is lost when no
 * tab is open: the inbox is the record, and the bell still polls as a fallback.
 *
 * Connections are held by this instance only. With several backend instances a user connected to another instance hears
 * about it at the bell's next poll, which is acceptable for an inbox (a message broker would be the next step).
 */
@Component
class NotificationStreamHub(
    private val store: NotificationStore,
    @Value("\${app.notification.stream.timeout-ms:1800000}") private val timeoutMs: Long,
    @Value("\${app.notification.stream.max-per-user:5}") private val maxPerUser: Int
) : NotificationLiveUpdates {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    /** Opens a stream for [userId]; the oldest of their streams is closed beyond [maxPerUser] (forgotten tabs). */
    fun open(userId: Long): SseEmitter {
        val emitter = newEmitter()
        val mine = emitters.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        mine += emitter
        while (mine.size > maxPerUser) mine.removeAt(0).complete()
        val remove = Runnable { drop(userId, emitter) }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove.run() }
        send(userId, emitter, SseEmitter.event().name("ready").data(StreamReadyDto(store.unreadCount(userId)), MediaType.APPLICATION_JSON))
        return emitter
    }

    override fun published(notifications: Collection<Notification>) {
        for ((recipient, mine) in notifications.groupBy { it.recipientId }) {
            val open = emitters[recipient]
            if (open.isNullOrEmpty()) continue
            val unread = store.unreadCount(recipient)
            for (n in mine) {
                val dto = LiveNotificationDto(n.type.name, n.incidentId, n.priority?.name, n.title, n.body, unread)
                for (e in open) send(recipient, e, SseEmitter.event().name("notification").data(dto, MediaType.APPLICATION_JSON))
            }
        }
    }

    /** A comment line every so often keeps idle connections open through proxies and lets dead ones be noticed. */
    fun heartbeat() {
        for ((user, open) in emitters) for (e in open) send(user, e, SseEmitter.event().comment("keep-alive"))
    }

    fun connections(userId: Long): Int = emitters[userId]?.size ?: 0

    /** Overridable so unit tests can record what is sent. */
    protected fun newEmitter(): SseEmitter = SseEmitter(timeoutMs)

    private fun send(userId: Long, emitter: SseEmitter, event: SseEmitter.SseEventBuilder) {
        try {
            emitter.send(event)
        } catch (e: IOException) {
            drop(userId, emitter) // the browser went away
        } catch (e: IllegalStateException) {
            drop(userId, emitter) // already completed
        }
    }

    private fun drop(userId: Long, emitter: SseEmitter) {
        emitters[userId]?.let { it.remove(emitter); if (it.isEmpty()) emitters.remove(userId, it) }
        log.debug("Notification stream of user #{} closed", userId)
    }
}

/** Sends the heartbeat; `app.notification.stream.enabled=false` turns it off. Enables scheduling itself, like the monitors. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.notification.stream", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationStreamSchedulingConfig

@Component
@ConditionalOnProperty(prefix = "app.notification.stream", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationStreamHeartbeat(private val hub: NotificationStreamHub) {
    @Scheduled(fixedDelayString = "\${app.notification.stream.heartbeat-ms:25000}")
    fun beat() = hub.heartbeat()
}
