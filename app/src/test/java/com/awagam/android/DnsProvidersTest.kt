// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import com.awagam.android.data.preferences.DnsProviders
import com.awagam.android.dns.DnsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * Unit tests for the upstream resolver catalog.
 * The hardcoded-address check is the important one: A provider whose host is
 * missing from `DnsResolver.DOH_SERVER_IPS` resolves through system DNS, which
 * the tunnel intercepts—so selecting it would send every query to the resolver
 * that is itself waiting to be resolved.
 */
class DnsProvidersTest {

    @Test
    fun `Every provider host has a hardcoded address`() {
        val known = DnsResolver.DOH_SERVER_IPS.keys
        DnsProviders.ALL.forEach { provider ->
            val host = URI(provider.url).host
            assertTrue(
                "No hardcoded address for ${provider.name} ($host)",
                known.contains(host)
            )
        }
    }

    @Test
    fun `Reaches every provider over HTTPS`() {
        DnsProviders.ALL.forEach { provider ->
            assertTrue(
                "${provider.name} is not HTTPS",
                provider.url.startsWith("https://")
            )
        }
    }

    @Test
    fun `Provider URLs are unique`() {
        val urls = DnsProviders.ALL.map { it.url }
        assertEquals("Duplicate provider URLs", urls.size, urls.toSet().size)
    }

    @Test
    fun `Defaults to DNS4EU Protective`() {
        assertEquals(
            "https://protective.joindns4.eu/dns-query",
            DnsProviders.DEFAULT.url
        )
    }

    @Test
    fun `forUrl resolves a known provider`() {
        val quad9 = DnsProviders.ALL.first { it.name == "Quad9" }
        assertEquals(quad9, DnsProviders.forUrl(quad9.url))
    }

    @Test
    fun `forUrl falls back to the default for an unknown URL`() {
        assertEquals(
            DnsProviders.DEFAULT,
            DnsProviders.forUrl("https://dns.example.com/dns-query")
        )
    }

    @Test
    fun `Providers carry a name and a description`() {
        DnsProviders.ALL.forEach { provider ->
            assertTrue("Provider without a name", provider.name.isNotBlank())
            assertTrue("${provider.name} has no description", provider.description.isNotBlank())
        }
    }

    @Test
    fun `Catalog covers the providers the documentation promises`() {
        val names = DnsProviders.ALL.map { it.name }
        listOf("DNS4EU", "Cloudflare", "Google", "Quad9", "OpenDNS", "AdGuard").forEach { promised ->
            assertNotNull(
                "$promised is documented but not offered",
                names.find { it.startsWith(promised) }
            )
        }
    }

    @Test
    fun `localResolver addresses the given port on IPv4 loopback`() {
        assertEquals("udp://127.0.0.1:5354", DnsProviders.localResolver(5354).url)
    }

    @Test
    fun `localResolver defaults to InviZible’s DNSCrypt port`() {
        assertEquals(5354, DnsProviders.LOCAL_RESOLVER_DEFAULT_PORT)
    }

    @Test
    fun `localResolverPort reads the port of a local resolver URL`() {
        assertEquals(5400, DnsProviders.localResolverPort("udp://127.0.0.1:5400"))
    }

    @Test
    fun `localResolverPort rejects hosts other than loopback`() {
        // Plain DNS leaving the device would undo what DoH protects
        listOf(
            "udp://192.168.1.1:53",
            "udp://9.9.9.9:53",
            "udp://localhost:5354"
        ).forEach { url ->
            assertNull(url, DnsProviders.localResolverPort(url))
        }
    }

    @Test
    fun `localResolverPort rejects ports out of range`() {
        listOf(
            "udp://127.0.0.1:0",
            "udp://127.0.0.1:65536",
            "udp://127.0.0.1:",
            "udp://127.0.0.1",
            "udp://127.0.0.1:53x"
        ).forEach { url ->
            assertNull(url, DnsProviders.localResolverPort(url))
        }
    }

    @Test
    fun `localResolverPort ignores DoH providers`() {
        DnsProviders.ALL.forEach { provider ->
            assertNull(provider.name, DnsProviders.localResolverPort(provider.url))
        }
    }

    @Test
    fun `forUrl resolves a local resolver URL`() {
        assertEquals(
            DnsProviders.localResolver(5400),
            DnsProviders.forUrl("udp://127.0.0.1:5400")
        )
    }

    @Test
    fun `forUrl falls back to the default for a non-loopback resolver URL`() {
        assertEquals(DnsProviders.DEFAULT, DnsProviders.forUrl("udp://9.9.9.9:53"))
    }
}