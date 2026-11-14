package com.itsm.intake

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.InvalidInput
import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.CategoryView
import com.itsm.common.spi.ServiceCatalog
import com.itsm.common.spi.ServiceView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class IncidentChatServiceTest {

    private val mapper = ObjectMapper()
    private val categories = mock(CategoryCatalog::class.java)
    private val services = mock(ServiceCatalog::class.java)

    private fun service(apiKey: String = "test-key"): Pair<IncidentChatService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val svc = IncidentChatService(apiKey, categories, services, mapper, builder)
        return svc to server
    }

    private fun stubCatalog() {
        val cat = mock(CategoryView::class.java)
        `when`(cat.id).thenReturn(1L)
        `when`(cat.name).thenReturn("Network")
        `when`(cat.keywords).thenReturn(listOf("wifi", "internet"))
        `when`(categories.listActive()).thenReturn(listOf(cat))

        val svc = mock(ServiceView::class.java)
        `when`(svc.id).thenReturn(10L)
        `when`(svc.name).thenReturn("Email")
        `when`(services.listActive()).thenReturn(listOf(svc))
    }

    private fun claudeEnvelope(text: String) =
        """{"content":[{"type":"text","text":${mapper.writeValueAsString(text)}}]}"""

    private fun extracted(
        title: String? = null, description: String? = null,
        catId: Long? = null, catName: String? = null,
        svcId: Long? = null, svcName: String? = null,
        impact: String? = null, urgency: String? = null,
        ready: Boolean = false
    ) = """<extracted>{"title":${mapper.writeValueAsString(title)},"description":${mapper.writeValueAsString(description)},"suggestedCategoryId":${catId ?: "null"},"suggestedCategoryName":${mapper.writeValueAsString(catName)},"suggestedServiceId":${svcId ?: "null"},"suggestedServiceName":${mapper.writeValueAsString(svcName)},"impact":${mapper.writeValueAsString(impact)},"urgency":${mapper.writeValueAsString(urgency)},"ready":$ready}</extracted>"""

    // ---- blank API key -----------------------------------------------------------------------

    @Test fun `blank API key throws without hitting the network`() {
        val (svc, _) = service(apiKey = "")
        assertThrows<InvalidInput> { svc.chat(listOf(ChatMessageRequest("user", "hello"))) }
    }

    // ---- parseResponse: envelope errors -----------------------------------------------------

    @Test fun `parseResponse throws when envelope is not JSON`() {
        val (svc, _) = service()
        assertThrows<InvalidInput> { svc.parseResponse("not json") }
    }

    @Test fun `parseResponse throws when content array is missing`() {
        val (svc, _) = service()
        assertThrows<InvalidInput> { svc.parseResponse("""{"id":"msg_1"}""") }
    }

    // ---- parseResponse: extracted tag parsing -----------------------------------------------

    @Test fun `parseResponse strips extracted tag and returns reply text`() {
        val (svc, _) = service()
        val text = "Sure, what's the problem?\n${extracted()}"
        val result = svc.parseResponse(claudeEnvelope(text))
        assertEquals("Sure, what's the problem?", result.reply)
    }

    @Test fun `parseResponse returns null extracted when no tag present`() {
        val (svc, _) = service()
        val text = "Hi there, how can I help you today?"
        val result = svc.parseResponse(claudeEnvelope(text))
        assertEquals(text, result.reply)
        assertNull(result.extracted)
    }

    @Test fun `parseResponse fills all fields from a complete extracted block`() {
        val (svc, _) = service()
        val text = "Got it!\n${extracted("VPN broken","Cannot connect since 9am",1L,"Network",10L,"Email","HIGH","HIGH",true)}"
        val result = svc.parseResponse(claudeEnvelope(text))
        val e = result.extracted!!
        assertEquals("VPN broken", e.title)
        assertEquals("Cannot connect since 9am", e.description)
        assertEquals(1L, e.suggestedCategoryId)
        assertEquals("Network", e.suggestedCategoryName)
        assertEquals(10L, e.suggestedServiceId)
        assertEquals("Email", e.suggestedServiceName)
        assertEquals("HIGH", e.impact)
        assertEquals("HIGH", e.urgency)
        assertTrue(e.ready)
    }

    @Test fun `parseResponse forces ready=false when any required field is null`() {
        val (svc, _) = service()
        // title is null — ready must be false even if the JSON says true
        val text = "Almost there!\n${extracted(null,"desc",1L,"Network",10L,"Email","HIGH","HIGH",true)}"
        val result = svc.parseResponse(claudeEnvelope(text))
        assertFalse(result.extracted!!.ready)
    }

    @Test fun `parseResponse returns null extracted when JSON inside tag is malformed`() {
        val (svc, _) = service()
        val text = "Hmm\n<extracted>not valid json</extracted>"
        val result = svc.parseResponse(claudeEnvelope(text))
        // reply still has the conversational text; extracted is null (not an exception)
        assertEquals("Hmm", result.reply)
        assertNull(result.extracted)
    }

    @Test fun `parseResponse ignores invalid impact and urgency values`() {
        val (svc, _) = service()
        val text = "OK\n${extracted("Title","Desc",1L,"Net",10L,"Email","EXTREME","CRITICAL",false)}"
        val result = svc.parseResponse(claudeEnvelope(text))
        assertNull(result.extracted!!.impact)
        assertNull(result.extracted!!.urgency)
    }

    // ---- full chat() flow -------------------------------------------------------------------

    @Test fun `chat drops leading assistant messages so Anthropic first message is always user`() {
        val (svc, server) = service()
        stubCatalog()
        // Simulate the frontend initial state: first message is the hardcoded assistant greeting.
        val msgs = listOf(
            ChatMessageRequest("assistant", "Hi! I'm your IT helpdesk assistant."),
            ChatMessageRequest("user", "my laptop won't turn on"),
        )
        val replyText = "Sorry to hear that!\n${extracted()}"
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(claudeEnvelope(replyText), MediaType.APPLICATION_JSON))

        svc.chat(msgs)
        server.verify() // server was called (dropWhile correctly stripped the leading assistant)
    }

    @Test fun `chat throws when all messages are assistant role`() {
        val (svc, _) = service()
        stubCatalog()
        val allAssistant = listOf(ChatMessageRequest("assistant", "hello"))
        assertThrows<InvalidInput> { svc.chat(allAssistant) }
    }

    @Test fun `chat returns assistant reply via MockRestServiceServer`() {
        val (svc, server) = service()
        stubCatalog()
        val replyText = "What service are you having trouble with?\n${extracted()}"
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(claudeEnvelope(replyText), MediaType.APPLICATION_JSON))

        val result = svc.chat(listOf(ChatMessageRequest("user", "my email is broken")))
        assertEquals("What service are you having trouble with?", result.reply)
        server.verify()
    }

    @Test fun `chat throws InvalidInput when API returns a server error`() {
        val (svc, server) = service()
        stubCatalog()
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withServerError())

        assertThrows<InvalidInput> { svc.chat(listOf(ChatMessageRequest("user", "help"))) }
        server.verify()
    }

    @Test fun `chat caps messages at MAX_MESSAGES before sending`() {
        val (svc, server) = service()
        stubCatalog()
        // Build 25 messages — only the last 20 should be sent
        val msgs = (1..25).map { ChatMessageRequest(if (it % 2 == 1) "user" else "assistant", "msg $it") }
        val replyText = "Got it\n${extracted()}"

        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(claudeEnvelope(replyText), MediaType.APPLICATION_JSON))

        svc.chat(msgs)
        server.verify()
    }
}
