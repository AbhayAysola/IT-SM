package com.itsm.intake

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.InvalidInput
import com.itsm.common.spi.CategoryCatalog
import com.itsm.common.spi.CategoryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class IncidentAnalysisServiceTest {

    private val mapper = ObjectMapper()
    private val jdbc = mock(JdbcTemplate::class.java)
    private val categories = mock(CategoryCatalog::class.java)

    private fun service(apiKey: String = "test-key"): Pair<IncidentAnalysisService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val svc = IncidentAnalysisService(apiKey, jdbc, categories, mapper, builder)
        return svc to server
    }

    private fun stubCatalogAndDb() {
        val cat = mock(CategoryView::class.java)
        `when`(cat.id).thenReturn(1L)
        `when`(cat.name).thenReturn("Network")
        `when`(cat.keywords).thenReturn(listOf("wifi", "internet"))
        `when`(categories.listActive()).thenReturn(listOf(cat))
        `when`(jdbc.query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any<org.springframework.jdbc.core.RowMapper<Pair<String,String>>>()))
            .thenReturn(emptyList())
    }

    private fun claudeResponse(payload: String) = """
        {"content":[{"type":"text","text":${mapper.writeValueAsString(payload)}}]}
    """.trimIndent()

    // ---- no API key -------------------------------------------------------------------------

    @Test fun `blank API key throws InvalidInput without hitting the network`() {
        val (svc, _) = service(apiKey = "")
        assertThrows<InvalidInput> { svc.analyze("title", "desc") }
    }

    // ---- parseResponse: envelope parsing ----------------------------------------------------

    @Test fun `parseResponse handles a well-formed Claude reply`() {
        val (svc, _) = service()
        val payload = """{"suggestedCategoryId":1,"suggestedCategoryName":"Network","confidence":"HIGH","reasoning":"wifi issue","possibleDuplicates":[]}"""
        val result = svc.parseResponse(claudeResponse(payload))
        assertEquals(1L, result.suggestedCategoryId)
        assertEquals("Network", result.suggestedCategoryName)
        assertEquals("HIGH", result.confidence)
        assertEquals("wifi issue", result.reasoning)
        assertTrue(result.possibleDuplicates.isEmpty())
    }

    @Test fun `parseResponse surfaces duplicate candidates`() {
        val (svc, _) = service()
        val payload = """{"suggestedCategoryId":2,"suggestedCategoryName":"Hardware","confidence":"MEDIUM","reasoning":"monitor","possibleDuplicates":[{"id":"INC1001","title":"Screen black","reason":"same symptom"}]}"""
        val result = svc.parseResponse(claudeResponse(payload))
        assertEquals(1, result.possibleDuplicates.size)
        assertEquals("INC1001", result.possibleDuplicates[0].id)
    }

    @Test fun `parseResponse accepts a null suggestedCategoryId`() {
        val (svc, _) = service()
        val payload = """{"suggestedCategoryId":null,"suggestedCategoryName":null,"confidence":"LOW","reasoning":"unclear","possibleDuplicates":[]}"""
        val result = svc.parseResponse(claudeResponse(payload))
        assertNull(result.suggestedCategoryId)
    }

    @Test fun `parseResponse throws when the envelope is not JSON`() {
        val (svc, _) = service()
        assertThrows<InvalidInput> { svc.parseResponse("not json at all") }
    }

    @Test fun `parseResponse throws when content array is missing`() {
        val (svc, _) = service()
        assertThrows<InvalidInput> { svc.parseResponse("""{"id":"msg_123"}""") }
    }

    @Test fun `parseResponse throws when the inner payload is not JSON`() {
        val (svc, _) = service()
        val envelope = """{"content":[{"type":"text","text":"oops, not json"}]}"""
        assertThrows<InvalidInput> { svc.parseResponse(envelope) }
    }

    // ---- full analyze() flow ----------------------------------------------------------------

    @Test fun `analyze returns AI suggestion when API key is set`() {
        val (svc, server) = service()
        stubCatalogAndDb()
        val payload = """{"suggestedCategoryId":1,"suggestedCategoryName":"Network","confidence":"HIGH","reasoning":"wifi","possibleDuplicates":[]}"""
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(claudeResponse(payload), MediaType.APPLICATION_JSON))

        val result = svc.analyze("WiFi down", "No connection in office")
        assertEquals(1L, result.suggestedCategoryId)
        assertEquals("HIGH", result.confidence)
        server.verify()
    }

    @Test fun `analyze throws InvalidInput when the API returns a server error`() {
        val (svc, server) = service()
        stubCatalogAndDb()
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
            .andRespond(withServerError())

        assertThrows<InvalidInput> { svc.analyze("title", "desc") }
        server.verify()
    }
}
