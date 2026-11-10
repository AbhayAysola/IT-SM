package com.itsm.admin.sla

import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * Admin API for SLA policy configuration. Every method requires SLA_CONFIGURE (ADMIN only, reads included).
 * No DELETE: policies are deactivated so history is preserved.
 */
@RestController
@RequestMapping("${ApiPaths.ADMIN}/sla-policies")
class SlaPolicyAdminController(private val service: SlaPolicyAdminService) {

    @GetMapping
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "${PageQuery.DEFAULT_SIZE}") size: Int,
        @RequestParam(name = "search", required = false) search: String?,
        @RequestParam(name = "active", required = false) active: Boolean?,
        @RequestParam(name = "priority", required = false) priority: String?,
        @RequestParam(name = "serviceId", required = false) serviceId: Long?,
        @RequestParam(name = "global", required = false) global: Boolean?,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "dir", required = false) dir: String?
    ): PageResponse<SlaPolicyResponse> =
        service.list(PageQuery(page, size), search, active, priority, serviceId, global, sort, dir)

    /** What the SLA provider would return for a service and priority right now (read-only preview). */
    @GetMapping("/effective")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun effective(
        @RequestParam(name = "serviceId") serviceId: Long,
        @RequestParam(name = "priority") priority: String
    ): EffectiveSlaResponse = service.effective(serviceId, priority)

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun get(@PathVariable("id") id: Long): SlaPolicyResponse = service.get(id)

    @PostMapping
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun create(@Valid @RequestBody request: CreateSlaPolicyRequest): ResponseEntity<SlaPolicyResponse> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).body(created)
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun update(@PathVariable("id") id: Long, @Valid @RequestBody request: UpdateSlaPolicyRequest): SlaPolicyResponse =
        service.update(id, request)

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun activate(@PathVariable("id") id: Long): SlaPolicyResponse = service.activate(id)

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('SLA_CONFIGURE')")
    fun deactivate(@PathVariable("id") id: Long): SlaPolicyResponse = service.deactivate(id)
}
