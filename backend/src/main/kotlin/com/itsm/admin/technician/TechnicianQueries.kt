package com.itsm.admin.technician

import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserSummary
import com.itsm.common.model.Role
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * Read side of the technician module: plain SQL through JDBC, in the caller's transaction. It joins `users` (identity,
 * read only) and `incident_categories` (skill names) instead of calling other modules' repositories, so a page of N
 * technicians costs a constant number of queries (page rows, count, skills): no N+1. Row order is always fully
 * deterministic (explicit key + `user_id` tie-break).
 */
@Component
class TechnicianQueries(private val jdbc: NamedParameterJdbcTemplate) {

    private data class Row(
        val userId: Long, val name: String, val email: String, val userRole: String, val userActive: Boolean,
        val active: Boolean, val available: Boolean, val maxWorkload: Int, val experienceYears: Int,
        val teamLead: Boolean, val reportsTo: Long?, val version: Long,
        val createdAt: java.time.Instant, val updatedAt: java.time.Instant
    )

    private data class SummaryRow(
        val userId: Long, val name: String, val email: String, val available: Boolean, val maxWorkload: Int,
        val experienceYears: Int, val teamLead: Boolean, val reportsTo: Long?
    )

    private fun ResultSet.row() = Row(
        getLong("user_id"), getString("name"), getString("email"), getString("role"), getBoolean("user_active"),
        getBoolean("active"), getBoolean("available"), getInt("max_workload"), getInt("experience_years"),
        getBoolean("is_team_lead"), getObject("reports_to_user_id", java.lang.Long::class.java)?.toLong(), getLong("version"),
        getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant()
    )

    private val select = """
        select p.user_id, u.name, u.email, u.role, u.active as user_active, p.active, p.available, p.max_workload,
               p.experience_years, p.is_team_lead, p.reports_to_user_id, p.version, p.created_at, p.updated_at
        from technician_profiles p join users u on u.id = p.user_id"""

    fun get(userId: Long): TechnicianResponse? {
        val row = jdbc.query("$select where p.user_id = :id", MapSqlParameterSource("id", userId)) { rs, _ -> rs.row() }.firstOrNull() ?: return null
        return respond(listOf(row), skillsFor(listOf(userId))).single()
    }

    fun page(
        page: PageQuery, pattern: String?, active: Boolean?, available: Boolean?, teamLead: Boolean?, categoryId: Long?,
        orderBy: String
    ): PageResponse<TechnicianResponse> {
        val where = """
            where (cast(:active as boolean) is null or p.active = cast(:active as boolean))
              and (cast(:available as boolean) is null or p.available = cast(:available as boolean))
              and (cast(:teamLead as boolean) is null or p.is_team_lead = cast(:teamLead as boolean))
              and (cast(:categoryId as bigint) is null or exists (
                     select 1 from technician_skills s where s.user_id = p.user_id and s.category_id = cast(:categoryId as bigint)))
              and (cast(:pattern as text) is null
                   or lower(u.email) like cast(:pattern as text) escape '\' or lower(u.name) like cast(:pattern as text) escape '\')"""
        val params = MapSqlParameterSource()
            .addValue("active", active).addValue("available", available).addValue("teamLead", teamLead)
            .addValue("categoryId", categoryId).addValue("pattern", pattern)
            .addValue("limit", page.size).addValue("offset", page.page.toLong() * page.size)
        val total = jdbc.queryForObject("select count(*) from technician_profiles p join users u on u.id = p.user_id $where", params, Long::class.java) ?: 0L
        val rows = jdbc.query("$select $where order by $orderBy, p.user_id limit :limit offset :offset", params) { rs, _ -> rs.row() }
        val items = respond(rows, skillsFor(rows.map { it.userId }))
        return PageResponse(items, page.page, page.size, total, ((total + page.size - 1) / page.size).toInt())
    }

    fun existsReportsTo(userId: Long): Boolean = (jdbc.queryForObject(
        "select count(*) from technician_profiles where reports_to_user_id = :id", MapSqlParameterSource("id", userId), Long::class.java
    ) ?: 0L) > 0

    /** True if making [managerId] the manager of [userId] would close a loop (the manager's own chain reaches [userId]). */
    fun wouldCycle(userId: Long, managerId: Long): Boolean = (jdbc.queryForObject(
        """with recursive up(user_id, reports_to_user_id) as (
               select user_id, reports_to_user_id from technician_profiles where user_id = :manager
               union
               select p.user_id, p.reports_to_user_id from technician_profiles p join up on p.user_id = up.reports_to_user_id)
           select count(*) from up where user_id = :user""",
        MapSqlParameterSource("manager", managerId).addValue("user", userId), Long::class.java
    ) ?: 0L) > 0

    /** Manager row facts needed for friendly validation: (active, teamLead), or null if there is no profile. */
    fun managerState(managerId: Long): Pair<Boolean, Boolean>? = jdbc.query(
        "select active, is_team_lead from technician_profiles where user_id = :id", MapSqlParameterSource("id", managerId)
    ) { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }.firstOrNull()

