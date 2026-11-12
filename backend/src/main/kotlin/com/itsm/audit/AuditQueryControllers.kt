package com.itsm.audit

import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Admin audit viewer API. GET only, by design: there is no POST/PUT/PATCH/DELETE here (those answer 405) and the
 * database trigger would refuse them anyway. Every method requires AUDIT_VIEW (ADMIN).
 */
@RestController
@RequestMapping("${ApiPaths.ADMIN}/audit-events")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
class AuditAdminController(private val service: AuditQueryService) {

    @GetMapping
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "${PageQuery.DEFAULT_SIZE}") size: Int,
        @RequestParam(name = "action", required = false) action: String?,
        @RequestParam(name = "actorType", required = false) actorType: String?,
        @RequestParam(name = "actorId", required = false) actorId: String?,
        @RequestParam(name = "actorRole", required = false) actorRole: String?,
        @RequestParam(name = "entityType", required = false) entityType: String?,
        @RequestParam(name = "entityId", required = false) entityId: String?,
        @RequestParam(name = "incidentId", required = false) incidentId: String?,
        @RequestParam(name = "from", required = false) from: String?,
        @RequestParam(name = "to", required = false) to: String?,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "dir", required = false) dir: String?
    ): PageResponse<AuditEventResponse> = service.list(
        PageQuery(page, size),
        AuditSearchParams(action, actorType, actorId, actorRole, entityType, entityId, incidentId, from, to, sort, dir)
    )

    @GetMapping("/{eventId}")
    fun get(@PathVariable("eventId") eventId: String): AuditEventResponse = service.get(eventId)
}

/**
 * One incident's audit trail. Authenticated callers only; whether THIS caller may see THIS incident is decided by
 * P2's IncidentAccessPolicy inside the service (503 while that provider is not deployed).
 */
@RestController
@RequestMapping("${ApiPaths.INCIDENTS}/{incidentId}/audit")
class IncidentAuditController(private val service: AuditQueryService) {

    @GetMapping
    fun list(
        @PathVariable("incidentId") incidentId: String,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "${PageQuery.DEFAULT_SIZE}") size: Int,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "dir", required = false) dir: String?
    ): PageResponse<IncidentAuditItem> = service.listForIncident(incidentId, PageQuery(page, size), sort, dir)
}
