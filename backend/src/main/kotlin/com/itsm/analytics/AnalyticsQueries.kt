package com.itsm.analytics

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The read-only reporting queries behind the analytics dashboard (docs/architecture/p4.md section 5). Reporting is the
 * one place that reads across modules' tables (P2's incidents, P1's catalogs and technician profiles, P3's feedback, P4's
 * SLA and escalation logs); it only reads, and all of it is in this class. Every query is bounded by `[from, to)`, except
 * the "open now" figures, which are a snapshot.
 */
@Repository
class AnalyticsQueries(private val jdbc: NamedParameterJdbcTemplate) {

    private fun range(from: Instant, to: Instant) =
        MapSqlParameterSource().addValue("from", from.atOffset(ZoneOffset.UTC)).addValue("to", to.atOffset(ZoneOffset.UTC))

    // --- volume -------------------------------------------------------------------------------------------------------

    fun reported(from: Instant, to: Instant): Long =
        jdbc.queryForObject("SELECT count(*) FROM incidents WHERE created_at >= :from AND created_at < :to", range(from, to), Long::class.java)!!

    fun resolved(from: Instant, to: Instant): Long =
        jdbc.queryForObject("SELECT count(*) FROM incidents WHERE resolved_at >= :from AND resolved_at < :to", range(from, to), Long::class.java)!!

    /** Open incidents right now, by state and by priority. */
    fun openNow(): Pair<Map<String, Long>, Map<String, Long>> {
        val rows = jdbc.query(
            "SELECT state, priority, count(*) AS n FROM incidents WHERE state IN ($OPEN_STATES) GROUP BY state, priority",
            MapSqlParameterSource()
        ) { rs, _ -> Triple(rs.getString("state"), rs.getString("priority") ?: "NONE", rs.getLong("n")) }
        return rows.groupBy({ it.first }, { it.third }).mapValues { it.value.sum() } to
            rows.groupBy({ it.second }, { it.third }).mapValues { it.value.sum() }
    }

    // --- SLA compliance (incidents reported in the range) -------------------------------------------------------------

    fun slaTotal(from: Instant, to: Instant): SlaCounts =
        jdbc.query("SELECT $SLA_COUNTS FROM incidents i JOIN incident_sla s ON s.incident_id = i.id WHERE $IN_RANGE", range(from, to)) { rs, _ -> rs.slaCounts() }
            .firstOrNull() ?: SlaCounts.ZERO

    fun slaByPriority(from: Instant, to: Instant): List<SlaGroup> = jdbc.query(
        """SELECT i.priority AS gid, i.priority AS gname, $SLA_COUNTS FROM incidents i JOIN incident_sla s ON s.incident_id = i.id
           WHERE $IN_RANGE GROUP BY i.priority ORDER BY i.priority""",
        range(from, to)
    ) { rs, _ -> rs.group() }

    fun slaByService(from: Instant, to: Instant): List<SlaGroup> = jdbc.query(
        """SELECT i.service_id::text AS gid, COALESCE(v.name, 'Unknown service') AS gname, $SLA_COUNTS
           FROM incidents i JOIN incident_sla s ON s.incident_id = i.id LEFT JOIN it_services v ON v.id = i.service_id
           WHERE $IN_RANGE GROUP BY i.service_id, v.name ORDER BY count(*) DESC, gname LIMIT 50""",
        range(from, to)
    ) { rs, _ -> rs.group() }

    fun slaByCategory(from: Instant, to: Instant): List<SlaGroup> = jdbc.query(
        """SELECT i.category_id::text AS gid, COALESCE(c.name, 'Uncategorised') AS gname, $SLA_COUNTS
           FROM incidents i JOIN incident_sla s ON s.incident_id = i.id LEFT JOIN incident_categories c ON c.id = i.category_id
           WHERE $IN_RANGE GROUP BY i.category_id, c.name ORDER BY count(*) DESC, gname LIMIT 50""",
        range(from, to)
    ) { rs, _ -> rs.group() }

    // --- times (incidents reported in the range; elapsed wall-clock time, pauses included) ----------------------------

    fun timeToRespond(from: Instant, to: Instant): DurationStats = duration("first_response_at", from, to)
    fun timeToResolve(from: Instant, to: Instant): DurationStats = duration("resolved_at", from, to)

    private fun duration(column: String, from: Instant, to: Instant): DurationStats = jdbc.queryForObject(
        """SELECT count(*) AS n,
                  avg(extract(epoch FROM $column - created_at)) / 60 AS mean,
                  percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM $column - created_at)) / 60 AS p90
           FROM incidents WHERE created_at >= :from AND created_at < :to AND $column IS NOT NULL""",
        range(from, to)
    ) { rs, _ -> DurationStats(rs.getLong("n"), rs.double("mean"), rs.double("p90")) }!!

