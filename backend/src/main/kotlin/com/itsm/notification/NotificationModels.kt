package com.itsm.notification

import com.itsm.common.model.Priority
import java.time.Instant

/** What a notification is about (docs/architecture/p4.md section 4). Stored; a contract with V42's CHECK (extended in V43). */
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
    ESCALATED,
    /** To the assignee (the admins when nobody has it): predicted to miss the resolution deadline (V43). */
    SLA_AT_RISK,
    /** To the assignee: an admin changed the incident's priority (V44). */
    PRIORITY_CHANGED,
    /** To every active admin: P4 suggests a different priority (V45). */
    PRIORITY_SUGGESTED,
    /** To the assignee: the reporter confirmed the fix (V46). */
    INCIDENT_VERIFIED,
    /** To the reporter: the incident was closed (V46). */
    INCIDENT_CLOSED
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
