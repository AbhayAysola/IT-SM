package com.itsm.admin.assignment

import com.itsm.common.api.ApiPaths
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Admin API for the single assignment configuration. Every method requires ASSIGNMENT_CONFIGURE (ADMIN only). */
@RestController
@RequestMapping("${ApiPaths.ADMIN}/assignment-settings")
class AssignmentSettingsAdminController(private val service: AssignmentSettingsAdminService) {

    @GetMapping
    @PreAuthorize("hasAuthority('ASSIGNMENT_CONFIGURE')")
    fun get(): AssignmentSettingsResponse = service.get()

    @PutMapping
    @PreAuthorize("hasAuthority('ASSIGNMENT_CONFIGURE')")
    fun update(@Valid @RequestBody request: UpdateAssignmentSettingsRequest): AssignmentSettingsResponse =
        service.update(request)
}
