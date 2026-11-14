package com.itsm.sla

import com.itsm.common.model.Priority
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

/** An open incident whose resolution clock is running and has no risk recorded yet. */
data class RiskCandidate(
    val incidentId: String,
    val priority: Priority,
    val categoryId: Long?,
    val assigneeId: Long?,
    val clockStart: Instant,
    val pausedSeconds: Long,
    val resolutionDueAt: Instant
)

/** How long one resolved incident needed on its resolution clock, pauses excluded. */
data class ResolvedDuration(val priority: Priority, val categoryId: Long?, val minutes: Long)

/** A recorded prediction (V43). */
data class SlaRisk(
    val incidentId: String,
    val raisedAt: Instant,
    val dueAt: Instant,
    val predictedFinishAt: Instant,
    val basis: RiskBasis,
    val sampleSize: Int,
    val typicalRemainingMinutes: Long,
    val loadFactor: Double,
    val assigneeId: Long?
)

/**
 * Persistence of predictive SLA risk (V43). History comes from P4's own `incident_sla` (when each resolution clock was
 * met, minus pauses); the category and assignee are read from P2's `incidents` table, read-only, like the SLA list.
 */
@Repository
class SlaRiskStore(private val jdbc: JdbcTemplate, private val named: NamedParameterJdbcTemplate) {

    /** Candidates, nearest deadline first, at most [limit]. A clock in WARNING, PAUSED, MET or BREACHED is not one. */
    fun candidates(limit: Int): List<RiskCandidate> = jdbc.query(
        """
        SELECT s.incident_id, s.priority, i.category_id, i.assignee_id, s.clock_start, s.paused_seconds, s.resolution_due_at
        FROM incident_sla s
        JOIN incidents i ON i.id = s.incident_id
        LEFT JOIN incident_sla_risk r ON r.incident_id = s.incident_id
        WHERE NOT s.complete AND s.resolution_status = 'RUNNING' AND s.resolution_due_at IS NOT NULL AND r.incident_id IS NULL
        ORDER BY s.resolution_due_at, s.incident_id
        LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            RiskCandidate(
                incidentId = rs.getString("incident_id"),
                priority = Priority.valueOf(rs.getString("priority")),
                categoryId = rs.getObject("category_id") as Long?,
                assigneeId = rs.getObject("assignee_id") as Long?,
                clockStart = rs.instant("clock_start")!!,
                pausedSeconds = rs.getLong("paused_seconds"),
                resolutionDueAt = rs.instant("resolution_due_at")!!
            )
        },
        limit
    )

    /** Resolution-clock durations of incidents whose clock was met since [since], for [priorities]. */
    fun history(priorities: Set<Priority>, since: Instant): List<ResolvedDuration> {
        if (priorities.isEmpty()) return emptyList()
        return named.query(
            """
            SELECT s.priority, i.category_id,
                   GREATEST(0, FLOOR((EXTRACT(EPOCH FROM (s.resolution_met_at - s.clock_start)) - s.paused_seconds) / 60))::BIGINT AS minutes
            FROM incident_sla s JOIN incidents i ON i.id = s.incident_id
            WHERE s.resolution_met_at IS NOT NULL AND s.resolution_met_at >= :since AND s.priority IN (:priorities)
            """.trimIndent(),
            MapSqlParameterSource().addValue("since", ts(since)).addValue("priorities", priorities.map { it.name })
        ) { rs, _ -> ResolvedDuration(Priority.valueOf(rs.getString("priority")), rs.getObject("category_id") as Long?, rs.getLong("minutes")) }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(risk: SlaRisk) {
        jdbc.update(
            """INSERT INTO incident_sla_risk (incident_id, raised_at, due_at, predicted_finish_at, basis, sample_size,
                   typical_remaining_min, load_factor, assignee_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            risk.incidentId, ts(risk.raisedAt), ts(risk.dueAt), ts(risk.predictedFinishAt), risk.basis.name, risk.sampleSize,
            risk.typicalRemainingMinutes, BigDecimal.valueOf(risk.loadFactor).setScale(2, RoundingMode.HALF_UP), risk.assigneeId
        )
    }

    fun find(incidentId: String): SlaRisk? = findAll(listOf(incidentId))[incidentId]

    fun findAll(incidentIds: Collection<String>): Map<String, SlaRisk> {
        if (incidentIds.isEmpty()) return emptyMap()
        return named.query(
            "SELECT * FROM incident_sla_risk WHERE incident_id IN (:ids)", MapSqlParameterSource("ids", incidentIds)
        ) { rs, _ -> rs.toRisk() }.associateBy { it.incidentId }
    }

    private fun ResultSet.toRisk() = SlaRisk(
        incidentId = getString("incident_id"),
        raisedAt = instant("raised_at")!!,
        dueAt = instant("due_at")!!,
        predictedFinishAt = instant("predicted_finish_at")!!,
        basis = RiskBasis.valueOf(getString("basis")),
        sampleSize = getInt("sample_size"),
        typicalRemainingMinutes = getLong("typical_remaining_min"),
        loadFactor = getBigDecimal("load_factor").toDouble(),
        assigneeId = getObject("assignee_id") as Long?
    )

    private fun ts(i: Instant) = i.atOffset(ZoneOffset.UTC)
}
