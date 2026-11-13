package com.itsm.sla

import com.itsm.common.model.Priority
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

/** Persistence of [SlaRecord] in `incident_sla` (V40). Writes are `MANDATORY`: they belong to the monitor's transaction. */
@Repository
class SlaStore(private val jdbc: JdbcTemplate, private val named: NamedParameterJdbcTemplate) {

    fun find(incidentId: String): SlaRecord? =
        jdbc.query("SELECT * FROM incident_sla WHERE incident_id = ?", { rs, _ -> rs.toRecord() }, incidentId).firstOrNull()

    fun findAll(incidentIds: Collection<String>): Map<String, SlaRecord> {
        if (incidentIds.isEmpty()) return emptyMap()
        return named.query(
            "SELECT * FROM incident_sla WHERE incident_id IN (:ids)", MapSqlParameterSource("ids", incidentIds)
        ) { rs, _ -> rs.toRecord() }.associateBy { it.incidentId }
    }

    /**
     * Inserts [record] when [previous] is null, otherwise updates it if nobody else changed it since [previous] was read.
     * Returns the stored record with its new version.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun save(record: SlaRecord, previous: SlaRecord?, now: Instant): SlaRecord {
        val params = record.toParams().addValue("now", ts(now))
        if (previous == null) {
            named.update("INSERT INTO incident_sla ($COLUMNS, version, created_at, updated_at) VALUES ($VALUES, 0, :now, :now)", params)
            return record.copy(version = 0)
        }
        val updated = named.update(
            "UPDATE incident_sla SET ($COLUMNS, version, updated_at) = ($VALUES, version + 1, :now) " +
                "WHERE incident_id = :incidentId AND version = :expectedVersion",
            params.addValue("expectedVersion", previous.version)
        )
        if (updated != 1) throw OptimisticLockingFailureException("SLA of incident ${record.incidentId} changed concurrently")
        return record.copy(version = previous.version + 1)
    }

    /**
     * Takes the monitor's transaction-scoped advisory lock, so two application instances never evaluate at the same time
     * (which would publish every event twice). Released automatically at commit or rollback. False if another holds it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun tryMonitorLock(): Boolean = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, MONITOR_LOCK_KEY)!!

    /**
     * One page of SLA records, newest deadline pressure first (breached, then nearest resolution deadline).
     * [assigneeId] restricts to incidents currently assigned to that user: the only other read of `incidents` in P4
     * besides [IncidentSnapshotReader].
     */
    fun list(statuses: Set<SlaStatus>, openOnly: Boolean, assigneeId: Long?, page: Int, size: Int): Pair<List<SlaRecord>, Long> {
        val where = buildList {
            if (statuses.isNotEmpty()) add("s.status IN (:statuses)")
            if (openOnly) add("NOT s.complete")
            if (assigneeId != null) add("i.assignee_id = :assignee")
        }.joinToString(" AND ").ifEmpty { "TRUE" }
        val params = MapSqlParameterSource()
            .addValue("statuses", statuses.map { it.name }.ifEmpty { listOf("") })
            .addValue("assignee", assigneeId)
            .addValue("limit", size)
            .addValue("offset", page.toLong() * size)
        val from = "FROM incident_sla s JOIN incidents i ON i.id = s.incident_id WHERE $where"
        val total = named.queryForObject("SELECT count(*) $from", params, Long::class.java)!!
        val items = named.query(
            "SELECT s.* $from ORDER BY (s.status = 'BREACHED') DESC, s.resolution_due_at NULLS LAST, s.incident_id LIMIT :limit OFFSET :offset",
            params
        ) { rs, _ -> rs.toRecord() }
        return items to total
    }

    private fun SlaRecord.toParams(): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("incidentId", incidentId)
        .addValue("priority", priority.name)
        .addValue("serviceId", serviceId)
        .addValue("clockStart", ts(clockStart))
        .addValue("policyId", targets?.policyId)
        .addValue("policyScope", targets?.scope?.name)
        .addValue("responseMinutes", targets?.responseMinutes)
        .addValue("resolutionMinutes", targets?.resolutionMinutes)
        .addValue("warningPct", targets?.warningThresholdPct)
        .addValue("noPolicyReason", noPolicyReason?.name)
        .addValue("responseDueAt", ts(responseDueAt))
        .addValue("responseWarningAt", ts(responseWarningAt))
        .addValue("responseWarnedAt", ts(responseWarnedAt))
        .addValue("responseMetAt", ts(responseMetAt))
        .addValue("responseBreachedAt", ts(responseBreachedAt))
        .addValue("resolutionDueAt", ts(resolutionDueAt))
        .addValue("resolutionWarningAt", ts(resolutionWarningAt))
        .addValue("resolutionWarnedAt", ts(resolutionWarnedAt))
        .addValue("resolutionMetAt", ts(resolutionMetAt))
        .addValue("resolutionBreachedAt", ts(resolutionBreachedAt))
        .addValue("pausedSince", ts(pausedSince))
        .addValue("pausedSeconds", pausedSeconds)
        .addValue("responseStatus", responseStatus.name)
        .addValue("resolutionStatus", resolutionStatus.name)
        .addValue("status", status.name)
        .addValue("complete", complete)

