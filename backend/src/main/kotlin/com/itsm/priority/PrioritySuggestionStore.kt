package com.itsm.priority

import com.itsm.common.model.Impact
import com.itsm.common.model.Priority
import com.itsm.common.model.Urgency
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** An open incident without an open suggestion, with what the rules need. */
data class SuggestionCandidate(
    val incidentId: String,
    val priority: Priority,
    val serviceId: Long,
    val impact: Impact?,
    val urgency: Urgency?,
    val manuallyChanged: Boolean,
    val duplicateReports: Int
)

data class StoredSuggestion(
    val id: Long,
    val incidentId: String,
    val currentPriority: Priority,
    val suggestedPriority: Priority,
    val rules: List<SuggestionRule>,
    val reasons: List<String>,
    val createdAt: Instant,
    val status: String
)

/**
 * Persistence of priority suggestions (V45). Reads P2's `incidents` and P1's `audit_events` read-only: the stored answers,
 * whether an admin changed the priority (`INCIDENT_PRIORITY_CHANGED`), and P2's duplicate flags
 * (`INCIDENT_DUPLICATE_FLAGGED`, whose `duplicateIds` name the incidents a new report resembles).
 */
@Repository
class PrioritySuggestionStore(private val jdbc: JdbcTemplate) {

    fun candidates(duplicatesSince: Instant, limit: Int): List<SuggestionCandidate> = jdbc.query(
        """
        SELECT i.id, i.priority, i.service_id, i.impact, i.urgency,
               EXISTS (SELECT 1 FROM audit_events a WHERE a.action = 'INCIDENT_PRIORITY_CHANGED' AND a.entity_id = i.id) AS manually_changed,
               (SELECT count(DISTINCT a.entity_id) FROM audit_events a
                 WHERE a.action = 'INCIDENT_DUPLICATE_FLAGGED' AND a.occurred_at >= ? AND a.entity_id <> i.id
                   AND a.metadata -> 'duplicateIds' @> jsonb_build_array(i.id)) AS duplicate_reports
        FROM incidents i
        WHERE i.state IN ($OPEN_STATES) AND i.priority IS NOT NULL AND i.service_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM incident_priority_suggestions s WHERE s.incident_id = i.id AND s.status = 'OPEN')
        ORDER BY i.created_at, i.id
        LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            SuggestionCandidate(
                incidentId = rs.getString("id"),
                priority = Priority.valueOf(rs.getString("priority")),
                serviceId = rs.getLong("service_id"),
                impact = rs.getString("impact")?.let(Impact::valueOf),
                urgency = rs.getString("urgency")?.let(Urgency::valueOf),
                manuallyChanged = rs.getBoolean("manually_changed"),
                duplicateReports = rs.getInt("duplicate_reports")
            )
        },
        ts(duplicatesSince), limit
    )

    /** True when an admin already dismissed [priority] for this incident: it is not suggested again. */
    fun wasDismissed(incidentId: String, priority: Priority): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM incident_priority_suggestions WHERE incident_id = ? AND suggested_priority = ? AND status = 'DISMISSED')",
        Boolean::class.java, incidentId, priority.name
    )!!

    @Transactional(propagation = Propagation.MANDATORY)
    fun insert(incidentId: String, current: Priority, s: PrioritySuggestion, now: Instant): Long = jdbc.queryForObject(
        """INSERT INTO incident_priority_suggestions (incident_id, current_priority, suggested_priority, rules, reasons, created_at)
           VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
        Long::class.java, incidentId, current.name, s.priority.name, s.rules.map { it.name }.toTypedArray(), s.reasons.toTypedArray(), ts(now)
    )!!

    /**
     * Closes open suggestions the incident has moved past: APPLIED once its priority is the suggested one, OUTDATED when
     * it changed to something else or the incident is no longer being worked on. Returns how many were closed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun closeResolved(now: Instant): Int = jdbc.update(
        """
        UPDATE incident_priority_suggestions s
        SET status = CASE WHEN i.priority = s.suggested_priority THEN 'APPLIED' ELSE 'OUTDATED' END, closed_at = ?
        FROM incidents i
        WHERE i.id = s.incident_id AND s.status = 'OPEN'
          AND (i.priority IS DISTINCT FROM s.current_priority OR i.state NOT IN ($OPEN_STATES))
        """.trimIndent(),
        ts(now)
    )

    /** The open suggestion for an incident, if it still applies (same priority, still being worked on). */
    fun openFor(incidentId: String): StoredSuggestion? = jdbc.query(
        """
        SELECT s.* FROM incident_priority_suggestions s JOIN incidents i ON i.id = s.incident_id
        WHERE s.incident_id = ? AND s.status = 'OPEN' AND i.priority = s.current_priority AND i.state IN ($OPEN_STATES)
        """.trimIndent(),
        { rs, _ -> rs.toSuggestion() }, incidentId
    ).firstOrNull()

    /** Dismisses the open suggestion; null when there is none. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun dismiss(incidentId: String, userId: Long, now: Instant): StoredSuggestion? = jdbc.query(
        """UPDATE incident_priority_suggestions SET status = 'DISMISSED', closed_at = ?, closed_by = ?
           WHERE incident_id = ? AND status = 'OPEN' RETURNING *""",
        { rs, _ -> rs.toSuggestion() }, ts(now), userId, incidentId
    ).firstOrNull()

    /** The monitor's transaction-scoped advisory lock (see the SLA monitor). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun tryMonitorLock(): Boolean = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, MONITOR_LOCK_KEY)!!

    private fun ResultSet.toSuggestion(): StoredSuggestion {
        @Suppress("UNCHECKED_CAST")
        val rules = (getArray("rules").array as Array<String>).map(SuggestionRule::valueOf)
        @Suppress("UNCHECKED_CAST")
        val reasons = (getArray("reasons").array as Array<String>).toList()
        return StoredSuggestion(
            id = getLong("id"), incidentId = getString("incident_id"),
            currentPriority = Priority.valueOf(getString("current_priority")), suggestedPriority = Priority.valueOf(getString("suggested_priority")),
            rules = rules, reasons = reasons, createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(), status = getString("status")
        )
    }

    private fun ts(i: Instant) = i.atOffset(ZoneOffset.UTC)

    companion object {
        /** Classified and not yet resolved, like P2's priority endpoint. */
        const val OPEN_STATES = "'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'"
        /** Distinct from the SLA, escalation and notification monitors' keys ("P4" "PRI"). */
        const val MONITOR_LOCK_KEY = 0x5034_5052_49L
    }
}
