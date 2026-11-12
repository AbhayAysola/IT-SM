package com.itsm.admin.category

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
 * Admin API for incident categories. Every method is authorised server-side with CATALOG_MANAGE, which only ADMIN
 * holds (RolePermissions); reads are admin-only too, per the `/api/admin` policy. There is no DELETE: categories
 * are deactivated so that historical incidents keep a valid reference.
 */
@RestController
@RequestMapping("${ApiPaths.ADMIN}/categories")
class CategoryAdminController(private val service: CategoryAdminService) {

    @GetMapping
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "${PageQuery.DEFAULT_SIZE}") size: Int,
        @RequestParam(name = "search", required = false) search: String?,
        @RequestParam(name = "active", required = false) active: Boolean?,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "dir", required = false) dir: String?
    ): PageResponse<CategoryResponse> = service.list(PageQuery(page, size), search, active, sort, dir)

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun get(@PathVariable("id") id: Long): CategoryResponse = service.get(id)

    @PostMapping
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun create(@Valid @RequestBody request: CreateCategoryRequest): ResponseEntity<CategoryResponse> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).body(created)
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun update(@PathVariable("id") id: Long, @Valid @RequestBody request: UpdateCategoryRequest): CategoryResponse =
        service.update(id, request)

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun activate(@PathVariable("id") id: Long): CategoryResponse = service.activate(id)

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun deactivate(@PathVariable("id") id: Long): CategoryResponse = service.deactivate(id)
}
