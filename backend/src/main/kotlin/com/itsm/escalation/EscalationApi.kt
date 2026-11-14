package com.itsm.escalation

import com.itsm.common.api.ApiPaths
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.FieldError
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.security.Permission
import com.itsm.common.spi.AuditPublisher
import com.itsm.common.spi.IncidentAccessPolicy
import com.itsm.common.spi.UserDirectory
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// --- DTOs ------------------------------------------------------------------------------------------------------------

data class EscalationSettingsResponse(
    val unassignedMinutes: Int,
    val secondLevelPct: Int,
    val adminLevelPct: Int,
    val version: Long,
    val updatedAt: Instant?,
    val updatedByUserId: Long?
) {
    companion object {
        fun of(s: EscalationSettings) = EscalationSettingsResponse(s.unassignedMinutes, s.secondLevelPct, s.adminLevelPct, s.version, s.updatedAt, s.updatedByUserId)
    }
}

data class UpdateEscalationSettingsRequest(
    @field:NotNull val unassignedMinutes: Int? = null,
    @field:NotNull val secondLevelPct: Int? = null,
    @field:NotNull val adminLevelPct: Int? = null,
    @field:NotNull val version: Long? = null
)

data class EscalationTargetDto(val id: Long, val name: String)

data class EscalationDto(
    val trigger: String,
    val tier: String,
    val description: String,
    val targets: List<EscalationTargetDto>,
    val assigneeId: Long?,
    val createdAt: Instant
)

// --- services --------------------------------------------------------------------------------------------------------

/** Reads and replaces the escalation settings. Every change is audited with its before and after values. */
@Service
class EscalationSettingsService(private val store: EscalationStore, private val audit: AuditPublisher, private val clock: Clock) {

    @Transactional(readOnly = true)
    fun get(): EscalationSettingsResponse = EscalationSettingsResponse.of(store.settings())

    /** 400 with field errors for invalid values, 409 for a stale version; an unchanged request is a no-op (no audit). */
    @Transactional
    fun update(request: UpdateEscalationSettingsRequest, user: CurrentUser): EscalationSettingsResponse {
        val unassigned = request.unassignedMinutes!!
        val second = request.secondLevelPct!!
        val admin = request.adminLevelPct!!
        val errors = EscalationSettings.validate(unassigned, second, admin)
        if (errors.isNotEmpty()) throw InvalidInput("Invalid escalation settings", errors.map { (f, m) -> FieldError(f, m) })

        val current = store.settings()
        if (request.version != current.version) throw OptimisticLockingFailureException("Escalation settings changed concurrently")
        if (current.unassignedMinutes == unassigned && current.secondLevelPct == second && current.adminLevelPct == admin) {
            return EscalationSettingsResponse.of(current)
        }

        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val saved = store.updateSettings(unassigned, second, admin, current.version, user.id, now)
        audit.publish(
            AuditableEvent(
                actor = AuditActor.of(user), action = "ESCALATION_SETTINGS_UPDATED", entityType = "ESCALATION_SETTINGS", entityId = "1",
                description = "Escalation settings updated",
                metadata = mapOf(
                    "unassignedMinutesBefore" to current.unassignedMinutes, "unassignedMinutesAfter" to unassigned,
                    "secondLevelPctBefore" to current.secondLevelPct, "secondLevelPctAfter" to second,
                    "adminLevelPctBefore" to current.adminLevelPct, "adminLevelPctAfter" to admin
                )
            )
        )
        return EscalationSettingsResponse.of(saved)
    }
}

/**
 * The escalation history of one incident, for IT staff (technicians and admins) who may see the incident. Reporters do
 * not see it: it names the people an incident was escalated to. Visibility of the incident itself is P2's
 * [IncidentAccessPolicy], looked up lazily (503 without it), as the SLA and audit endpoints do.
 */
@Service
@Transactional(readOnly = true)
class IncidentEscalationQuery(
    private val store: EscalationStore,
    private val access: ObjectProvider<IncidentAccessPolicy>,
    private val directory: UserDirectory
) {
    fun forIncident(user: CurrentUser, incidentId: String): List<EscalationDto> {
        if (!INCIDENT_ID.matches(incidentId)) throw InvalidInput("Invalid incident id")
        val policy = access.getIfAvailable() ?: throw ItsmException(
            ErrorCode.SERVICE_UNAVAILABLE,
            "The escalations of an incident cannot be shown right now: incident access checks are not available. Try again later."
        )
        policy.requireView(user, incidentId)
        if (!(user.isAdmin || user.has(Permission.INCIDENT_WORK))) throw UnauthorizedOperation("Escalations are visible to IT staff only")

        val escalations = store.forIncident(incidentId)
        val names = escalations.flatMap { it.targetUserIds }.distinct().associateWith { id -> directory.findById(id)?.name ?: "User #$id" }
        return escalations.map { e ->
            EscalationDto(
                trigger = e.trigger.name, tier = e.tier.name, description = e.trigger.description,
                targets = e.targetUserIds.map { EscalationTargetDto(it, names.getValue(it)) },
                assigneeId = e.assigneeId, createdAt = e.createdAt
            )
        }
    }

    private companion object {
        val INCIDENT_ID = Regex("^[A-Za-z0-9_-]{1,30}$")
    }
}

// --- controllers -----------------------------------------------------------------------------------------------------

@RestController
class EscalationController(
    private val settings: EscalationSettingsService,
    private val escalations: IncidentEscalationQuery,
    private val currentUser: CurrentUserProvider
) {
    @GetMapping("${ApiPaths.ADMIN}/escalation-settings")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun getSettings(): EscalationSettingsResponse = settings.get()

    @PutMapping("${ApiPaths.ADMIN}/escalation-settings")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun updateSettings(@Valid @RequestBody body: UpdateEscalationSettingsRequest): EscalationSettingsResponse =
        settings.update(body, currentUser.current())

    @GetMapping("${ApiPaths.INCIDENTS}/{incidentId}/escalations")
    fun forIncident(@PathVariable incidentId: String): List<EscalationDto> = escalations.forIncident(currentUser.current(), incidentId)
}
