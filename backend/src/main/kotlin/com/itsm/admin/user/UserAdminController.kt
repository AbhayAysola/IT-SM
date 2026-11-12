package com.itsm.admin.user

import com.itsm.common.api.ApiPaths
import com.itsm.common.api.PageQuery
import com.itsm.common.api.PageResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/** Admin user management. Every method requires USER_MANAGE (ADMIN only, reads included). No DELETE: accounts are deactivated. */
@RestController
@RequestMapping("${ApiPaths.ADMIN}/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
class UserAdminController(private val service: UserAdminService) {

    @GetMapping
    fun list(
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "size", defaultValue = "${PageQuery.DEFAULT_SIZE}") size: Int,
        @RequestParam(name = "search", required = false) search: String?,
        @RequestParam(name = "role", required = false) role: String?,
        @RequestParam(name = "active", required = false) active: Boolean?,
        @RequestParam(name = "sort", required = false) sort: String?,
        @RequestParam(name = "dir", required = false) dir: String?
    ): PageResponse<AdminUserResponse> = service.list(PageQuery(page, size), search, role, active, sort, dir)

    @GetMapping("/{id}")
    fun get(@PathVariable("id") id: Long): AdminUserResponse = service.get(id)

    @PostMapping
    fun create(@Valid @RequestBody request: CreateUserRequest): ResponseEntity<AdminUserResponse> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).body(created)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable("id") id: Long, @Valid @RequestBody request: UpdateUserRequest): AdminUserResponse =
        service.update(id, request)

    @PutMapping("/{id}/role")
    fun changeRole(@PathVariable("id") id: Long, @Valid @RequestBody request: ChangeRoleRequest): AdminUserResponse =
        service.changeRole(id, request)

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable("id") id: Long, @Valid @RequestBody request: VersionRequest): AdminUserResponse =
        service.setActive(id, request.version!!, true)

    @PostMapping("/{id}/deactivate")
    fun deactivate(@PathVariable("id") id: Long, @Valid @RequestBody request: VersionRequest): AdminUserResponse =
        service.setActive(id, request.version!!, false)

    /** Sets a new password and invalidates every token the user holds. */
    @PostMapping("/{id}/reset-password")
    fun resetPassword(@PathVariable("id") id: Long, @Valid @RequestBody request: ResetPasswordRequest): AdminUserResponse =
        service.resetPassword(id, request.password!!)

    /** Invalidates every token the user holds (forces re-login) without changing anything else. */
    @PostMapping("/{id}/revoke-sessions")
    fun revokeSessions(@PathVariable("id") id: Long): AdminUserResponse = service.revokeSessions(id)
}
