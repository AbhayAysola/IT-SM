package com.itsm.intake

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.InvalidInput
import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.ServiceCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private const val MAX_MESSAGES = 20

@Service
class IncidentChatService(
    @Value("\${anthropic.api-key:}") private val apiKey: String,
    private val categories: CategoryCatalog,
    private val services: ServiceCatalog,
    private val mapper: ObjectMapper,
    restClientBuilder: RestClient.Builder,
) {
    companion object {
        private val log = LoggerFactory.getLogger(IncidentChatService::class.java)
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"

        private val VALID_IMPACT = setOf("HIGH", "MEDIUM", "LOW")
        private val VALID_URGENCY = setOf("HIGH", "MEDIUM", "LOW")
        private val EXTRACTED_TAG = Regex("""<extracted>(.*?)</extracted>""", RegexOption.DOT_MATCHES_ALL)
    }

    private val client = restClientBuilder.build()

    fun chat(messages: List<ChatMessageRequest>): ChatResponse {
        if (apiKey.isBlank()) throw InvalidInput("AI chat is not configured on this server.")

        val categoryList = categories.listActive().joinToString("\n") { c ->
            val kw = if (c.keywords.isNotEmpty()) " (keywords: ${c.keywords.joinToString()})" else ""
            "  ${c.id}: ${c.name}$kw"
        }
        val serviceList = services.listActive().joinToString("\n") { s ->
            "  ${s.id}: ${s.name}"
        }

        val systemPrompt = """
            You are a friendly IT helpdesk assistant helping users report support incidents.
            Gather all the information needed to file a ticket through natural conversation.

            You need to collect:
            1. A short title (what went wrong, max 200 chars)
            2. A description (what happened, when it started, what was tried, max 4000 chars)
            3. Category — pick from the list below
            4. Affected service/system — pick from the list below
            5. Impact — how many people are affected:
               HIGH = whole org or large group | MEDIUM = user's team | LOW = just the user
            6. Urgency — can the user still work:
               HIGH = work has stopped | MEDIUM = there is a workaround | LOW = inconvenient but workable

            Available categories:
            $categoryList

            Available services:
            $serviceList

            Rules:
            - Ask one or two questions at a time — do not overwhelm the user.
            - Be concise and friendly.
            - After EVERY reply, end with an <extracted> block on its own line containing a single JSON object:
              <extracted>{"title":null,"description":null,"suggestedCategoryId":null,"suggestedCategoryName":null,"suggestedServiceId":null,"suggestedServiceName":null,"impact":null,"urgency":null,"ready":false}</extracted>
            - Use JSON null (not the string "null") for fields not yet determined.
            - Set ready=true only when ALL six fields (title, description, suggestedCategoryId, suggestedServiceId, impact, urgency) are non-null.
        """.trimIndent()

        // Cap history and enforce Anthropic's rule: first message must be user role.
        // The frontend includes a hardcoded assistant greeting in the initial state; drop it here.
        val cappedMessages = messages.takeLast(MAX_MESSAGES)
            .dropWhile { it.role == "assistant" }
        if (cappedMessages.isEmpty()) throw InvalidInput("Please describe your issue first.")
        val apiMessages = cappedMessages.map { mapOf("role" to it.role, "content" to it.content) }

        val body = mapper.writeValueAsString(
            mapOf(
                "model" to MODEL,
                "max_tokens" to 1024,
                "system" to systemPrompt,
                "messages" to apiMessages
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
            log.warn("Anthropic chat API call failed: ${e.message}")
            throw InvalidInput("AI service is unavailable. Please try again later.")
        }

        return parseResponse(responseText)
    }

    internal fun parseResponse(responseText: String): ChatResponse {
        val root = try {
            mapper.readTree(responseText)
        } catch (e: Exception) {
            log.warn("Failed to parse Anthropic envelope: ${responseText.take(200)}")
            throw InvalidInput("AI returned an unexpected response.")
        }

        val fullText = root.path("content").get(0)?.path("text")?.asText()
            ?: throw InvalidInput("AI response had no text content.")

        // Extract the <extracted>...</extracted> block; everything outside is the conversational reply.
        val jsonContent = EXTRACTED_TAG.find(fullText)?.groupValues?.get(1)?.trim()
        val reply = EXTRACTED_TAG.replace(fullText, "").trimEnd().ifBlank { fullText.trim() }

        val extracted = jsonContent?.let { json ->
            try {
                val node = mapper.readTree(json)
                val catId = node.get("suggestedCategoryId")?.takeUnless { it.isNull }?.asLong()
                val catName = node.path("suggestedCategoryName").asText(null)
                val svcId = node.get("suggestedServiceId")?.takeUnless { it.isNull }?.asLong()
                val svcName = node.path("suggestedServiceName").asText(null)
                val impact = node.path("impact").asText(null)?.uppercase()?.takeIf { it in VALID_IMPACT }
                val urgency = node.path("urgency").asText(null)?.uppercase()?.takeIf { it in VALID_URGENCY }
                val title = node.path("title").asText(null)?.trim()?.take(200)
                val description = node.path("description").asText(null)?.trim()?.take(4000)
                val ready = node.path("ready").asBoolean(false)
                    && title != null && description != null && catId != null
                    && svcId != null && impact != null && urgency != null
                ExtractedIncidentFields(title, description, catId, catName, svcId, svcName, impact, urgency, ready)
            } catch (e: Exception) {
                log.debug("Could not parse <extracted> fields from chat reply", e)
                null
            }
        }

        return ChatResponse(reply = reply, extracted = extracted)
    }
}
