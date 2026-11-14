package com.itsm.notification

import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.CurrentUserProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class NotificationDto(
    val id: Long,
    val type: String,
    val incidentId: String?,
    val priority: String?,
    val title: String,
    val body: String,
    val channels: List<String>,
    val createdAt: Instant,
    val readAt: Instant?
) {
    companion object {
        fun of(n: StoredNotification) = NotificationDto(
            n.id, n.type.name, n.incidentId, n.priority?.name, n.title, n.body, n.channels.map { it.name }, n.createdAt, n.readAt
        )
    }
}

data class UnreadCountDto(val count: Long)
data class MarkedReadDto(val updated: Int)

/** Every user's own inbox. Nobody can read or mark anyone else's notifications: another user's id answers 404. */
@Service
class InboxService(private val store: NotificationStore, private val clock: Clock) {

    @Transactional(readOnly = true)
    fun list(user: CurrentUser, unreadOnly: Boolean, query: PageQuery): PageResponse<NotificationDto> {
        val (items, total) = store.inbox(user.id, unreadOnly, query.page, query.size)
        return PageResponse(items.map(NotificationDto::of), query.page, query.size, total, ((total + query.size - 1) / query.size).toInt())
    }

    @Transactional(readOnly = true)
    fun unreadCount(user: CurrentUser) = UnreadCountDto(store.unreadCount(user.id))

    @Transactional
    fun markRead(user: CurrentUser, id: Long): NotificationDto =
        store.markRead(id, user.id, now())?.let(NotificationDto::of) ?: throw ResourceNotFound("Notification", id)

    @Transactional
    fun markAllRead(user: CurrentUser) = MarkedReadDto(store.markAllRead(user.id, now()))

    private fun now() = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
}

@RestController
@RequestMapping("${ApiPaths.API}/notifications")
class NotificationController(private val inbox: InboxService, private val currentUser: CurrentUserProvider) {

    /** Newest first; `unreadOnly=true` hides the read ones. */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): PageResponse<NotificationDto> = inbox.list(currentUser.current(), unreadOnly, PageQuery(page, size))

    @GetMapping("/unread-count")
    fun unreadCount(): UnreadCountDto = inbox.unreadCount(currentUser.current())

    /** Idempotent: marking a read notification again changes nothing. */
    @PostMapping("/{id}/read")
    fun markRead(@PathVariable id: Long): NotificationDto = inbox.markRead(currentUser.current(), id)

    @PostMapping("/read-all")
    fun markAllRead(): MarkedReadDto = inbox.markAllRead(currentUser.current())
}
