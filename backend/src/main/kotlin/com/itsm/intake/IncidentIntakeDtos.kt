package com.itsm.intake

import com.itsm.common.model.Priority
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateIncidentRequest(
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:NotBlank @field:Size(max = 4000) val description: String = "",
    @field:NotNull val categoryId: Long? = null,
    @field:NotNull val serviceId: Long? = null,
    @field:NotNull val priority: Priority? = null
)

data class UpdateIncidentRequest(
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:NotBlank @field:Size(max = 4000) val description: String = ""
)

data class IncidentIntakeDto(
    val id: String,
    val title: String,
    val description: String,
    val state: String,
    val categoryId: Long,
    val serviceId: Long,
    val priority: String,
    val reporterId: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CatalogCategoryDto(val id: Long, val code: String, val name: String)
data class CatalogServiceDto(val id: Long, val name: String)

data class AnalyzeIncidentRequest(
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:NotBlank @field:Size(max = 4000) val description: String = ""
)

data class DuplicateCandidate(val id: String, val title: String, val reason: String)

data class IncidentAnalysisDto(
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String?,
    val confidence: String,
    val reasoning: String,
    val possibleDuplicates: List<DuplicateCandidate>
)