    // --- escalations --------------------------------------------------------------------------------------------------

    fun escalationsByTrigger(from: Instant, to: Instant): Map<String, Long> = jdbc.query(
        "SELECT trigger, count(*) AS n FROM incident_escalations WHERE created_at >= :from AND created_at < :to GROUP BY trigger",
        range(from, to)
    ) { rs, _ -> rs.getString("trigger") to rs.getLong("n") }.toMap()

    // --- per day (UTC days) -------------------------------------------------------------------------------------------

    fun perDay(from: Instant, to: Instant): Map<String, Map<LocalDate, Long>> {
        fun daily(sql: String) = jdbc.query(sql, range(from, to)) { rs, _ -> rs.getObject("day", LocalDate::class.java) to rs.getLong("n") }.toMap()
        val day = { col: String -> "($col AT TIME ZONE 'UTC')::date" }
        return mapOf(
            "reported" to daily("SELECT ${day("created_at")} AS day, count(*) AS n FROM incidents WHERE created_at >= :from AND created_at < :to GROUP BY 1"),
            "resolved" to daily("SELECT ${day("resolved_at")} AS day, count(*) AS n FROM incidents WHERE resolved_at >= :from AND resolved_at < :to GROUP BY 1"),
            "breaches" to daily(
                """SELECT day, count(*) AS n FROM (
                     SELECT ${day("response_breached_at")} AS day FROM incident_sla WHERE response_breached_at >= :from AND response_breached_at < :to
                     UNION ALL
                     SELECT ${day("resolution_breached_at")} FROM incident_sla WHERE resolution_breached_at >= :from AND resolution_breached_at < :to
                   ) b GROUP BY day"""
            ),
            "escalations" to daily("SELECT ${day("created_at")} AS day, count(*) AS n FROM incident_escalations WHERE created_at >= :from AND created_at < :to GROUP BY 1")
        )
    }

    // --- technicians --------------------------------------------------------------------------------------------------

    /** Usable technicians (active profile and account) with what they resolved and how it was rated in the range. */
    fun technicians(from: Instant, to: Instant): List<TechnicianBase> = jdbc.query(
        """
        SELECT u.id, u.name, p.max_workload,
               (SELECT count(*) FROM incidents i WHERE i.assignee_id = u.id AND i.resolved_at >= :from AND i.resolved_at < :to) AS resolved,
               (SELECT count(*) FROM incident_feedback f JOIN incidents i ON i.id = f.incident_id
                 WHERE i.assignee_id = u.id AND f.submitted_at >= :from AND f.submitted_at < :to) AS ratings,
               (SELECT avg(f.rating) FROM incident_feedback f JOIN incidents i ON i.id = f.incident_id
                 WHERE i.assignee_id = u.id AND f.submitted_at >= :from AND f.submitted_at < :to) AS avg_rating
        FROM users u JOIN technician_profiles p ON p.user_id = u.id
        WHERE u.active AND p.active AND u.role = 'TECHNICIAN'
        ORDER BY lower(u.name), u.id
        LIMIT 200
        """.trimIndent(),
        range(from, to)
    ) { rs, _ ->
        TechnicianBase(rs.getLong("id"), rs.getString("name"), rs.getInt("max_workload"), rs.getLong("resolved"), rs.getLong("ratings"), rs.double("avg_rating"))
    }

    data class TechnicianBase(val userId: Long, val name: String, val maxWorkload: Int, val resolved: Long, val ratings: Long, val averageRating: Double?)

    private fun ResultSet.slaCounts() = SlaCounts(
        tracked = getLong("tracked"),
        responseMet = getLong("response_met"),
        responseBreached = getLong("response_breached"),
        resolutionMet = getLong("resolution_met"),
        resolutionBreached = getLong("resolution_breached")
    )

    private fun ResultSet.group() = SlaGroup(getString("gid"), getString("gname") ?: "Unknown", slaCounts())
    private fun ResultSet.double(column: String): Double? = getObject(column)?.let { (it as Number).toDouble() }

    private companion object {
        const val OPEN_STATES = "'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'"
        const val IN_RANGE = "i.created_at >= :from AND i.created_at < :to AND s.policy_id IS NOT NULL"
        /** Breached wins over met (a breached clock that later stopped is still a breach), as on the SLA itself. */
        const val SLA_COUNTS = """count(*) AS tracked,
            count(*) FILTER (WHERE s.response_breached_at IS NULL AND s.response_status = 'MET') AS response_met,
            count(*) FILTER (WHERE s.response_breached_at IS NOT NULL) AS response_breached,
            count(*) FILTER (WHERE s.resolution_breached_at IS NULL AND s.resolution_status = 'MET') AS resolution_met,
            count(*) FILTER (WHERE s.resolution_breached_at IS NOT NULL) AS resolution_breached"""
    }
}