    private fun skillsFor(userIds: List<Long>): Map<Long, List<SkillResponse>> {
        if (userIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, MutableList<SkillResponse>>()
        jdbc.query(
            """select s.user_id, c.id, c.code, c.name, c.active, s.proficiency
               from technician_skills s join incident_categories c on c.id = s.category_id
               where s.user_id in (:ids) order by s.user_id, lower(c.name), c.id""",
            MapSqlParameterSource("ids", userIds)
        ) { rs ->
            result.getOrPut(rs.getLong(1)) { mutableListOf() } +=
                SkillResponse(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6))
        }
        return result
    }

    private fun respond(rows: List<Row>, skills: Map<Long, List<SkillResponse>>) = rows.map {
        TechnicianResponse(
            userId = it.userId, name = it.name, email = it.email, userRole = it.userRole, userActive = it.userActive,
            active = it.active, available = it.available, maxWorkload = it.maxWorkload, experienceYears = it.experienceYears,
            isTeamLead = it.teamLead, reportsToUserId = it.reportsTo, skills = skills[it.userId].orEmpty(),
            version = it.version, createdAt = it.createdAt, updatedAt = it.updatedAt
        )
    }

    // --- UserDirectory read model ---------------------------------------------------------------------------------

    /**
     * Usable technicians only: ACTIVE profile, ACTIVE user, role TECHNICIAN. [onlyAvailable] adds the administrator's
     * `available` flag; [categoryId] requires a skill for that (active) category. Skills reported are those of ACTIVE
     * categories. Ordered by name, then user id.
     */
    fun summaries(userId: Long?, onlyAvailable: Boolean, categoryId: Long?): List<TechnicianSummary> {
        val params = MapSqlParameterSource().addValue("userId", userId).addValue("available", onlyAvailable).addValue("categoryId", categoryId)
        val rows = jdbc.query(
            """select p.user_id, u.name, u.email, p.available, p.max_workload, p.experience_years, p.is_team_lead, p.reports_to_user_id
               from technician_profiles p join users u on u.id = p.user_id
               where p.active and u.active and u.role = 'TECHNICIAN'
                 and (cast(:userId as bigint) is null or p.user_id = cast(:userId as bigint))
                 and (not cast(:available as boolean) or p.available)
                 and (cast(:categoryId as bigint) is null or exists (
                        select 1 from technician_skills s join incident_categories c on c.id = s.category_id
                        where s.user_id = p.user_id and s.category_id = cast(:categoryId as bigint) and c.active))
               order by lower(u.name), u.name, p.user_id""",
            params
        ) { rs, _ ->
            SummaryRow(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getInt(5), rs.getInt(6), rs.getBoolean(7),
                rs.getObject(8, java.lang.Long::class.java)?.toLong()
            )
        }
        if (rows.isEmpty()) return emptyList()
        val skills = HashMap<Long, MutableMap<Long, Int>>()
        jdbc.query(
            """select s.user_id, s.category_id, s.proficiency from technician_skills s
               join incident_categories c on c.id = s.category_id where c.active and s.user_id in (:ids)""",
            MapSqlParameterSource("ids", rows.map { it.userId })
        ) { rs -> skills.getOrPut(rs.getLong(1)) { LinkedHashMap() }[rs.getLong(2)] = rs.getInt(3) }
        return rows.map {
            TechnicianSummary(
                userId = it.userId, name = it.name, email = it.email, available = it.available, maxWorkload = it.maxWorkload,
                experienceYears = it.experienceYears, isTeamLead = it.teamLead, reportsToUserId = it.reportsTo,
                skills = skills[it.userId]?.toSortedMap() ?: emptyMap()
            )
        }
    }

    /**
     * The reporting line above [technicianId], nearest first, in ONE query. Managers whose profile or account is inactive are
     * skipped (the walk continues above them). The technician themself is excluded; a loop (impossible by the V16 trigger) would
     * stop at [MAX_DEPTH].
     */
    fun escalationChain(technicianId: Long): List<UserSummary> = jdbc.query(
        """with recursive chain(user_id, reports_to_user_id, depth) as (
               select p.user_id, p.reports_to_user_id, 0 from technician_profiles p where p.user_id = :id
               union all
               select m.user_id, m.reports_to_user_id, c.depth + 1
               from chain c join technician_profiles m on m.user_id = c.reports_to_user_id
               where c.depth < $MAX_DEPTH)
           select u.id, u.email, u.name, u.role, u.active
           from chain c
           join technician_profiles p on p.user_id = c.user_id
           join users u on u.id = c.user_id
           where c.depth > 0 and p.active and u.active and u.role = 'TECHNICIAN'
           order by c.depth""",
        MapSqlParameterSource("id", technicianId)
    ) { rs, _ -> UserSummary(rs.getLong(1), rs.getString(2), rs.getString(3), Role.valueOf(rs.getString(4)), rs.getBoolean(5)) }

    private companion object {
        const val MAX_DEPTH = 50
    }
}
