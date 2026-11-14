package com.itsm.intake

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.InvalidInput
import com.itsm.common.spi.CategoryCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

@Service
class IncidentAnalysisService(
    @Value("\${anthropic.api-key:}") private val apiKey: String,
    private val jdbc: JdbcTemplate,
    private val categories: CategoryCatalog,
    private val mapper: ObjectMapper,
    restClientBuilder: RestClient.Builder,
) {
    companion object {
        private val log = LoggerFactory.getLogger(IncidentAnalysisService::class.java)
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
    }

    private val client = restClientBuilder.build()

    fun analyze(title: String, description: String): IncidentAnalysisDto {
        if (apiKey.isBlank()) throw InvalidInput("AI analysis is not configured on this server.")

        val activeCategories = categories.listActive()
        val recentOpen = jdbc.query(
            """SELECT id, title FROM incidents
               WHERE state IN ('NEW','CLASSIFIED','ASSIGNED','ACKNOWLEDGED','IN_PROGRESS','PENDING_INFO')
               ORDER BY created_at DESC LIMIT 20""",
            { rs, _ -> rs.getString("id") to rs.getString("title") }
        )

        val systemPrompt = """
            You are an IT service management assistant that classifies support incidents.
            Respond ONLY with valid JSON — no markdown fences, no preamble, nothing outside the JSON object.
            Schema:
            {
              "suggestedCategoryId": <integer from the provided list>,
              "suggestedCategoryName": "<string>",
              "confidence": "HIGH" | "MEDIUM" | "LOW",
              "reasoning": "<one short sentence>",
              "possibleDuplicates": [{"id": "<string>", "title": "<string>", "reason": "<string>"}]
            }
        """.trimIndent()

        val userContent = buildString {
            appendLine("Categories:")
            activeCategories.forEach { c ->
                val kw = if (c.keywords.isNotEmpty()) " (keywords: ${c.keywords.joinToString()})" else ""
                appendLine("  ${c.id}: ${c.name}$kw")
            }
            appendLine()
            if (recentOpen.isNotEmpty()) {
                appendLine("Open incidents (flag any that look like duplicates):")
                recentOpen.forEach { (id, t) -> appendLine("  $id: $t") }
                appendLine()
            }
            appendLine("New incident to classify:")
            appendLine("Title: $title")
            appendLine("Description: $description")
        }

        val body = mapper.writeValueAsString(
            mapOf(
                "model" to MODEL,
                "max_tokens" to 512,
                "system" to systemPrompt,
                "messages" to listOf(mapOf("role" to "user", "content" to userContent))
            )
        )

        val responseText = try {
            client.post()
                .uri(ANTHROPIC_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?: throw InvalidInput("Empty response from AI service.")
        } catch (e: RestClientException) {
            log.warn("Anthropic API call failed: ${e.message}")
            throw InvalidInput("AI service is unavailable. Please try again later.")
        }

        return parseResponse(responseText)
    }

    internal fun parseResponse(responseText: String): IncidentAnalysisDto {
        val root = try {
            mapper.readTree(responseText)
        } catch (e: Exception) {
            log.warn("Failed to parse Anthropic envelope as JSON: $responseText")
            throw InvalidInput("AI returned an unexpected response.")
        }

        // Claude Messages API: content[0].text holds the model's reply
        val text = root.path("content").get(0)?.path("text")?.asText()
            ?: throw InvalidInput("AI response had no text content.")

        return try {
            val node = mapper.readTree(text.trim())
            val catId = node.get("suggestedCategoryId")?.takeUnless { it.isNull }?.asLong()
            val catName = node.path("suggestedCategoryName").asText(null)
            val confidence = node.path("confidence").asText("LOW")
            val reasoning = node.path("reasoning").asText("")
            val duplicates = node.path("possibleDuplicates").map { d ->
                DuplicateCandidate(
                    id = d.path("id").asText(""),
                    title = d.path("title").asText(""),
                    reason = d.path("reason").asText("")
                )
            }
            IncidentAnalysisDto(catId, catName, confidence, reasoning, duplicates)
        } catch (e: Exception) {
            log.warn("Failed to parse AI analysis payload: $text", e)
            throw InvalidInput("AI returned an unexpected response format.")
        }
    }
}
