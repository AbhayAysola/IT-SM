package com.itsm.sla

import com.itsm.common.model.Priority
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The only P4 code that reads the `incidents` table (P2's table, written by P2 and P3). Read-only.
 *
 * Until P2 and P3 publish domain events (docs/architecture/p4.md section 6), the SLA monitor learns about incidents by
 * polling through this class. When the events exist, this adapter is replaced and nothing else in `sla/` changes.
 */
@Repository
class IncidentSnapshotReader(private val jdbc: JdbcTemplate) {

    /**
     * Incidents whose SLA still needs evaluating: past `NEW` (so service and priority are set, a CHECK guarantees it) and
     * either without an SLA row yet or with one that is not complete. Oldest first, at most [limit].
     */
    fun findTracked(limit: Int): List<IncidentSnapshot> = jdbc.query(
        """
        SELECT i.id, i.state, i.priority, i.service_id, i.created_at, i.updated_at, i.first_response_at, i.resolved_at
        FROM incidents i LEFT JOIN incident_sla s ON s.incident_id = i.id
        WHERE i.state <> 'NEW' AND (s.incident_id IS NULL OR NOT s.complete)
        ORDER BY i.created_at, i.id
        LIMIT ?
        """.trimIndent(),
        { rs, _ -> rs.toSnapshot() },
        limit
    )

    private fun ResultSet.toSnapshot() = IncidentSnapshot(
        incidentId = getString("id"),
        state = getString("state"),
        priority = Priority.valueOf(getString("priority")),
        serviceId = getLong("service_id"),
        createdAt = instant("created_at")!!,
        updatedAt = instant("updated_at")!!,
        firstResponseAt = instant("first_response_at"),
        resolvedAt = instant("resolved_at")
    )
}

internal fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
