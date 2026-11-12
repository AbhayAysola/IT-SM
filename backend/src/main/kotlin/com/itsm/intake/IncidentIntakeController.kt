package com.itsm.intake

import com.itsm.common.api.ApiPaths
import com.itsm.common.security.CurrentUserProvider
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
@RequestMapping(ApiPaths.INCIDENTS)
class IncidentIntakeController(
    private val service: IncidentIntakeService,
    private val analysisService: IncidentAnalysisService,
    private val currentUser: CurrentUserProvider
) {

    @PostMapping
    @PreAuthorize("hasAuthority('INCIDENT_CREATE')")
    fun create(@Valid @RequestBody body: CreateIncidentRequest): ResponseEntity<IncidentIntakeDto> {
        val dto = service.create(body, currentUser.current())
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(dto.id).toUri()
        return ResponseEntity.created(location).body(dto)
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('INCIDENT_CREATE')")
    fun update(@PathVariable id: String, @Valid @RequestBody body: UpdateIncidentRequest): IncidentIntakeDto =
        service.update(id, body, currentUser.current())

    /** Active categories for the report form. Any authenticated user with INCIDENT_CREATE may read them. */
    @GetMapping("/catalog/categories")
    @PreAuthorize("hasAuthority('INCIDENT_CREATE')")
    fun categories(): List<CatalogCategoryDto> = service.listCategories()

    /** Active services for the report form. */
    @GetMapping("/catalog/services")
    @PreAuthorize("hasAuthority('INCIDENT_CREATE')")
    fun services(): List<CatalogServiceDto> = service.listServices()

    /** AI-powered categorisation and duplicate detection before the incident is saved. */
    @PostMapping("/analyze")
    @PreAuthorize("hasAuthority('INCIDENT_CREATE')")
    fun analyze(@Valid @RequestBody body: AnalyzeIncidentRequest): IncidentAnalysisDto =
        analysisService.analyze(body.title, body.description)
}
