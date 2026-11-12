package com.itsm.auth.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {

    private fun request(peer: String, vararg xff: String) = MockHttpServletRequest().apply {
        remoteAddr = peer
        xff.forEach { addHeader("X-Forwarded-For", it) }
    }

    @Test fun `by default the TCP peer is used and X-Forwarded-For is ignored completely`() {
        val r = ClientIpResolver(0, emptyList())
        assertEquals("198.51.100.4", r.resolve(request("198.51.100.4", "203.0.113.9")))
        assertEquals("198.51.100.4", r.resolve(request("198.51.100.4", "1.1.1.1, 2.2.2.2")))
        assertEquals("198.51.100.4", r.resolve(request("198.51.100.4")))
    }

    @Test fun `one trusted hop takes the last entry, the one the trusted proxy appended, never what the client forged`() {
        val r = ClientIpResolver(1, listOf("10.0.0.0/8"))
        assertEquals("203.0.113.9", r.resolve(request("10.1.2.3", "203.0.113.9")))
        assertEquals("203.0.113.9", r.resolve(request("10.1.2.3", "6.6.6.6, 7.7.7.7, 203.0.113.9")), "forged entries are on the left")
        assertEquals("203.0.113.9", r.resolve(request("10.1.2.3", "6.6.6.6", "203.0.113.9")), "several header lines are one list")
    }

    @Test fun `two trusted hops take the second entry from the right`() {
        val r = ClientIpResolver(2, listOf("10.0.0.0/8"))
        assertEquals("203.0.113.9", r.resolve(request("10.1.2.3", "forged-not-an-ip, 203.0.113.9, 10.9.9.9")))
        assertEquals("203.0.113.9", r.resolve(request("10.1.2.3", "9.9.9.9, 203.0.113.9, 10.9.9.9")))
    }

    @Test fun `a header from a peer that is not a trusted proxy is ignored`() {
        val r = ClientIpResolver(1, listOf("10.0.0.0/8", "192.168.1.5"))
        assertEquals("198.51.100.4", r.resolve(request("198.51.100.4", "203.0.113.9")))
        assertEquals("203.0.113.9", r.resolve(request("192.168.1.5", "203.0.113.9")), "an exact-IP proxy is trusted")
        assertEquals("192.168.1.6", r.resolve(request("192.168.1.6", "203.0.113.9")), "a neighbouring address is not")
    }

    @Test fun `fewer entries than trusted hops, a missing header or an unusable entry falls back to the peer`() {
        val r2 = ClientIpResolver(2, listOf("10.0.0.0/8"))
        assertEquals("10.1.2.3", r2.resolve(request("10.1.2.3", "203.0.113.9")), "only one entry for two hops")
        val r1 = ClientIpResolver(1, listOf("10.0.0.0/8"))
        assertEquals("10.1.2.3", r1.resolve(request("10.1.2.3")))
        assertEquals("10.1.2.3", r1.resolve(request("10.1.2.3", "evil.example.com")), "a hostname is never accepted (and never resolved)")
        assertEquals("10.1.2.3", r1.resolve(request("10.1.2.3", "203.0.113.9:4711")), "an address with a port is not a plain literal")
        assertEquals("10.1.2.3", r1.resolve(request("10.1.2.3", "999.1.1.1")))
        assertEquals("10.1.2.3", r1.resolve(request("10.1.2.3", "fe80::1%eth0")), "zone ids are rejected")
        assertEquals("10.1.2.3", r1.resolve(request("10.1.2.3", "")))
    }

    @Test fun `IPv6 clients are canonicalised so one address has one bucket`() {
        val r = ClientIpResolver(1, listOf("10.0.0.0/8"))
        assertEquals(r.resolve(request("10.1.2.3", "2001:db8::1")), r.resolve(request("10.1.2.3", "2001:0DB8:0:0:0:0:0:1")))
    }

    @Test fun `trusting forwarded headers without naming the proxy is refused at startup`() {
        assertThrows<IllegalArgumentException> { ClientIpResolver(1, emptyList()) }
        assertThrows<IllegalArgumentException> { ClientIpResolver(2, listOf("  ")) }
        assertThrows<IllegalArgumentException> { ClientIpResolver(-1, emptyList()) }
    }
}
