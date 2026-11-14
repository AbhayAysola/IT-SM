package com.itsm.notification

import com.itsm.common.model.Priority
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A change of an incident's state or assignee since P4 last looked, as polling sees it. */
data class IncidentChange(
    val context: IncidentContext,
    val state: String,
    val assignedAt: Instant?,
    /** Null when P4 has never seen this incident. */
    val previousState: String?,
    val previousAssigneeId: Long?
) {
    val firstSight: Boolean get() = previousState == null
}

/**
 * Persistence for notifications (V42): the inbox, and the per-incident watch used to notice lifecycle changes. The
 * incident reads join P2's `incidents` table read-only, like the SLA and escalation monitors, until P2/P3 publish
 * domain events (docs/architecture/p4.md section 6).
 */
@Repository
class NotificationStore(private val jdbc: JdbcTemplate) {

    // --- inbox --------------------------------------------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    fun insert(n: Notification): Long = jdbc.queryForObject(
        """INSERT INTO notifications (recipient_id, type, incident_id, priority, title, body, channels)
           VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
        Long::class.java, n.recipientId, n.type.name, n.incidentId, n.priority?.name, n.title.take(200), n.body.take(1000),
        n.channels.sortedBy { it.ordinal }.map { it.name }.toTypedArray()
    )!!

    fun inbox(recipientId: Long, unreadOnly: Boolean, page: Int, size: Int): Pair<List<StoredNotification>, Long> {
        val filter = if (unreadOnly) " AND read_at IS NULL" else ""
        val total = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ?$filter", Long::class.java, recipientId)!!
        val items = jdbc.query(
            "SELECT * FROM notifications WHERE recipient_id = ?$filter ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            { rs, _ -> rs.toNotification() }, recipientId, size, page.toLong() * size
        )
        return items to total
    }

    fun unreadCount(recipientId: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND read_at IS NULL", Long::class.java, recipientId)!!

    /** Marks one of the recipient's own notifications read (no-op if already read). Null if it is not theirs or unknown. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun markRead(id: Long, recipientId: Long, now: Instant): StoredNotification? {
        jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ? AND recipient_id = ? AND read_at IS NULL", ts(now), id, recipientId)
        return jdbc.query("SELECT * FROM notifications WHERE id = ? AND recipient_id = ?", { rs, _ -> rs.toNotification() }, id, recipientId).firstOrNull()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun markAllRead(recipientId: Long, now: Instant): Int =
        jdbc.update("UPDATE notifications SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL", ts(now), recipientId)

    // --- incidents ----------------------------------------------------------------------------------------------------

    fun context(incidentId: String): IncidentContext? = jdbc.query(
        "SELECT id, title, priority, reporter_id, assignee_id FROM incidents WHERE id = ?", { rs, _ -> rs.toContext() }, incidentId
    ).firstOrNull()

    /** Incidents whose state or assignee differs from what P4 last saw (or that it never saw), oldest first. */
    fun changes(limit: Int): List<IncidentChange> = jdbc.query(
        """
        SELECT i.id, i.title, i.priority, i.reporter_id, i.assignee_id, i.state, i.assigned_at,
               w.state AS previous_state, w.assignee_id AS previous_assignee_id
        FROM incidents i LEFT JOIN notification_watch w ON w.incident_id = i.id
        WHERE w.incident_id IS NULL OR w.state <> i.state OR w.assignee_id IS DISTINCT FROM i.assignee_id
        ORDER BY i.updated_at, i.id
        LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            IncidentChange(
                context = rs.toContext(),
                state = rs.getString("state"),
                assignedAt = rs.instant("assigned_at"),
                previousState = rs.getString("previous_state"),
                previousAssigneeId = rs.getObject("previous_assignee_id") as Long?
            )
        },
        limit
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun remember(incidentId: String, state: String, assigneeId: Long?, now: Instant) {
        jdbc.update(
            """INSERT INTO notification_watch (incident_id, state, assignee_id, updated_at) VALUES (?, ?, ?, ?)
               ON CONFLICT (incident_id) DO UPDATE SET state = EXCLUDED.state, assignee_id = EXCLUDED.assignee_id, updated_at = EXCLUDED.updated_at""",
            incidentId, state, assigneeId, ts(now)
        )
    }

    /** The lifecycle monitor's advisory lock (see the SLA monitor). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun tryMonitorLock(): Boolean = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, MONITOR_LOCK_KEY)!!

    private fun ResultSet.toContext() = IncidentContext(
        incidentId = getString("id"),
        title = getString("title"),
        priority = getString("priority")?.let(Priority::valueOf),
        reporterId = getLong("reporter_id"),
        assigneeId = getObject("assignee_id") as Long?
    )

    private fun ResultSet.toNotification(): StoredNotification {
        @Suppress("UNCHECKED_CAST")
        val channels = (getArray("channels").array as Array<String>).map(ChannelKey::valueOf)
        return StoredNotification(
            id = getLong("id"),
            recipientId = getLong("recipient_id"),
            type = NotificationType.valueOf(getString("type")),
            incidentId = getString("incident_id"),
            priority = getString("priority")?.let(Priority::valueOf),
            title = getString("title"),
            body = getString("body"),
            channels = channels,
            createdAt = instant("created_at")!!,
            readAt = instant("read_at")
        )
    }

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
    private fun ts(i: Instant) = i.atOffset(ZoneOffset.UTC)

    companion object {
        const val MONITOR_LOCK_KEY = 0x5034_4E4F_54L
    }
}
