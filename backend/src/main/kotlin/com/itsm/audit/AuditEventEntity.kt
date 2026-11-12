package com.itsm.audit

import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One persisted [AuditableEvent]. Append-only: `@Immutable` makes Hibernate ignore changes and refuse deletes of
 * loaded instances, every property is a `val`, and the table's trigger rejects UPDATE/DELETE/TRUNCATE in SQL.
 *
 * Not part of any SPI: other modules never see this class.
 */
@Entity
@Immutable
@Table(name = "audit_events")
class AuditEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID = UUID.randomUUID(),

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant = Instant.EPOCH,

    @Column(name = "actor_type", nullable = false, updatable = false, length = 10)
    val actorType: String = "",

    @Column(name = "actor_id", updatable = false)
    val actorId: Long? = null,

    @Column(name = "actor_role", updatable = false, length = 20)
    val actorRole: String? = null,

    @Column(name = "actor_name", updatable = false, length = 40)
    val actorName: String? = null,

    @Column(nullable = false, updatable = false, length = 60)
    val action: String = "",

    @Column(name = "entity_type", nullable = false, updatable = false, length = 40)
    val entityType: String = "",

    @Column(name = "entity_id", updatable = false, length = 40)
    val entityId: String? = null,

    @Column(name = "incident_id", updatable = false, length = 30)
    val incidentId: String? = null,

    @Column(name = "previous_state", updatable = false, length = 40)
    val previousState: String? = null,

    @Column(name = "new_state", updatable = false, length = 40)
    val newState: String? = null,

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    val description: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    val metadata: Map<String, Any> = emptyMap()
) {
    // `recorded_at` is filled by the column default and is not mapped: nothing reads it until the retrieval batch.

    companion object {
        /**
         * Maps the shared contract onto the table. The actor's email is deliberately NOT stored: the stable
         * user id and role identify the actor without keeping personal data in an append-only table.
         */
        fun from(event: AuditableEvent): AuditEventEntity {
            val actor = event.actor
            return AuditEventEntity(
                occurredAt = event.occurredAt,
                actorType = if (actor is AuditActor.UserActor) "USER" else "SYSTEM",
                actorId = (actor as? AuditActor.UserActor)?.userId,
                actorRole = (actor as? AuditActor.UserActor)?.role?.name,
                actorName = (actor as? AuditActor.SystemActor)?.name,
                action = event.action,
                entityType = event.entityType,
                entityId = event.entityId,
                incidentId = event.incidentId,
                previousState = event.previousState,
                newState = event.newState,
                description = event.description,
                metadata = event.metadata
            )
        }
    }
}
