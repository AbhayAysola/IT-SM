package com.itsm.intake

import com.itsm.common.error.ResourceNotFound
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.IncidentAccessPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * P2's implementation of the incident visibility contract. Reporter, assigned technician and admins may view an
 * incident; anyone else gets a 404 (the existence of an incident is not revealed to unauthorised callers).
 */
@Service
class SpringIncidentAccessPolicy(private val jdbc: JdbcTemplate) : IncidentAccessPolicy {

    override fun canView(user: CurrentUser, incidentId: String): Boolean {
        val rows = jdbc.query(
            "SELECT reporter_id, assignee_id FROM incidents WHERE id = ?",
            { rs, _ -> Pair(rs.getLong("reporter_id"), rs.getObject("assignee_id") as Long?) },
            incidentId
        )
        if (rows.isEmpty()) throw ResourceNotFound("Incident", incidentId)
        val (reporterId, assigneeId) = rows[0]
        return user.isAdmin || reporterId == user.id || assigneeId == user.id
    }
}
