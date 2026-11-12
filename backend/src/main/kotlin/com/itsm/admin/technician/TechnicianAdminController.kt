package com.itsm.admin.technician

import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * Admin API for technician profiles. Every method requires USER_MANAGE (ADMIN only, reads included). The `{userId}` path
 * segment is the canonical user id. No DELETE: a profile is deactivated so its history is kept.
 */
@RestController
@RequestMapping("${ApiPaths.ADMIN}/technicians")
@PreAuthorize("hasAuthority('USER_MANAGE')")
class TechnicianAdminController(private val service: TechnicianAdminService) {

    @GetMapping
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "${PageQuery.DEFAULT_SIZE}") size: Int,
        @RequestParam(name = "search", required = false) search: String?,
        @RequestParam(name = "active", required = false) active: Boolean?,
        @RequestParam(name = "available", required = false) available: Boolean?,
        @RequestParam(name = "teamLead", required = false) teamLead: Boolean?,
        @RequestParam(name = "categoryId", required = false) categoryId: Long?,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "dir", required = false) dir: String?
    ): PageResponse<TechnicianResponse> = service.list(PageQuery(page, size), search, active, available, teamLead, categoryId, sort, dir)

    @GetMapping("/{userId}")
    fun get(@PathVariable("userId") userId: Long): TechnicianResponse = service.get(userId)

    @PostMapping
    fun create(@Valid @RequestBody request: CreateTechnicianRequest): ResponseEntity<TechnicianResponse> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{userId}").buildAndExpand(created.userId).toUri()
        return ResponseEntity.created(location).body(created)
    }

    @PutMapping("/{userId}")
    fun update(@PathVariable("userId") userId: Long, @Valid @RequestBody request: UpdateTechnicianRequest): TechnicianResponse =
        service.update(userId, request)

    @PutMapping("/{userId}/skills")
    fun updateSkills(@PathVariable("userId") userId: Long, @Valid @RequestBody request: UpdateSkillsRequest): TechnicianResponse =
        service.updateSkills(userId, request)

    @PostMapping("/{userId}/activate")
    fun activate(@PathVariable("userId") userId: Long, @Valid @RequestBody request: TechnicianVersionRequest): TechnicianResponse =
        service.setActive(userId, request.version!!, true)

    @PostMapping("/{userId}/deactivate")
    fun deactivate(@PathVariable("userId") userId: Long, @Valid @RequestBody request: TechnicianVersionRequest): TechnicianResponse =
        service.setActive(userId, request.version!!, false)
}
