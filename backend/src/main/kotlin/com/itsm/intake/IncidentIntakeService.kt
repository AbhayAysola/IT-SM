package com.itsm.intake

import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.security.CurrentUser
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.ServiceCatalog
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class IncidentIntakeService(
    private val jdbc: JdbcTemplate,
    private val categories: CategoryCatalog,
    private val services: ServiceCatalog,
    private val audit: AuditPublisher,
    private val clock: Clock
) {

    @Transactional
    fun create(request: CreateIncidentRequest, user: CurrentUser): IncidentIntakeDto {
        val title = request.title.trim()
        val description = request.description.trim()
        if (title.isEmpty()) throw InvalidInput("Title must not be blank")
        if (description.isEmpty()) throw InvalidInput("Description must not be blank")

        // Validate category and service exist and are active
        val category = categories.requireActive(request.categoryId!!)
        val service = services.requireActive(request.serviceId!!)
        val priority = request.priority!!

        val now = now()
        val holder = GeneratedKeyHolder()
        jdbc.update({ con ->
            val ps = con.prepareStatement(
                """
                INSERT INTO incidents (title, description, state, reporter_id, category_id, service_id, priority, created_at, updated_at)
                VALUES (?, ?, 'CLASSIFIED', ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            )
            ps.setString(1, title)
            ps.setString(2, description)
            ps.setLong(3, user.id)
            ps.setLong(4, category.id)
            ps.setLong(5, service.id)
            ps.setString(6, priority.name)
            ps.setObject(7, now)
            ps.setObject(8, now)
            ps
        }, holder)

        val id = holder.keyList.firstOrNull()?.get("id")?.toString()
            ?: throw IllegalStateException("No id returned after incident insert")

        audit.publish(AuditableEvent(
            actor = AuditActor.of(user),
            action = "INCIDENT_CREATED", entityType = "INCIDENT", entityId = id,
            incidentId = id,
            description = "Incident created: $title",
            metadata = mapOf("categoryId" to category.id, "serviceId" to service.id, "priority" to priority.name)
        ))
        audit.publish(AuditableEvent(
            actor = AuditActor.of(user),
            action = "INCIDENT_CLASSIFIED", entityType = "INCIDENT", entityId = id,
            incidentId = id, previousState = "NEW", newState = "CLASSIFIED",
            description = "Incident classified on creation",
            metadata = mapOf("categoryId" to category.id, "serviceId" to service.id, "priority" to priority.name)
        ))

        return IncidentIntakeDto(id, title, description, "CLASSIFIED", category.id, service.id, priority.name, user.id, now, now)
    }

    @Transactional
    fun update(incidentId: String, request: UpdateIncidentRequest, user: CurrentUser): IncidentIntakeDto {
        val title = request.title.trim()
        val description = request.description.trim()
        if (title.isEmpty()) throw InvalidInput("Title must not be blank")
        if (description.isEmpty()) throw InvalidInput("Description must not be blank")

        val row = jdbc.query(
            "SELECT reporter_id, state, category_id, service_id, priority, created_at FROM incidents WHERE id = ?",
            { rs, _ ->
                listOf(
                    rs.getLong("reporter_id"),
                    rs.getString("state"),
                    rs.getLong("category_id"),
                    rs.getLong("service_id"),
                    rs.getString("priority"),
                    rs.getObject("created_at", java.time.OffsetDateTime::class.java)?.toInstant()
                )
            },
            incidentId
        ).firstOrNull() ?: throw ResourceNotFound("Incident", incidentId)

        val reporterId = row[0] as Long
        val state = row[1] as String
        val categoryId = row[2] as Long
        val serviceId = row[3] as Long
        val priority = row[4] as String
        val createdAt = row[5] as Instant

        if (state !in setOf("NEW", "CLASSIFIED")) {
            throw InvalidInput("Incident details can only be edited before it has been assigned")
        }
        if (!user.isAdmin && reporterId != user.id) {
            throw UnauthorizedOperation("Only the reporter or an admin can edit incident details")
        }

        val now = now()
        jdbc.update("UPDATE incidents SET title = ?, description = ?, updated_at = ? WHERE id = ?", title, description, now, incidentId)

        audit.publish(AuditableEvent(
            actor = AuditActor.of(user),
            action = "INCIDENT_UPDATED", entityType = "INCIDENT", entityId = incidentId,
            incidentId = incidentId,
            description = "Incident details updated",
            metadata = mapOf("changedFields" to listOf("title", "description"))
        ))

        return IncidentIntakeDto(incidentId, title, description, state, categoryId, serviceId, priority, reporterId, createdAt, now)
    }

    fun listCategories(): List<CatalogCategoryDto> =
        categories.listActive().map { CatalogCategoryDto(it.id, it.code, it.name) }

    fun listServices(): List<CatalogServiceDto> =
        services.listActive().map { CatalogServiceDto(it.id, it.name) }

    private fun now(): Instant = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
}
