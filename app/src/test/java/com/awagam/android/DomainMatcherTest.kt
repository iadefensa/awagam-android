package com.awagam.android

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.IDN

/**
 * Unit tests for domain/TLD matching logic.
 * Tests the core blocking functionality without Android dependencies.
 */
class DomainMatcherTest {

    private lateinit var matcher: TestDomainMatcher

    @Before
    fun setup() {
        matcher = TestDomainMatcher()
    }

    // TLD Matching Tests

    @Test
    fun `exact TLD match blocks domain`() {
        matcher.addTld(".ru")
        assertTrue(matcher.isBlocked("example.ru"))
        assertTrue(matcher.isBlocked("sub.example.ru"))
    }

    @Test
    fun `TLD without leading dot still works`() {
        matcher.addTld("ru")
        assertTrue(matcher.isBlocked("example.ru"))
    }

    @Test
    fun `TLD does not match partial suffix`() {
        matcher.addTld(".ru")
        assertFalse(matcher.isBlocked("example.guru")) // .guru != .ru
        assertFalse(matcher.isBlocked("example.trust"))
    }

    @Test
    fun `multiple TLDs can be blocked`() {
        matcher.addTld(".ru")
        matcher.addTld(".cn")
        matcher.addTld(".by")

        assertTrue(matcher.isBlocked("example.ru"))
        assertTrue(matcher.isBlocked("example.cn"))
        assertTrue(matcher.isBlocked("example.by"))
        assertFalse(matcher.isBlocked("example.com"))
    }

    // Domain Matching Tests

    @Test
    fun `exact domain match blocks`() {
        matcher.addDomain("blocked.com")
        assertTrue(matcher.isBlocked("blocked.com"))
    }

    @Test
    fun `subdomain of blocked domain is blocked`() {
        matcher.addDomain("blocked.com")
        assertTrue(matcher.isBlocked("sub.blocked.com"))
        assertTrue(matcher.isBlocked("deep.sub.blocked.com"))
    }

    @Test
    fun `similar domain not blocked`() {
        matcher.addDomain("blocked.com")
        assertFalse(matcher.isBlocked("notblocked.com"))
        assertFalse(matcher.isBlocked("blocked.org"))
        assertFalse(matcher.isBlocked("myblocked.com")) // different domain
    }

    @Test
    fun `www domain entry blocks apex and www`() {
        matcher.addDomain("www.blocked.com")
        assertTrue(matcher.isBlocked("blocked.com"))
        assertTrue(matcher.isBlocked("www.blocked.com"))
        assertTrue(matcher.isBlocked("sub.blocked.com"))
    }

    @Test
    fun `domain matching is case insensitive`() {
        matcher.addDomain("Blocked.COM")
        assertTrue(matcher.isBlocked("blocked.com"))
        assertTrue(matcher.isBlocked("BLOCKED.COM"))
        assertTrue(matcher.isBlocked("Sub.Blocked.Com"))
    }

    // IDN/Punycode Tests

    @Test
    fun `punycode domain matches`() {
        // München.de -> xn--mnchen-3ya.de
        matcher.addDomain("xn--mnchen-3ya.de")
        assertTrue(matcher.isBlocked("xn--mnchen-3ya.de"))
    }

    @Test
    fun `unicode domain converted to punycode`() {
        matcher.addDomain("münchen.de")
        // Should be stored as punycode
        assertTrue(matcher.isBlocked("xn--mnchen-3ya.de"))
    }

    // Edge Cases

    @Test
    fun `empty blocklist blocks nothing`() {
        assertFalse(matcher.isBlocked("example.com"))
        assertFalse(matcher.isBlocked("anything.ru"))
    }

    @Test
    fun `whitespace in domain is trimmed`() {
        matcher.addDomain("  blocked.com  ")
        assertTrue(matcher.isBlocked("blocked.com"))
    }

    @Test
    fun `localhost is not blocked unless specified`() {
        assertFalse(matcher.isBlocked("localhost"))
        matcher.addDomain("localhost")
        assertTrue(matcher.isBlocked("localhost"))
    }

    @Test
    fun `IP address can be blocked as domain`() {
        matcher.addDomain("192.168.1.1")
        assertTrue(matcher.isBlocked("192.168.1.1"))
    }

    // Combined TLD and Domain

    @Test
    fun `domain block takes precedence over TLD allow`() {
        // Block specific domain even if TLD not blocked
        matcher.addDomain("malicious.com")
        assertTrue(matcher.isBlocked("malicious.com"))
        assertFalse(matcher.isBlocked("safe.com"))
    }

    @Test
    fun `TLD and domain blocks work together`() {
        matcher.addTld(".ru")
        matcher.addDomain("specific-bad-site.com")

        assertTrue(matcher.isBlocked("anything.ru"))
        assertTrue(matcher.isBlocked("specific-bad-site.com"))
        assertFalse(matcher.isBlocked("safe.com"))
    }

    /**
     * Test implementation of domain matcher that mirrors BlocklistRepository logic.
     */
    class TestDomainMatcher {
        private val blockedTlds = mutableSetOf<String>()
        private val blockedDomains = mutableSetOf<String>()

        fun addTld(tld: String) {
            val normalized = if (tld.startsWith(".")) tld else ".$tld"
            blockedTlds.add(normalized.lowercase())
        }

        fun addDomain(domain: String) {
            val normalized = normalizeDomain(domain).removePrefix("www.")
            blockedDomains.add(normalized)
        }

        fun isBlocked(hostname: String): Boolean {
            val normalized = normalizeDomain(hostname)

            // Check exact domain match
            if (blockedDomains.contains(normalized)) {
                return true
            }

            // Check subdomain matches
            for (blockedDomain in blockedDomains) {
                if (normalized.endsWith(".$blockedDomain")) {
                    return true
                }
            }

            // Check TLD matches
            for (tld in blockedTlds) {
                if (normalized.endsWith(tld)) {
                    return true
                }
            }

            return false
        }

        private fun normalizeDomain(domain: String): String {
            return try {
                IDN.toASCII(domain.lowercase().trim())
            } catch (e: Exception) {
                domain.lowercase().trim()
            }
        }
    }
}
