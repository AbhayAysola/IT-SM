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
    @Value("\${gemini.api-key:}") private val apiKey: String,
    private val jdbc: JdbcTemplate,
    private val categories: CategoryCatalog,
    private val mapper: ObjectMapper,
    restClientBuilder: RestClient.Builder,
) {
    companion object {
        private val log = LoggerFactory.getLogger(IncidentAnalysisService::class.java)
        private const val GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    }

    private val client = restClientBuilder.build()

    fun analyze(title: String, description: String): IncidentAnalysisDto {
        val candidates = ftsSearch(title, description)

        // No Gemini key: return FTS matches as duplicate candidates without AI categorisation.
        if (apiKey.isBlank()) {
            return IncidentAnalysisDto(
                suggestedCategoryId = null,
                suggestedCategoryName = null,
                confidence = "LOW",
                reasoning = "",
                possibleDuplicates = candidates.map { (id, t, _) ->
                    DuplicateCandidate(id, t, "Similar incident found by text search")
                }
            )
        }

        val activeCategories = categories.listActive()

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
            if (candidates.isNotEmpty()) {
                appendLine("Open incidents — flag any that look like duplicates (FTS-ranked, most similar first):")
                candidates.forEach { (id, t, snippet) ->
                    appendLine("  $id: $t")
                    if (snippet.isNotBlank()) appendLine("    \"${snippet.trim()}\"")
                }
                appendLine()
            }
            appendLine("New incident to classify:")
            appendLine("Title: $title")
            appendLine("Description: $description")
        }

        val body = mapper.writeValueAsString(
            mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to userContent)))),
                "generationConfig" to mapOf("maxOutputTokens" to 512)
            )
        )

        val responseText = try {
            client.post()
                .uri("$GEMINI_BASE_URL?key=$apiKey")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?: throw InvalidInput("Empty response from AI service.")
        } catch (e: RestClientException) {
            log.warn("Gemini API call failed: ${e.message}")
            throw InvalidInput("AI service is unavailable. Please try again later.")
        }

        return parseResponse(responseText)
    }

    private fun ftsSearch(title: String, description: String): List<Triple<String, String, String>> {
        val searchQuery = "$title $description"
        val ftsMatches = jdbc.query(
            """SELECT id, title, left(description, 200) AS snippet
               FROM incidents
               WHERE state IN ('NEW','CLASSIFIED','ASSIGNED','ACKNOWLEDGED','IN_PROGRESS','PENDING_INFO')
                 AND to_tsvector('english', title || ' ' || description)
                     @@ plainto_tsquery('english', ?)
               ORDER BY ts_rank_cd(
                   to_tsvector('english', title || ' ' || description),
                   plainto_tsquery('english', ?)) DESC
               LIMIT 10""",
            { rs, _ -> Triple(rs.getString("id"), rs.getString("title"), rs.getString("snippet")) },
            searchQuery, searchQuery
        )
        val seenIds = ftsMatches.map { it.first }.toHashSet()
        val extras = if (ftsMatches.size < 5) {
            jdbc.query(
                """SELECT id, title, left(description, 200) AS snippet
                   FROM incidents
                   WHERE state IN ('NEW','CLASSIFIED','ASSIGNED','ACKNOWLEDGED','IN_PROGRESS','PENDING_INFO')
                   ORDER BY created_at DESC LIMIT 15""",
                { rs, _ -> Triple(rs.getString("id"), rs.getString("title"), rs.getString("snippet")) }
            ).filterNot { it.first in seenIds }.take(10 - ftsMatches.size)
        } else emptyList()
        return ftsMatches + extras
    }

    internal fun parseResponse(responseText: String): IncidentAnalysisDto {
        val root = try {
            mapper.readTree(responseText)
        } catch (e: Exception) {
            log.warn("Failed to parse Gemini envelope as JSON: $responseText")
            throw InvalidInput("AI returned an unexpected response.")
        }

        val text = root.path("candidates").get(0)
            ?.path("content")?.path("parts")?.get(0)?.path("text")?.asText()
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
