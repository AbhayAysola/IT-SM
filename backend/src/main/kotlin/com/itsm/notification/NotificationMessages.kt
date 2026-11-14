package com.itsm.notification

import com.itsm.escalation.EscalationRecorded
import com.itsm.sla.SlaClock
import com.itsm.sla.SlaNotice
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Who hears about what, and in which words (docs/architecture/p4.md section 4). Pure: no database or beans. Titles name the
 * incident by id; bodies repeat its title, which every recipient here is allowed to see (reporter, assignee, the people an
 * escalation reached, admins).
 */
object NotificationMessages {

    /** A first-seen incident still waiting for its assignee to accept counts as just assigned if it happened this recently. */
    val RECENT_ASSIGNMENT: Duration = Duration.ofMinutes(15)

    // --- lifecycle (polled) -------------------------------------------------------------------------------------------

    fun forChange(change: IncidentChange, now: Instant): List<Notification> {
        val c = change.context
        if (change.firstSight) {
            // Baseline: an incident P4 has never seen is not news, except a fresh assignment the polling just caught.
            val fresh = change.state == "ASSIGNED" && c.assigneeId != null && change.assignedAt != null && !change.assignedAt.isBefore(now.minus(RECENT_ASSIGNMENT))
            return if (fresh) assigned(c) else emptyList()
        }
        return buildList {
            if (c.assigneeId != null && c.assigneeId != change.previousAssigneeId) addAll(assigned(c))
            if (change.state == "PENDING_INFO" && change.previousState != "PENDING_INFO") {
                add(make(c, c.reporterId, NotificationType.INFO_REQUESTED, "The technician has a question about ${c.incidentId}", "Open the incident to answer: ${c.title}"))
            }
            if (change.state == "RESOLVED" && change.previousState != "RESOLVED") {
                add(make(c, c.reporterId, NotificationType.INCIDENT_RESOLVED, "${c.incidentId} is resolved: please confirm the fix", c.title))
            }
            if (change.previousState == "RESOLVED" && change.state in REOPENED_STATES && c.assigneeId != null) {
                add(make(c, c.assigneeId, NotificationType.INCIDENT_REOPENED, "${c.incidentId} was reopened by the reporter", c.title))
            }
        }
    }

    private fun assigned(c: IncidentContext): List<Notification> = buildList {
        val assignee = c.assigneeId ?: return@buildList
        add(make(c, assignee, NotificationType.INCIDENT_ASSIGNED, "${c.incidentId} was assigned to you", c.title))
        if (c.reporterId != assignee) {
            add(make(c, c.reporterId, NotificationType.ASSIGNMENT_MADE, "A technician is working on ${c.incidentId}", c.title))
        }
    }

    // --- SLA and escalation (P4's own events) -------------------------------------------------------------------------

    /** [admins] is only asked when the notice goes to them. */
    fun forSla(notice: SlaNotice, c: IncidentContext, admins: () -> List<Long>): List<Notification> = when (notice.kind) {
        SlaNotice.Kind.WARNING -> listOfNotNull(c.assigneeId?.let {
            make(c, it, NotificationType.SLA_WARNING, "${c.incidentId}: ${clockName(notice.clock)} SLA at risk", "${c.title}. Due ${when_(notice.dueAt)}.")
        })
        SlaNotice.Kind.BREACHED -> listOfNotNull(c.assigneeId?.let {
            make(c, it, NotificationType.SLA_BREACHED, "${c.incidentId}: ${clockName(notice.clock)} SLA breached", "${c.title}. The deadline was ${when_(notice.dueAt)}.")
        })
        SlaNotice.Kind.POLICY_MISSING -> admins().distinct().map {
            make(c, it, NotificationType.SLA_POLICY_MISSING, "${c.incidentId} has no SLA policy", "No SLA policy covers its service and priority, so it has no deadlines: ${c.title}")
        }
    }

    fun forEscalation(e: EscalationRecorded, c: IncidentContext): List<Notification> = e.targetUserIds.distinct().map {
        make(c, it, NotificationType.ESCALATED, "${c.incidentId} was escalated to you", "${e.trigger.description}: ${c.title}")
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private val REOPENED_STATES = setOf("ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS", "PENDING_INFO")
    private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    private fun clockName(clock: SlaClock?) = if (clock == SlaClock.RESPONSE) "response" else "resolution"
    private fun when_(at: Instant?) = at?.let(TIME::format) ?: "unknown"

    private fun make(c: IncidentContext, recipient: Long, type: NotificationType, title: String, body: String) =
        Notification(recipient, type, c.incidentId, c.priority, title, body, ChannelPolicy.channelsFor(c.priority))
}
