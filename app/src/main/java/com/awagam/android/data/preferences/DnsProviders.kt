// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.preferences

/**
 * An upstream resolver the user can select: DNS-over-HTTPS, or a local resolver.
 * The host of every `url` must appear in `DnsResolver.DOH_SERVER_IPS`: without a
 * hardcoded address, resolving it would fall back to system DNS, which the
 * tunnel intercepts—the query would be answered by the resolver it is trying to
 * reach.
 */
data class DnsProvider(
    val name: String,
    val description: String,
    val url: String
)

/**
 * The DoH resolvers offered under Settings, in display order, plus the local
 * resolver offered after them.
 */
object DnsProviders {

    val ALL = listOf(
        DnsProvider(
            name = "DNS4EU Protective",
            description = "EU-based and GDPR-compliant; blocks malware and phishing",
            url = "https://protective.joindns4.eu/dns-query"
        ),
        DnsProvider(
            name = "DNS4EU Protective + Child Protection",
            description = "Adult content blocked as well",
            url = "https://child.joindns4.eu/dns-query"
        ),
        DnsProvider(
            name = "DNS4EU Protective + Ad Blocking",
            description = "Ad and tracker domains blocked as well",
            url = "https://noads.joindns4.eu/dns-query"
        ),
        DnsProvider(
            name = "DNS4EU Protective + Child Protection + Ad Blocking",
            description = "Adult content, ads, and trackers blocked as well",
            url = "https://child-noads.joindns4.eu/dns-query"
        ),
        DnsProvider(
            name = "DNS4EU Unfiltered",
            description = "No filtering of its own—blocklists alone decide",
            url = "https://unfiltered.joindns4.eu/dns-query"
        ),
        DnsProvider(
            name = "Cloudflare",
            description = "Fast global network",
            url = "https://cloudflare-dns.com/dns-query"
        ),
        DnsProvider(
            name = "Google",
            description = "Reliable and widely used",
            url = "https://dns.google/dns-query"
        ),
        DnsProvider(
            name = "Quad9",
            description = "Security-focused; blocks malicious domains",
            url = "https://dns.quad9.net/dns-query"
        ),
        DnsProvider(
            name = "OpenDNS",
            description = "Cisco-operated",
            url = "https://doh.opendns.com/dns-query"
        ),
        DnsProvider(
            name = "AdGuard",
            description = "Privacy-focused; blocks ads and trackers",
            url = "https://dns.adguard.com/dns-query"
        )
    )

    /**
     * DNS4EU Protective: EU-based, and filtering malware and phishing on its own,
     * so a fresh install without blocklists still offers some protection.
     */
    val DEFAULT = ALL.first()

    /**
     * The provider a stored URL belongs to, falling back to the default.
     * Only the selection UI writes this preference, and only values from `ALL` or
     * `localResolver`, so the fallback covers a downgrade that shipped a provider
     * since removed.
     */
    fun forUrl(url: String): DnsProvider =
        ALL.find { it.url == url }
            ?: localResolverPort(url)?.let { localResolver(it) }
            ?: DEFAULT

    /**
     * InviZible Pro’s DNSCrypt port in proxy mode, the setup local resolvers are
     * most often asked for.
     */
    const val LOCAL_RESOLVER_DEFAULT_PORT = 5354

    private const val LOCAL_RESOLVER_PREFIX = "udp://127.0.0.1:"

    /**
     * A resolver running on this device, like InviZible’s DNSCrypt, reached with
     * plain DNS over UDP. Limited to loopback: Plain DNS leaving the device would
     * be readable on the network, which is what DoH is chosen to prevent.
     */
    fun localResolver(port: Int): DnsProvider = DnsProvider(
        name = "Local resolver",
        description = "A DNS resolver on this device, on port $port (like InviZible’s DNSCrypt)",
        url = "$LOCAL_RESOLVER_PREFIX$port"
    )

    /**
     * The port of a local resolver URL, or null for anything else.
     */
    fun localResolverPort(url: String): Int? =
        url.removePrefix(LOCAL_RESOLVER_PREFIX)
            .takeIf { it != url && it.all(Char::isDigit) }
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
}