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
    @Value("\${gemini.api-key:}") private val apiKey: String,
    private val categories: CategoryCatalog,
    private val services: ServiceCatalog,
    private val mapper: ObjectMapper,
    restClientBuilder: RestClient.Builder,
) {
    companion object {
        private val log = LoggerFactory.getLogger(IncidentChatService::class.java)
        private const val GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        private val VALID_IMPACT = setOf("HIGH", "MEDIUM", "LOW")
        private val VALID_URGENCY = setOf("HIGH", "MEDIUM", "LOW")
        private val EXTRACTED_TAG = Regex("""<extracted>(.*?)</extracted>""", RegexOption.DOT_MATCHES_ALL)
    }

    private val client = restClientBuilder.build()

    fun chat(messages: List<ChatMessageRequest>): ChatResponse {
        if (apiKey.isBlank()) return mockChat(messages)

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

        // Cap history and enforce Gemini's rule: first message must be user role.
        // The frontend includes a hardcoded assistant greeting in the initial state; drop it here.
        val cappedMessages = messages.takeLast(MAX_MESSAGES)
            .dropWhile { it.role == "assistant" }
        if (cappedMessages.isEmpty()) throw InvalidInput("Please describe your issue first.")

        // Gemini uses "model" instead of "assistant" for AI turns
        val contents = cappedMessages.map { m ->
            val role = if (m.role == "assistant") "model" else m.role
            mapOf("role" to role, "parts" to listOf(mapOf("text" to m.content)))
        }

        val body = mapper.writeValueAsString(
            mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
                "contents" to contents,
                "generationConfig" to mapOf("maxOutputTokens" to 1024)
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
            log.warn("Gemini chat API call failed: ${e.message}")
            throw InvalidInput("AI service is unavailable. Please try again later.")
        }

        return parseResponse(responseText)
    }

    internal fun parseResponse(responseText: String): ChatResponse {
        val root = try {
            mapper.readTree(responseText)
        } catch (e: Exception) {
            log.warn("Failed to parse Gemini envelope: ${responseText.take(200)}")
            throw InvalidInput("AI returned an unexpected response.")
        }

        val fullText = root.path("candidates").get(0)
            ?.path("content")?.path("parts")?.get(0)?.path("text")?.asText()
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

    private fun mockChat(messages: List<ChatMessageRequest>): ChatResponse {
        val userTurns = messages.count { it.role == "user" }
        val none = ExtractedIncidentFields(null, null, null, null, null, null, null, null, false)
        return when {
            userTurns <= 1 -> ChatResponse(
                reply = "Hi there! I'm your IT helpdesk assistant. I'll help you log a support ticket in just a few steps. Could you briefly describe what's going wrong?",
                extracted = none
            )
            userTurns == 2 -> ChatResponse(
                reply = "Thanks for letting me know. How many people are affected — just you, your team, or a larger group? And has your work completely stopped, or is there a workaround you can use in the meantime?",
                extracted = ExtractedIncidentFields(
                    title = "WiFi connectivity outage on office floor",
                    description = messages.lastOrNull { it.role == "user" }?.content,
                    suggestedCategoryId = 3L, suggestedCategoryName = "Network",
                    suggestedServiceId = 2L, suggestedServiceName = "VPN",
                    impact = null, urgency = null, ready = false
                )
            )
            else -> ChatResponse(
                reply = "Got it — a full floor outage with no workaround, that's high priority. I've captured everything and pre-filled the incident form for you. Take a quick look and hit Submit to log your ticket!",
                extracted = ExtractedIncidentFields(
                    title = "WiFi connectivity outage on office floor",
                    description = "Complete loss of WiFi connectivity across the office floor. All users on the floor are unable to connect to the network or access any cloud services. No workaround is available as all tasks require internet access. Issue started approximately 30 minutes ago.",
                    suggestedCategoryId = 3L, suggestedCategoryName = "Network",
                    suggestedServiceId = 2L, suggestedServiceName = "VPN",
                    impact = "HIGH", urgency = "HIGH", ready = true
                )
            )
        }
    }
}
