package com.itsm.sla

import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.ItsmException
import com.itsm.common.security.CurrentUser
import com.itsm.common.security.CurrentUserProvider
import com.itsm.common.security.Permission
import com.itsm.common.spi.IncidentAccessPolicy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class SlaClockDto(
    val status: String,
    val targetMinutes: Int?,
    val dueAt: Instant?,
    val warningAt: Instant?,
    val metAt: Instant?,
    val breachedAt: Instant?
)

data class SlaPolicyRefDto(val id: Long, val scope: String)

/**
 * The SLA of one incident. `status` is `PENDING` until the monitor has evaluated the incident once (within one polling
 * interval of its classification), then the overall [SlaStatus]. Deadlines are absolute instants, so a client can count
 * down without asking again; `evaluatedAt` says how fresh the statuses are.
 */
data class SlaDto(
    val incidentId: String,
    val status: String,
    val priority: String?,
    val policy: SlaPolicyRefDto?,
    val noPolicyReason: String?,
    val response: SlaClockDto?,
    val resolution: SlaClockDto?,
    val pausedSince: Instant?,
    val pausedSeconds: Long,
    val evaluatedAt: Instant?
) {
    companion object {
        fun pending(incidentId: String) = SlaDto(incidentId, PENDING, null, null, null, null, null, null, 0, null)

        fun of(r: SlaRecord, evaluatedAt: Instant?) = SlaDto(
            incidentId = r.incidentId,
            status = r.status.name,
            priority = r.priority.name,
            policy = r.targets?.let { SlaPolicyRefDto(it.policyId, it.scope.name) },
            noPolicyReason = r.noPolicyReason?.name,
            response = SlaClockDto(r.responseStatus.name, r.targets?.responseMinutes, r.responseDueAt, r.responseWarningAt, r.responseMetAt, r.responseBreachedAt),
            resolution = SlaClockDto(r.resolutionStatus.name, r.targets?.resolutionMinutes, r.resolutionDueAt, r.resolutionWarningAt, r.resolutionMetAt, r.resolutionBreachedAt),
            pausedSince = r.pausedSince,
            pausedSeconds = r.pausedSeconds,
            evaluatedAt = evaluatedAt
        )

        const val PENDING = "PENDING"
    }
}

/**
 * Read side of SLA tracking. Visibility of a single incident is P2's [IncidentAccessPolicy]; nobody re-implements it.
 * It is looked up lazily, like the audit trail does, so the application still starts without it (the per-incident
 * endpoint then answers 503 for everyone instead of guessing).
 */
@Service
@Transactional(readOnly = true)
class SlaQueryService(private val store: SlaStore, private val access: ObjectProvider<IncidentAccessPolicy>) {

    /** 404 for an unknown incident, 403 when the caller may not see it (P2's policy decides), 503 without the policy. */
    fun forIncident(user: CurrentUser, incidentId: String): SlaDto {
        if (!INCIDENT_ID.matches(incidentId)) throw InvalidInput("Invalid incident id")
        val policy = access.getIfAvailable() ?: throw ItsmException(
            ErrorCode.SERVICE_UNAVAILABLE,
            "The SLA of an incident cannot be shown right now: incident access checks are not available. Try again later."
        )
        policy.requireView(user, incidentId)
        val record = store.find(incidentId) ?: return SlaDto.pending(incidentId)
        return SlaDto.of(record, store.lastEvaluatedAt(incidentId))
    }

    /** Admins see every incident; technicians only those currently assigned to them. */
    fun list(user: CurrentUser, statuses: Set<SlaStatus>, openOnly: Boolean, query: PageQuery): PageResponse<SlaDto> {
        val assignee = if (user.has(Permission.INCIDENT_VIEW_ALL)) null else user.id
        val (items, total) = store.list(statuses, openOnly, assignee, query.page, query.size)
        return PageResponse(
            items = items.map { SlaDto.of(it, null) },
            page = query.page,
            size = query.size,
            totalItems = total,
            totalPages = ((total + query.size - 1) / query.size).toInt()
        )
    }

    private companion object {
        val INCIDENT_ID = Regex("^[A-Za-z0-9_-]{1,30}$")
    }
}

@RestController
class SlaController(private val service: SlaQueryService, private val currentUser: CurrentUserProvider) {

    /** Any authenticated user who may see the incident (reporter, assignee, admin). */
    @GetMapping("${ApiPaths.INCIDENTS}/{incidentId}/sla")
    fun forIncident(@PathVariable incidentId: String): SlaDto = service.forIncident(currentUser.current(), incidentId)

    /**
     * SLA records for the caller's queue: `status` filters by overall status (repeatable, e.g. `status=BREACHED&status=WARNING`),
     * `open=true` (default) hides incidents that are verified or closed.
     */
    @GetMapping("${ApiPaths.API}/sla/incidents")
    @PreAuthorize("hasAnyAuthority('INCIDENT_VIEW_ALL', 'INCIDENT_VIEW_ASSIGNED')")
    fun list(
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "true") open: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): PageResponse<SlaDto> = service.list(currentUser.current(), parseStatuses(status), open, PageQuery(page, size))

    private fun parseStatuses(values: List<String>?): Set<SlaStatus> = values.orEmpty().map { v ->
        SlaStatus.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }
            ?: throw InvalidInput("Unknown SLA status '${v.take(20)}'. Expected one of ${SlaStatus.entries.joinToString()}.")
    }.toSet()
}
