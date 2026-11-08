package com.itsm.common.event

import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class AuditableEventTest {
    private val actor = AuditActor.of(CurrentUser(1, "a@x.com", Role.ADMIN))

    private fun event(
        metadata: Map<String, Any> = emptyMap(),
        action: String = "INCIDENT_ASSIGNED",
        description: String = "Assigned to technician 5"
    ) = AuditableEvent(actor, action, "INCIDENT", "INC1024", "INC1024", "CLASSIFIED", "ASSIGNED", description, metadata)

    @Test fun `accepts all documented scalar types and scalar lists`() {
        val e = event(mapOf("s" to "x", "b" to true, "i" to 1, "l" to 2L, "d" to 1.5, "bd" to BigDecimal("2.5"), "tags" to listOf("a", "b")))
        assertEquals(7, e.metadata.size)
    }

    @Test fun `metadata is a defensive unmodifiable copy`() {
        val source = mutableMapOf<String, Any>("technicianId" to 5L, "tags" to mutableListOf("a"))
        val e = event(source)
        source["technicianId"] = 99L
        (source["tags"] as MutableList<String>).add("b")
        source["extra"] = "late"

        assertEquals(5L, e.metadata["technicianId"])
        assertEquals(listOf("a"), e.metadata["tags"])
        assertEquals(setOf("technicianId", "tags"), e.metadata.keys)
        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> { (e.metadata as MutableMap<String, Any>)["x"] = 1 }
        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> { (e.metadata["tags"] as MutableList<Any>).add("c") }
    }

    @Test fun `rejects unsupported metadata value types`() {
        val bad = listOf<Any>(Instant.now(), mapOf("a" to 1), 1.5f, 1.toShort(), Any(), listOf(listOf("nested")), Double.NaN, Double.POSITIVE_INFINITY)
        for (value in bad) {
            assertThrows<IllegalArgumentException>("should reject ${value.javaClass.simpleName}") { event(mapOf("k" to value)) }
        }
    }

    @Test fun `rejects null metadata values smuggled in through an unchecked map`() {
        @Suppress("UNCHECKED_CAST")
        val withNull = mapOf<String, Any?>("k" to null) as Map<String, Any>
        assertThrows<IllegalArgumentException> { event(withNull) }
    }

    @Test fun `enforces metadata size limits and key format`() {
        assertThrows<IllegalArgumentException> { event((1..21).associate { "k$it" to 1 }) }
        assertThrows<IllegalArgumentException> { event(mapOf("k" to "x".repeat(AuditableEvent.MAX_STRING + 1))) }
        assertThrows<IllegalArgumentException> { event(mapOf("k" to List(21) { it })) }
        assertThrows<IllegalArgumentException> { event(mapOf("Bad-Key" to 1)) }
        assertThrows<IllegalArgumentException> { event(mapOf("1key" to 1)) }
    }

    @Test fun `validates identifier formats`() {
        assertThrows<IllegalArgumentException> { event(action = "assigned") }
        assertThrows<IllegalArgumentException> { event(action = "A") }
        assertThrows<IllegalArgumentException> { event(description = "  ") }
        assertThrows<IllegalArgumentException> { event(description = "x".repeat(1001)) }
        assertThrows<IllegalArgumentException> {
            AuditableEvent(actor, "ACTION_X", "INCIDENT", incidentId = "INC 1; DROP", description = "d")
        }
        assertThrows<IllegalArgumentException> { AuditActor.system("lowercase") }
    }

    @Test fun `actor can be a user or a named system job`() {
        assertEquals(AuditActor.UserActor(1, "a@x.com", Role.ADMIN), actor)
        assertEquals("SLA_MONITOR", (AuditActor.system("SLA_MONITOR") as AuditActor.SystemActor).name)
    }

    @Test fun `toString does not dump metadata or description`() {
        val s = event(mapOf("note" to "confidential")).toString()
        assert(!s.contains("confidential") && !s.contains("Assigned to"))
    }
}
