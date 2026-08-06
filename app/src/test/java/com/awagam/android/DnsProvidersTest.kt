// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import com.awagam.android.data.preferences.DnsProviders
import com.awagam.android.dns.DnsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `every provider host has a hardcoded address`() {
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
    fun `every provider is reached over HTTPS`() {
        DnsProviders.ALL.forEach { provider ->
            assertTrue(
                "${provider.name} is not HTTPS",
                provider.url.startsWith("https://")
            )
        }
    }

    @Test
    fun `provider URLs are unique`() {
        val urls = DnsProviders.ALL.map { it.url }
        assertEquals("Duplicate provider URLs", urls.size, urls.toSet().size)
    }

    @Test
    fun `default is DNS4EU Protective`() {
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
    fun `providers carry a name and a description`() {
        DnsProviders.ALL.forEach { provider ->
            assertTrue("Provider without a name", provider.name.isNotBlank())
            assertTrue("${provider.name} has no description", provider.description.isNotBlank())
        }
    }

    @Test
    fun `catalog covers the providers the documentation promises`() {
        val names = DnsProviders.ALL.map { it.name }
        listOf("DNS4EU", "Cloudflare", "Google", "Quad9", "OpenDNS", "AdGuard").forEach { promised ->
            assertNotNull(
                "$promised is documented but not offered",
                names.find { it.startsWith(promised) }
            )
        }
    }
}