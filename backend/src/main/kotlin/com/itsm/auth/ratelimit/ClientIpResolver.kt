package com.itsm.auth.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.IpAddressMatcher
import java.net.InetAddress

/**
 * Decides which address a rate limit is charged to.
 *
 * Default (`trustedHops == 0`): the TCP peer, `request.remoteAddr`. `X-Forwarded-For` is ignored completely, so a client
 * cannot choose its own bucket.
 *
 * With `trustedHops = N > 0` the application sits behind N proxies that you operate. `X-Forwarded-For` is read ONLY IF the
 * immediate TCP peer is inside [trustedProxies] (exact IPs or CIDR blocks). Every proxy appends the address it saw to the
 * END of the header, so the entries a client forged are on the left and the entry appended by the first trusted proxy is the
 * N-th from the RIGHT. The client is `entries[size - N]`. Examples, N = 1 (one ingress): `X-Forwarded-For: forged, 203.0.113.9`
 * -> `203.0.113.9`; N = 2 (CDN then ingress): `forged, 203.0.113.9, 198.51.100.4` -> `203.0.113.9`.
 *
 * Anything doubtful falls back to the immediate peer (the safe direction: more clients share a bucket, none escape one):
 * an untrusted peer, fewer than N entries, or a selected entry that is not a plain IP literal (hostnames, ports, zone ids).
 * Only the immediate peer is verified; the intermediate hops (N > 1) are trusted because they sit between it and the client.
 */
class ClientIpResolver(private val trustedHops: Int, trustedProxies: List<String>) {

    private val matchers: List<IpAddressMatcher> = trustedProxies.map { it.trim() }.filter { it.isNotEmpty() }.map { IpAddressMatcher(it) }

    init {
        require(trustedHops >= 0) { "app.security.trusted-proxy-hops must be >= 0" }
        require(trustedHops == 0 || matchers.isNotEmpty()) {
            "app.security.trusted-proxy-hops > 0 requires app.security.trusted-proxies (the address(es)/CIDR of your proxy). " +
                "Without it X-Forwarded-For would be trusted from anyone."
        }
    }

    fun resolve(request: HttpServletRequest): String {
        val peer = request.remoteAddr?.takeIf { it.isNotBlank() } ?: "unknown"
        if (trustedHops == 0 || !isTrusted(peer)) return peer
        val entries = request.getHeaders("X-Forwarded-For").toList()
            .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.size < trustedHops) return peer
        return canonical(entries[entries.size - trustedHops]) ?: peer
    }

    private fun isTrusted(peer: String) = isIpLiteral(peer) && matchers.any { it.matches(peer) }

    private companion object {
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        fun isIpLiteral(value: String): Boolean {
            IPV4.matchEntire(value)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
            return ':' in value && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }

        /** The canonical text of an IP literal (no DNS lookup is possible: only literals reach InetAddress), else null. */
        fun canonical(value: String): String? {
            if (!isIpLiteral(value)) return null
            return try { InetAddress.getByName(value).hostAddress } catch (e: Exception) { null }
        }
    }
}