    private fun ResultSet.toRecord(): SlaRecord {
        val policyId = getObject("policy_id") as Long?
        return SlaRecord(
            incidentId = getString("incident_id"),
            priority = Priority.valueOf(getString("priority")),
            serviceId = getLong("service_id"),
            clockStart = instant("clock_start")!!,
            targets = policyId?.let {
                SlaTargets(
                    policyId = it,
                    scope = PolicyScope.valueOf(getString("policy_scope")),
                    responseMinutes = getInt("response_minutes"),
                    resolutionMinutes = getInt("resolution_minutes"),
                    warningThresholdPct = getInt("warning_threshold_pct")
                )
            },
            noPolicyReason = getString("no_policy_reason")?.let(NoPolicyReason::valueOf),
            responseDueAt = instant("response_due_at"),
            responseWarningAt = instant("response_warning_at"),
            responseWarnedAt = instant("response_warned_at"),
            responseMetAt = instant("response_met_at"),
            responseBreachedAt = instant("response_breached_at"),
            resolutionDueAt = instant("resolution_due_at"),
            resolutionWarningAt = instant("resolution_warning_at"),
            resolutionWarnedAt = instant("resolution_warned_at"),
            resolutionMetAt = instant("resolution_met_at"),
            resolutionBreachedAt = instant("resolution_breached_at"),
            pausedSince = instant("paused_since"),
            pausedSeconds = getLong("paused_seconds"),
            responseStatus = ClockStatus.valueOf(getString("response_status")),
            resolutionStatus = ClockStatus.valueOf(getString("resolution_status")),
            status = SlaStatus.valueOf(getString("status")),
            complete = getBoolean("complete"),
            version = getLong("version")
        )
    }

    /** When the record was last written, for the API ("evaluated at"). */
    fun lastEvaluatedAt(incidentId: String): Instant? = jdbc.query(
        "SELECT updated_at FROM incident_sla WHERE incident_id = ?", { rs, _ -> rs.instant("updated_at") }, incidentId
    ).firstOrNull()

    private fun ts(i: Instant?) = i?.atOffset(ZoneOffset.UTC)

    companion object {
        /** Arbitrary, fixed key of the monitor's advisory lock ("P4" "SLA"). */
        const val MONITOR_LOCK_KEY = 0x5034_534C_41L

        private val COLUMN_TO_PARAM = linkedMapOf(
            "incident_id" to "incidentId", "priority" to "priority", "service_id" to "serviceId", "clock_start" to "clockStart",
            "policy_id" to "policyId", "policy_scope" to "policyScope", "response_minutes" to "responseMinutes",
            "resolution_minutes" to "resolutionMinutes", "warning_threshold_pct" to "warningPct", "no_policy_reason" to "noPolicyReason",
            "response_due_at" to "responseDueAt", "response_warning_at" to "responseWarningAt", "response_warned_at" to "responseWarnedAt",
            "response_met_at" to "responseMetAt", "response_breached_at" to "responseBreachedAt",
            "resolution_due_at" to "resolutionDueAt", "resolution_warning_at" to "resolutionWarningAt",
            "resolution_warned_at" to "resolutionWarnedAt", "resolution_met_at" to "resolutionMetAt",
            "resolution_breached_at" to "resolutionBreachedAt", "paused_since" to "pausedSince", "paused_seconds" to "pausedSeconds",
            "response_status" to "responseStatus", "resolution_status" to "resolutionStatus", "status" to "status", "complete" to "complete"
        )
        private val COLUMNS = COLUMN_TO_PARAM.keys.joinToString(", ")
        private val VALUES = COLUMN_TO_PARAM.values.joinToString(", ") { ":$it" }
    }
}
