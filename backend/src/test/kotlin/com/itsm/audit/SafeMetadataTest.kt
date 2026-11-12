package com.itsm.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Database-free: the output shape guard for metadata. */
class SafeMetadataTest {

    @Test fun `scalars and flat scalar lists pass through unchanged`() {
        val result = SafeMetadata.of(mapOf("s" to "x", "i" to 1, "l" to 2L, "d" to 1.5, "b" to true, "list" to listOf("a", 1, false)))
        assertEquals(mapOf("s" to "x", "i" to 1, "l" to 2L, "d" to 1.5, "b" to true, "list" to listOf("a", 1, false)), result)
    }

    @Test fun `nested maps, nulls, other objects and non-scalar list items are dropped`() {
        val result = SafeMetadata.of(mapOf("map" to mapOf("a" to 1), "nothing" to null, "obj" to Any(), "list" to listOf("a", null, mapOf("x" to 1), listOf(1))))
        assertEquals(setOf("list"), result.keys)
        assertEquals(listOf("a"), result["list"])
    }

    @Test fun `output is sorted by key, unmodifiable, and null or empty input gives an empty map`() {
        assertEquals(listOf("a", "b", "c"), SafeMetadata.of(mapOf("c" to 1, "a" to 2, "b" to 3)).keys.toList())
        assertThrows<UnsupportedOperationException> { (SafeMetadata.of(mapOf("a" to 1)) as MutableMap<String, Any>).put("z", 1) }
        assertThrows<UnsupportedOperationException> { (SafeMetadata.of(mapOf("l" to listOf(1)))["l"] as MutableList<Any>).add(2) }
        assertTrue(SafeMetadata.of(null).isEmpty()); assertTrue(SafeMetadata.of(emptyMap()).isEmpty())
    }

    @Test fun `credential-like keys and e-mail or JWT values are dropped, ordinary values survive`() {
        val result = SafeMetadata.of(mapOf(
            "password" to "x", "newPassword" to "x", "passwordHash" to "x", "accessToken" to "x", "tokenVersion" to 3, "clientSecret" to "x",
            "email" to "a@b.example", "contactPhone" to "1", "authorization" to "Bearer x", "apiKey" to "k",
            "note" to "ping bob@corp.example", "jwtValue" to "eyJhbGciOiJ.eyJzdWIiOiJ.sig", "list" to listOf("ok", "a@b.example"),
            "code" to "NET", "keywordCount" to 4
        ))
        assertEquals(listOf("code", "keywordCount", "list"), result.keys.toList())
        assertEquals(listOf("ok"), result["list"])
    }
}
