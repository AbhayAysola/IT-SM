package com.itsm.common.event

import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections

/** Who performed the audited action. */
sealed interface AuditActor {
    data class UserActor(val userId: Long, val email: String, val role: Role) : AuditActor

    /** Background/automatic actions (SLA monitor, escalation job...). Name is UPPER_SNAKE_CASE. */
    data class SystemActor(val name: String) : AuditActor {
        init {
            require(NAME.matches(name)) { "SystemActor name must match $NAME" }
        }
    }

    companion object {
        private val NAME = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        fun of(user: CurrentUser): AuditActor = UserActor(user.id, user.email, user.role)
        fun system(name: String): AuditActor = SystemActor(name)
    }
}

/**
 * Immutable description of one audited action, published through
 * [com.itsm.common.spi.AuditPublisher] inside the caller's transaction.
 *
 * Violations of the rules below are PROGRAMMING errors, so they throw
 * [IllegalArgumentException] (-> 500), not a client-facing 4xx.
 *
 * Field rules
 *  - `action`:      UPPER_SNAKE_CASE verb phrase, 3-60 chars, e.g. `INCIDENT_ASSIGNED`.
 *  - `entityType`:  UPPER_SNAKE_CASE, 2-40 chars, e.g. `INCIDENT`, `USER`, `SLA_POLICY`.
 *  - `entityId`/`incidentId`: 1-40 / 1-30 chars of `[A-Za-z0-9_-]`.
 *  - `previousState`/`newState`: UPPER_SNAKE_CASE, 1-40 chars.
 *  - `description`: non-blank, at most 1000 chars. Human-readable; do not embed secrets.
 *
 * Metadata (stored as JSONB) — allowed value types ONLY:
 *   String (<= 500 chars), Boolean, Int, Long, Double (finite), BigDecimal,
 *   or a List of those scalars (<= 20 items, no nesting).
 *  Not allowed: null, Float/Short/Byte, Instant/Date (convert to an ISO string yourself),
 *  nested Maps, arbitrary objects, entities.
 *  Keys: camelCase `[a-z][A-Za-z0-9]{0,39}`, at most 20 entries.
 *  NEVER put passwords, tokens, or personal data beyond ids in metadata.
 *
 * The metadata map and its lists are defensively copied and unmodifiable.
 */
class AuditableEvent(
    val actor: AuditActor,
    action: String,
    entityType: String,
    entityId: String? = null,
    incidentId: String? = null,
    previousState: String? = null,
    newState: String? = null,
    description: String,
    metadata: Map<String, Any> = emptyMap(),
    val occurredAt: Instant = Instant.now()
) {
    val action: String = action.also { require(ACTION.matches(it)) { "Invalid audit action format" } }
    val entityType: String = entityType.also { require(ENTITY_TYPE.matches(it)) { "Invalid audit entityType format" } }
    val entityId: String? = entityId?.also { require(ENTITY_ID.matches(it)) { "Invalid audit entityId format" } }
    val incidentId: String? = incidentId?.also { require(INCIDENT_ID.matches(it)) { "Invalid audit incidentId format" } }
    val previousState: String? = previousState?.also { require(STATE.matches(it)) { "Invalid audit previousState format" } }
    val newState: String? = newState?.also { require(STATE.matches(it)) { "Invalid audit newState format" } }
    val description: String = description.also {
        require(it.isNotBlank() && it.length <= MAX_DESCRIPTION) { "Audit description must be 1..$MAX_DESCRIPTION chars" }
    }
    val metadata: Map<String, Any> = sanitize(metadata)

    override fun toString() = "AuditableEvent(action=$action, entityType=$entityType, entityId=$entityId, incidentId=$incidentId)"

    companion object {
        const val MAX_DESCRIPTION = 1000
        const val MAX_METADATA_ENTRIES = 20
        const val MAX_STRING = 500
        const val MAX_LIST = 20

        private val ACTION = Regex("^[A-Z][A-Z0-9_]{2,59}$")
        private val ENTITY_TYPE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        private val ENTITY_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
        private val INCIDENT_ID = Regex("^[A-Za-z0-9_-]{1,30}$")
        private val STATE = Regex("^[A-Z0-9_]{1,40}$")
        private val KEY = Regex("^[a-z][A-Za-z0-9]{0,39}$")

        private fun sanitize(source: Map<String, Any?>): Map<String, Any> {
            require(source.size <= MAX_METADATA_ENTRIES) { "Audit metadata allows at most $MAX_METADATA_ENTRIES entries" }
            val copy = LinkedHashMap<String, Any>(source.size)
            for ((key, value) in source) {
                require(KEY.matches(key)) { "Invalid audit metadata key format" }
                requireNotNull(value) { "Audit metadata value for '$key' must not be null" }
                copy[key] = when (value) {
                    is List<*> -> {
                        require(value.size <= MAX_LIST) { "Audit metadata list '$key' exceeds $MAX_LIST items" }
                        Collections.unmodifiableList(ArrayList(value.map { scalar(key, it) }))
                    }
                    else -> scalar(key, value)
                }
            }
            return Collections.unmodifiableMap(copy)
        }

        private fun scalar(key: String, value: Any?): Any = when (value) {
            is String -> value.also { require(it.length <= MAX_STRING) { "Audit metadata '$key' string exceeds $MAX_STRING chars" } }
            is Boolean, is Int, is Long -> value
            is Double -> value.also { require(it.isFinite()) { "Audit metadata '$key' must be a finite number" } }
            is BigDecimal -> value
            else -> throw IllegalArgumentException(
                "Audit metadata '$key' has unsupported type ${value?.javaClass?.simpleName}"
            )
        }
    }
}
