package com.itsm.notification

import com.itsm.common.model.Priority
import java.time.Instant

/** What a notification is about (docs/architecture/p4.md section 4). Stored; a contract with V42's CHECK. */
enum class NotificationType {
    /** To the new assignee. */
    INCIDENT_ASSIGNED,
    /** To the reporter: a technician has been assigned. */
    ASSIGNMENT_MADE,
    /** To the reporter: the technician asked a question. */
    INFO_REQUESTED,
    /** To the reporter: please confirm the fix. */
    INCIDENT_RESOLVED,
    /** To the assignee: the reporter said it is not fixed. */
    INCIDENT_REOPENED,
    /** To the assignee. */
    SLA_WARNING,
    /** To the assignee. */
    SLA_BREACHED,
    /** To the admins. */
    SLA_POLICY_MISSING,
    /** To whoever the escalation reached. */
    ESCALATED
}

/** Where a notification is delivered. */
enum class ChannelKey { IN_APP, EMAIL, SMS }

/** One notification for one person, before it is delivered. */
data class Notification(
    val recipientId: Long,
    val type: NotificationType,
    val incidentId: String?,
    val priority: Priority?,
    val title: String,
    val body: String,
    val channels: Set<ChannelKey>
) {
    init {
        require(title.isNotBlank() && body.isNotBlank()) { "a notification needs a title and a body" }
        require(ChannelKey.IN_APP in channels) { "every notification is kept in the in-app inbox" }
    }
}

/** A row of the inbox. */
data class StoredNotification(
    val id: Long,
    val recipientId: Long,
    val type: NotificationType,
    val incidentId: String?,
    val priority: Priority?,
    val title: String,
    val body: String,
    val channels: List<ChannelKey>,
    val createdAt: Instant,
    val readAt: Instant?
)

/** What notifications need to know about an incident: who to tell and how urgent it is. */
data class IncidentContext(
    val incidentId: String,
    val title: String,
    val priority: Priority?,
    val reporterId: Long,
    val assigneeId: Long?
)

/**
 * Which channels a notification uses, by the incident's priority: the more urgent, the more intrusive. Email and SMS are
 * simulated (`PRODUCT.md`): they log what would be sent.
 */
object ChannelPolicy {
    fun channelsFor(priority: Priority?): Set<ChannelKey> = when (priority) {
        Priority.P1 -> setOf(ChannelKey.IN_APP, ChannelKey.EMAIL, ChannelKey.SMS)
        Priority.P2 -> setOf(ChannelKey.IN_APP, ChannelKey.EMAIL)
        Priority.P3, Priority.P4, null -> setOf(ChannelKey.IN_APP)
    }
}
