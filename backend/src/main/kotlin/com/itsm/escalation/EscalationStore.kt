package com.itsm.escalation

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence for escalation (V41): the settings singleton, the append-only escalation log, and the read of open
 * incidents with their SLA facts. That read joins P2's `incidents` table read-only, like the SLA monitor's adapter, until
 * P2/P3 publish domain events (docs/architecture/p4.md section 6).
 */
@Repository
class EscalationStore(private val jdbc: JdbcTemplate, private val named: NamedParameterJdbcTemplate) {

    // --- settings -----------------------------------------------------------------------------------------------------

    fun settings(): EscalationSettings = jdbc.queryForObject(
        "SELECT unassigned_minutes, second_level_pct, admin_level_pct, version, updated_at, updated_by_user_id FROM escalation_settings WHERE id = 1"
    ) { rs, _ ->
        EscalationSettings(
            unassignedMinutes = rs.getInt("unassigned_minutes"),
            secondLevelPct = rs.getInt("second_level_pct"),
            adminLevelPct = rs.getInt("admin_level_pct"),
            version = rs.getLong("version"),
            updatedAt = rs.instant("updated_at"),
            updatedByUserId = rs.getObject("updated_by_user_id") as Long?
        )
    }!!

    /** Replaces the settings if nobody changed them since [expectedVersion]; otherwise a 409 `CONCURRENT_MODIFICATION`. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun updateSettings(unassignedMinutes: Int, secondLevelPct: Int, adminLevelPct: Int, expectedVersion: Long, userId: Long, now: Instant): EscalationSettings {
        val updated = jdbc.update(
            """UPDATE escalation_settings SET unassigned_minutes = ?, second_level_pct = ?, admin_level_pct = ?,
                   version = version + 1, updated_at = ?, updated_by_user_id = ? WHERE id = 1 AND version = ?""",
            unassignedMinutes, secondLevelPct, adminLevelPct, ts(now), userId, expectedVersion
        )
        if (updated != 1) throw OptimisticLockingFailureException("Escalation settings changed concurrently")
        return settings()
    }

    // --- escalations --------------------------------------------------------------------------------------------------

    /** Triggers that already fired, per incident. */
    fun firedTriggers(incidentIds: Collection<String>): Map<String, Set<EscalationTrigger>> {
        if (incidentIds.isEmpty()) return emptyMap()
        return named.query(
            "SELECT incident_id, trigger FROM incident_escalations WHERE incident_id IN (:ids)", MapSqlParameterSource("ids", incidentIds)
        ) { rs, _ -> rs.getString(1) to EscalationTrigger.valueOf(rs.getString(2)) }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(incidentId: String, trigger: EscalationTrigger, targets: List<Long>, assigneeId: Long?, now: Instant): Escalation {
        val id = jdbc.queryForObject(
            """INSERT INTO incident_escalations (incident_id, trigger, tier, target_user_ids, assignee_id, created_at)
               VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
            Long::class.java, incidentId, trigger.name, trigger.tier.name, targets.toTypedArray(), assigneeId, ts(now)
        )!!
        return Escalation(id, incidentId, trigger, trigger.tier, targets, assigneeId, now)
    }

    fun forIncident(incidentId: String): List<Escalation> = jdbc.query(
        "SELECT * FROM incident_escalations WHERE incident_id = ? ORDER BY created_at, id", { rs, _ -> rs.toEscalation() }, incidentId
    )

    // --- monitor ------------------------------------------------------------------------------------------------------

    /**
     * Open incidents with their SLA facts (left join: an incident may not have an SLA row yet, or have no policy). Oldest
     * first, at most [limit].
     */
    fun openCandidates(limit: Int): List<EscalationCandidate> = jdbc.query(
        """
        SELECT i.id, i.state, i.assignee_id, i.created_at,
               s.response_breached_at, s.resolution_breached_at, s.clock_start, s.resolution_minutes,
               COALESCE(s.paused_seconds, 0) AS paused_seconds, s.paused_since, s.resolution_met_at
        FROM incidents i LEFT JOIN incident_sla s ON s.incident_id = i.id
        WHERE i.state IN ('CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO')
        ORDER BY i.created_at, i.id
        LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            EscalationCandidate(
                incidentId = rs.getString("id"),
                state = rs.getString("state"),
                assigneeId = rs.getObject("assignee_id") as Long?,
                createdAt = rs.instant("created_at")!!,
                responseBreachedAt = rs.instant("response_breached_at"),
                resolutionBreachedAt = rs.instant("resolution_breached_at"),
                clockStart = rs.instant("clock_start"),
                resolutionMinutes = rs.getObject("resolution_minutes") as Int?,
                pausedSeconds = rs.getLong("paused_seconds"),
                pausedSince = rs.instant("paused_since"),
                resolutionMetAt = rs.instant("resolution_met_at")
            )
        },
        limit
    )

    /** The monitor's transaction-scoped advisory lock (see the SLA monitor): one evaluating instance at a time. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun tryMonitorLock(): Boolean = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, MONITOR_LOCK_KEY)!!

    private fun ResultSet.toEscalation(): Escalation {
        @Suppress("UNCHECKED_CAST")
        val targets = (getArray("target_user_ids").array as Array<Long>).toList()
        return Escalation(
            id = getLong("id"),
            incidentId = getString("incident_id"),
            trigger = EscalationTrigger.valueOf(getString("trigger")),
            tier = EscalationTier.valueOf(getString("tier")),
            targetUserIds = targets,
            assigneeId = getObject("assignee_id") as Long?,
            createdAt = instant("created_at")!!
        )
    }

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
    private fun ts(i: Instant) = i.atOffset(ZoneOffset.UTC)

    companion object {
        /** Different from the SLA monitor's key, so the two jobs do not block each other. */
        const val MONITOR_LOCK_KEY = 0x5034_4553_43L
    }
}
