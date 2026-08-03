// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.blocklist

import java.net.IDN

/**
 * Immutable set of blocking rules, matched against query hostnames.
 *
 * Instances are built once per blocklist load and swapped in atomically, so the
 * DNS path never observes a half-populated rule set. Lookups walk the hostname’s
 * parent domains against a hash set instead of scanning every rule, which keeps
 * per-query cost constant as users add large external blocklists.
 */
class DomainMatcher(
    /** Normalized, with a leading dot (".example") */
    val tlds: Set<String>,
    /** Normalized to punycode, without a "www." prefix */
    val domains: Set<String>
) {

    val tldCount: Int get() = tlds.size
    val domainCount: Int get() = domains.size

    fun isBlocked(hostname: String): Boolean {
        val normalized = normalizeDomain(hostname)
        if (normalized.isEmpty()) return false

        // Walk the hostname and each of its parent domains: "a.b.example.com"
        // checks "a.b.example.com", "b.example.com", "example.com", "com"
        var index = 0
        while (index in 0 until normalized.length) {
            val candidate = normalized.substring(index)
            if (domains.contains(candidate)) return true
            // A TLD rule matches at any label boundary, so ".com" blocks "example.com"
            if (index > 0 && tlds.contains(".$candidate")) return true

            val nextDot = normalized.indexOf('.', index)
            index = if (nextDot < 0) -1 else nextDot + 1
        }

        return false
    }

    companion object {
        fun normalizeTld(tld: String): String =
            ".${normalizeDomain(tld.trim().removePrefix("."))}"

        fun normalizeDomain(domain: String): String {
            val trimmed = domain.lowercase().trim().trimEnd('.')
            return try {
                IDN.toASCII(trimmed)
            } catch (e: Exception) {
                trimmed
            }
        }

        val EMPTY = DomainMatcher(emptySet(), emptySet())

        /**
         * Accumulates rules while blocklists are parsed, then produces an
         * immutable matcher to publish to the DNS path.
         */
        class Builder {
            private val tlds = mutableSetOf<String>()
            private val domains = mutableSetOf<String>()

            fun addTld(tld: String) {
                tlds.add(normalizeTld(tld))
            }

            fun addDomain(domain: String) {
                domains.add(normalizeDomain(domain).removePrefix("www."))
            }

            fun build(): DomainMatcher = DomainMatcher(tlds.toSet(), domains.toSet())
        }
    }
}