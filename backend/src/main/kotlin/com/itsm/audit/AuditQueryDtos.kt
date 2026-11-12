package com.itsm.audit

import com.itsm.common.event.AuditableEvent
import java.time.Instant
import java.util.Collections
import java.util.UUID

/**
 * Who performed an audited action. Mirrors what the table stores and nothing more: a user is identified by stable id
 * and role (never email or name); a system actor by its job name. `id` is withheld (null) from callers who only hold
 * incident access and not the AUDIT_VIEW permission.
 */
data class AuditActorResponse(
    val type: String,
    val id: Long?,
    val role: String?,
    val name: String?
)

/** Anything the incident trail endpoint may return; which shape a caller gets depends on AUDIT_VIEW, see [IncidentHistoryEntry]. */
sealed interface IncidentAuditItem

/** Read model of one audit row. `id` is the row's public `event_id` (the internal bigint is never exposed). */
data class AuditEventResponse(
    val id: UUID,
    val occurredAt: Instant,
    val actor: AuditActorResponse,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val incidentId: String?,
    val previousState: String?,
    val newState: String?,
    val description: String,
    val metadata: Map<String, Any>
) : IncidentAuditItem {
    companion object {
        fun from(entity: AuditEventEntity, includeActorId: Boolean = true) = AuditEventResponse(
            id = entity.eventId,
            occurredAt = entity.occurredAt,
            actor = AuditActorResponse(
                type = entity.actorType,
                id = if (includeActorId) entity.actorId else null,
                role = entity.actorRole,
                name = entity.actorName
            ),
            action = entity.action,
            entityType = entity.entityType,
            entityId = entity.entityId,
            incidentId = entity.incidentId,
            previousState = entity.previousState,
            newState = entity.newState,
            description = entity.description,
            metadata = SafeMetadata.of(entity.metadata)
        )
    }
}

/** Actor as an incident viewer sees it: kind and role only. No id, no system job name. */
data class IncidentHistoryActor(val type: String, val role: String?)

/**
 * The reduced projection of an audit row for callers who may see an incident but do not hold AUDIT_VIEW. It is a
 * separate type (not the full DTO with fields nulled), so a field can only reach such a caller by being added here
 * on purpose. Deliberately absent: actor id/name, entity type/id, `description` (free text written by many
 * producers) and `metadata` (producer-defined keys). Unlock those only through a product decision, see
 * docs/architecture/audit-retrieval.md.
 */
data class IncidentHistoryEntry(
    val id: UUID,
    val occurredAt: Instant,
    val action: String,
    val actor: IncidentHistoryActor,
    val previousState: String?,
    val newState: String?,
    val incidentId: String?
) : IncidentAuditItem {
    companion object {
        fun from(entity: AuditEventEntity) = IncidentHistoryEntry(
            id = entity.eventId,
            occurredAt = entity.occurredAt,
            action = entity.action,
            actor = IncidentHistoryActor(entity.actorType, entity.actorRole),
            previousState = entity.previousState,
            newState = entity.newState,
            incidentId = entity.incidentId
        )
    }
}

/**
 * Re-establishes the documented metadata shape ([AuditableEvent]: scalars or flat lists of scalars) on the way OUT,
 * so a row that was written by anything other than the publisher can never hand the client nested objects, nulls or
 * exotic types. Anything outside the shape is dropped (never rewritten); keys come back sorted so output is
 * deterministic. The result is unmodifiable.
 *
 * Defence in depth for the admin view: the publisher contract forbids credentials and personal data, but rows are
 * only as good as their producers, so entries whose KEY names a secret/credential/contact detail, or whose string
 * VALUE looks like an e-mail address or a JWT, are dropped too. Stored rows are never touched.
 */
object SafeMetadata {
    fun of(raw: Map<String, Any?>?): Map<String, Any> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (key in raw.keys.sorted()) {
            if (SENSITIVE_KEY.containsMatchIn(key)) continue
            when (val value = raw[key]) {
                is List<*> -> out[key] = Collections.unmodifiableList(value.filter(::isSafeScalar).map { it as Any })
                else -> if (isSafeScalar(value)) out[key] = value as Any
            }
        }
        return Collections.unmodifiableMap(out)
    }

    private fun isScalar(value: Any?) = value is String || value is Boolean || value is Number

    private fun isSafeScalar(value: Any?) =
        isScalar(value) && !(value is String && (EMAIL_VALUE.containsMatchIn(value) || JWT_VALUE.containsMatchIn(value)))

    private val SENSITIVE_KEY = Regex("password|passwd|secret|token|credential|hash|jwt|authorization|apikey|e-?mail|phone", RegexOption.IGNORE_CASE)
    private val EMAIL_VALUE = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    private val JWT_VALUE = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
}
