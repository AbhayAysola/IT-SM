package com.itsm.audit

import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.security.Permission
import com.itsm.common.spi.IncidentAccessPolicy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** Raw (unvalidated) query-string values of the admin listing. */
data class AuditSearchParams(
    val action: String? = null,
    val actorType: String? = null,
    val actorId: String? = null,
    val actorRole: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val incidentId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val sort: String? = null,
    val dir: String? = null
)

/**
 * Read-only audit retrieval. Two entry points:
 *  - [list]/[get]: the admin viewer, requires AUDIT_VIEW (ADMIN).
 *  - [listForIncident]: one incident's trail. Who may see an incident is P2's decision ([IncidentAccessPolicy]); this
 *    class never re-implements it. If P2's bean is absent the answer is 503, never a fallback, so "provider missing"
 *    cannot be mistaken for "incident has no events" (that is a 200 with an empty page).
 */
@Service
class AuditQueryService(
    private val reader: AuditEventReader,
    private val currentUser: CurrentUserProvider,
    private val incidentAccess: ObjectProvider<IncidentAccessPolicy>
) {

    @Transactional(readOnly = true)
    fun list(page: PageQuery, params: AuditSearchParams): PageResponse<AuditEventResponse> {
        requireAuditView()
        val filter = parseFilter(params)
        val pageable = page.toPageable(sorting(params.sort, params.dir, Sort.Direction.DESC))
        requireReachableOffset(page)
        return PageResponse.from(reader.search(filter, pageable)) { AuditEventResponse.from(it) }
    }

    @Transactional(readOnly = true)
    fun get(eventId: String): AuditEventResponse {
        requireAuditView()
        val id = try {
            UUID.fromString(eventId.trim())
        } catch (_: IllegalArgumentException) {
            throw InvalidInput("Invalid audit event id", listOf(FieldError("id", "must be a UUID")))
        }
        val entity = reader.findByEventId(id) ?: throw ResourceNotFound("Audit event", id)
        return AuditEventResponse.from(entity)
    }

    @Transactional(readOnly = true)
    fun listForIncident(incidentId: String, page: PageQuery, sort: String?, dir: String?): PageResponse<IncidentAuditItem> {
        val user = currentUser.current()
        if (!INCIDENT_ID.matches(incidentId)) {
            throw InvalidInput("Invalid incident id", listOf(FieldError("incidentId", "must match [A-Za-z0-9_-]{1,30}")))
        }
        val policy = incidentAccess.getIfAvailable() ?: throw ItsmException(
            ErrorCode.SERVICE_UNAVAILABLE,
            "The audit trail of an incident cannot be shown right now: incident access checks are not available. Try again later."
        )
        policy.requireView(user, incidentId) // 404 unknown incident (P2), 403 not allowed
        // Question 2 (WHAT may this caller see of an incident they may see) is answered here, not by P2's policy:
        // AUDIT_VIEW holders get the full admin row, everyone else the reduced IncidentHistoryEntry.
        val full = user.has(Permission.AUDIT_VIEW)
        val requestedSort = sort?.trim()?.takeIf { it.isNotEmpty() }
        if (!full && requestedSort != null && requestedSort !in VIEWER_SORTS) {
            throw InvalidInput("Unsupported sort", listOf(FieldError("sort", "must be one of ${VIEWER_SORTS.sorted()}")))
        }
        val pageable = page.toPageable(sorting(sort, dir, Sort.Direction.ASC))
        requireReachableOffset(page)
        return PageResponse.from(reader.search(AuditEventFilter(incidentId = incidentId), pageable)) {
            if (full) AuditEventResponse.from(it) else IncidentHistoryEntry.from(it)
        }
    }

    private fun requireAuditView(): CurrentUser = currentUser.current().also { it.requirePermission(Permission.AUDIT_VIEW) }

    private fun requireReachableOffset(page: PageQuery) {
        if (page.page.toLong() * page.size > Int.MAX_VALUE) {
            throw InvalidInput("Invalid paging parameters", listOf(FieldError("page", "is out of range")))
        }
    }

    private fun parseFilter(p: AuditSearchParams): AuditEventFilter {
        val errors = mutableListOf<FieldError>()
        fun text(name: String, value: String?, format: Regex, hint: String): String? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!format.matches(v)) errors += FieldError(name, hint)
            return v
        }
        fun instant(name: String, value: String?): Instant? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return try {
                OffsetDateTime.parse(v).toInstant()
            } catch (_: DateTimeParseException) {
                errors += FieldError(name, "must be an ISO-8601 instant such as 2026-11-01T00:00:00Z")
                null
            }
        }

        val action = text("action", p.action, ACTION, "must be UPPER_SNAKE_CASE, 3-60 characters")
        val entityType = text("entityType", p.entityType, ENTITY_TYPE, "must be UPPER_SNAKE_CASE, 2-40 characters")
        val entityId = text("entityId", p.entityId, ENTITY_ID, "must match [A-Za-z0-9_-]{1,40}")
        val incidentId = text("incidentId", p.incidentId, INCIDENT_ID, "must match [A-Za-z0-9_-]{1,30}")
        val actorType = p.actorType?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?.also { if (it !in ACTOR_TYPES) errors += FieldError("actorType", "must be one of $ACTOR_TYPES") }
        val actorRole = p.actorRole?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?.also { r -> if (Role.entries.none { it.name == r }) errors += FieldError("actorRole", "must be one of ${Role.entries.map { it.name }}") }
        val actorId = p.actorId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 } ?: run { errors += FieldError("actorId", "must be a positive number"); null }
        }
        val from = instant("from", p.from)
        val to = instant("to", p.to)
        if (from != null && to != null && from.isAfter(to)) errors += FieldError("to", "must not be before from")

        if (errors.isNotEmpty()) throw InvalidInput("Invalid audit filter", errors)
        return AuditEventFilter(action, actorType, actorId, actorRole, entityType, entityId, incidentId, from, to)
    }

    /**
     * `id` always ends the ordering, in the primary direction, so paging is stable and newest-first can use
     * `idx_audit_events_occurred_at (occurred_at DESC, id DESC)`. Non-time sorts fall back to time before id.
     */
    private fun sorting(sort: String?, dir: String?, defaultDir: Sort.Direction): Sort {
        val key = sort?.trim()?.takeIf { it.isNotEmpty() } ?: "occurredAt"
        val property = SORTS[key]
            ?: throw InvalidInput("Unsupported sort", listOf(FieldError("sort", "must be one of ${SORTS.keys.sorted()}")))
        val direction = when (dir?.trim()?.lowercase()) {
            null, "" -> defaultDir
            "asc" -> Sort.Direction.ASC
            "desc" -> Sort.Direction.DESC
            else -> throw InvalidInput("Unsupported sort direction", listOf(FieldError("dir", "must be asc or desc")))
        }
        return if (property == "occurredAt") Sort.by(direction, "occurredAt", "id")
        else Sort.by(Sort.Order(direction, property), Sort.Order(Sort.Direction.DESC, "occurredAt"), Sort.Order(Sort.Direction.DESC, "id"))
    }

    companion object {
        /** Query-string sort key -> entity property. */
        val SORTS: Map<String, String> = mapOf(
            "occurredAt" to "occurredAt", "action" to "action", "entityType" to "entityType", "actorRole" to "actorRole"
        )
        /** Sort keys that only use fields an incident viewer receives. */
        private val VIEWER_SORTS = setOf("occurredAt", "action")
        private val ACTOR_TYPES = setOf("USER", "SYSTEM")
        // Same shapes AuditableEvent enforces on write; anything else cannot match a stored row.
        private val ACTION = Regex("^[A-Z][A-Z0-9_]{2,59}$")
        private val ENTITY_TYPE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        private val ENTITY_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
        private val INCIDENT_ID = Regex("^[A-Za-z0-9_-]{1,30}$")
    }
}
