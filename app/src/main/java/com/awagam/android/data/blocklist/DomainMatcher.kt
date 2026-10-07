// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.blocklist

import android.icu.text.IDNA

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

        // UTS #46 nontransitional processing, as browsers use (keeps “ß” instead of mapping it to “ss”)
        private val idna: IDNA = IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII)

        // Browsers skip hyphen checks (CheckHyphens=false), and CDN hosts like "r3---sn-abc.googlevideo.com" rely on that
        private val ignoredIdnaErrors = setOf(
            IDNA.Error.HYPHEN_3_4,
            IDNA.Error.LEADING_HYPHEN,
            IDNA.Error.TRAILING_HYPHEN
        )

        /** Converts a domain to its ASCII (punycode) form, or returns null if it isn’t a valid IDN. */
        fun toAscii(domain: String): String? {
            val info = IDNA.Info()
            val ascii = StringBuilder()
            idna.nameToASCII(domain, ascii, info)
            return if ((info.errors - ignoredIdnaErrors).isEmpty()) ascii.toString() else null
        }

        fun normalizeDomain(domain: String): String {
            val trimmed = domain.lowercase().trim().trimEnd('.')
            return toAscii(trimmed) ?: trimmed
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