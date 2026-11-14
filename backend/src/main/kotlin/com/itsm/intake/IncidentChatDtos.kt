package com.itsm.intake

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class ChatMessageRequest(
    @field:NotBlank @field:Pattern(regexp = "user|assistant") val role: String = "",
    // 4000 chars matches the incident description limit and comfortably fits Claude's max response
    // (max_tokens=1024 ≈ 3000–4000 chars); previous assistant messages are included in subsequent requests.
    @field:NotBlank @field:Size(max = 4000) val content: String = ""
)

data class ChatRequest(
    @field:NotEmpty @field:Valid val messages: List<ChatMessageRequest> = emptyList()
)

data class ExtractedIncidentFields(
    val title: String?,
    val description: String?,
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String?,
    val suggestedServiceId: Long?,
    val suggestedServiceName: String?,
    /** HIGH | MEDIUM | LOW, or null when not yet determined */
    val impact: String?,
    /** HIGH | MEDIUM | LOW, or null when not yet determined */
    val urgency: String?,
    /** True when all six required fields are present and the form can be pre-filled */
    val ready: Boolean
)

data class ChatResponse(
    val reply: String,
    val extracted: ExtractedIncidentFields?
)
