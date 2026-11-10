package com.itsm.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.auth.JwtService
import com.itsm.common.model.Role
import com.itsm.common.security.ItsmUserDetails
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.ResultActions
import java.util.UUID

/** A real user row with a real JWT: requests go through the production filter chain and method security. */
data class Caller(val id: Long, val email: String, val role: Role, val bearer: String)

/** Shared helpers for the admin API integration tests (PostgreSQL Testcontainers). */
class AdminApiTestSupport(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val jdbc: JdbcTemplate
) {
    val json = ObjectMapper()

    fun caller(role: Role): Caller {
        val email = "${role.name.lowercase()}-${UUID.randomUUID()}@campus.example"
        val user = users.save(UserEntity(email = email, password = "not-used", name = "Test ${role.name}", role = role.name))
        return Caller(user.id, email, role, "Bearer " + jwt.generateToken(email))
    }

    /** Runs [block] with [caller] as the authenticated principal, for calling services directly. */
    fun <T> asCaller(caller: Caller, block: () -> T): T {
        val previous = SecurityContextHolder.getContext().authentication
        val principal = ItsmUserDetails(caller.id, caller.email, "", caller.role)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    fun body(result: ResultActions): JsonNode = json.readTree(result.andReturn().response.contentAsString)

    fun auditCount(): Long = jdbc.queryForObject("select count(*) from audit_events", Long::class.java)!!

    /** Audit rows for one entity, oldest first, with metadata as parsed JSON. */
    fun auditRows(entityType: String, entityId: Long): List<AuditRow> = jdbc.query(
        """select action, actor_type, actor_id, actor_role, previous_state, new_state, metadata::text
           from audit_events where entity_type = ? and entity_id = ? order by id""",
        { rs, _ ->
            AuditRow(
                action = rs.getString(1), actorType = rs.getString(2), actorId = rs.getLong(3),
                actorRole = rs.getString(4), previousState = rs.getString(5), newState = rs.getString(6),
                metadata = json.readTree(rs.getString(7))
            )
        },
        entityType, entityId.toString()
    )

    companion object {
        fun unique(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(8)
    }
}

data class AuditRow(
    val action: String,
    val actorType: String,
    val actorId: Long,
    val actorRole: String?,
    val previousState: String?,
    val newState: String?,
    val metadata: JsonNode
)
