package com.itsm.intake

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateIncidentRequest(
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:NotBlank @field:Size(max = 4000) val description: String = "",
    @field:NotNull val categoryId: Long? = null,
    @field:NotNull val serviceId: Long? = null,
    /** Reporter-supplied impact: HIGH | MEDIUM | LOW. The backend calls PriorityPolicy to derive the priority. */
    @field:NotBlank val impact: String = "",
    /** Reporter-supplied urgency: HIGH | MEDIUM | LOW. The backend calls PriorityPolicy to derive the priority. */
    @field:NotBlank val urgency: String = "",
    /** Category the AI suggested before the reporter confirmed their choice. Null when the form was submitted without AI analysis. */
    val aiSuggestedCategoryId: Long? = null,
    /** Incident IDs the AI flagged as possible duplicates. Null or empty when none were detected. */
    val aiDuplicateIds: List<String>? = null,
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
    val updatedAt: Instant,
    /** The reporter's answers the priority was assessed from (V22); null for incidents created before they were stored. */
    val impact: String? = null,
    val urgency: String? = null
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
