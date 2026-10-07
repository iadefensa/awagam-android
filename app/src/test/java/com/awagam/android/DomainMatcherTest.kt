// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.awagam.android.data.blocklist.DomainMatcher

/**
 * Unit tests for domain/TLD matching logic.
 * Runs on Robolectric for the platform’s UTS #46 IDNA implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DomainMatcherTest {

    private lateinit var matcher: TestDomainMatcher

    @Before
    fun setup() {
        matcher = TestDomainMatcher()
    }

    // TLD Matching Tests

    @Test
    fun `Exact TLD match blocks domain`() {
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
    fun `Blocks multiple TLDs`() {
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
    fun `Exact domain match blocks`() {
        matcher.addDomain("blocked.com")
        assertTrue(matcher.isBlocked("blocked.com"))
    }

    @Test
    fun `Blocks a subdomain of a blocked domain`() {
        matcher.addDomain("blocked.com")
        assertTrue(matcher.isBlocked("sub.blocked.com"))
        assertTrue(matcher.isBlocked("deep.sub.blocked.com"))
    }

    @Test
    fun `Does not block a similar domain`() {
        matcher.addDomain("blocked.com")
        assertFalse(matcher.isBlocked("notblocked.com"))
        assertFalse(matcher.isBlocked("blocked.org"))
        assertFalse(matcher.isBlocked("myblocked.com")) // different domain
    }

    @Test
    fun `A www domain entry blocks apex and www`() {
        matcher.addDomain("www.blocked.com")
        assertTrue(matcher.isBlocked("blocked.com"))
        assertTrue(matcher.isBlocked("www.blocked.com"))
        assertTrue(matcher.isBlocked("sub.blocked.com"))
    }

    @Test
    fun `Domain matching is case insensitive`() {
        matcher.addDomain("Blocked.COM")
        assertTrue(matcher.isBlocked("blocked.com"))
        assertTrue(matcher.isBlocked("BLOCKED.COM"))
        assertTrue(matcher.isBlocked("Sub.Blocked.Com"))
    }

    // IDN/Punycode Tests

    @Test
    fun `Punycode domain matches`() {
        // München.de -> xn--mnchen-3ya.de
        matcher.addDomain("xn--mnchen-3ya.de")
        assertTrue(matcher.isBlocked("xn--mnchen-3ya.de"))
    }

    @Test
    fun `Converts a Unicode domain to punycode`() {
        matcher.addDomain("münchen.de")
        // Should be stored as punycode
        assertTrue(matcher.isBlocked("xn--mnchen-3ya.de"))
    }

    @Test
    fun `Keeps deviation characters as browsers resolve them (UTS 46 nontransitional)`() {
        matcher.addDomain("straße.de")
        assertTrue(matcher.isBlocked("xn--strae-oqa.de"))
        assertTrue(matcher.isBlocked("straße.de"))
        assertFalse(matcher.isBlocked("strasse.de"))
    }

    @Test
    fun `Accepts hyphens in third and fourth position`() {
        matcher.addDomain("r3---sn-abc.googlevideo.com")
        assertTrue(matcher.isBlocked("r3---sn-abc.googlevideo.com"))
        assertEquals("r3---sn-abc.googlevideo.com", DomainMatcher.toAscii("r3---sn-abc.googlevideo.com"))
    }

    @Test
    fun `Converts a Unicode TLD to punycode`() {
        matcher.addTld(".рф")
        assertTrue(matcher.isBlocked("example.xn--p1ai"))
    }

    // Edge Cases

    @Test
    fun `Empty blocklist blocks nothing`() {
        assertFalse(matcher.isBlocked("example.com"))
        assertFalse(matcher.isBlocked("anything.ru"))
    }

    @Test
    fun `Trims whitespace in a domain`() {
        matcher.addDomain("  blocked.com  ")
        assertTrue(matcher.isBlocked("blocked.com"))
    }

    @Test
    fun `Does not block localhost unless specified`() {
        assertFalse(matcher.isBlocked("localhost"))
        matcher.addDomain("localhost")
        assertTrue(matcher.isBlocked("localhost"))
    }

    @Test
    fun `Blocks an IP address as a domain`() {
        matcher.addDomain("192.168.1.1")
        assertTrue(matcher.isBlocked("192.168.1.1"))
    }

    // Combined TLD and Domain

    @Test
    fun `Domain block takes precedence over TLD allow`() {
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
     * Adapter over the production builder, so tests can add rules incrementally
     * while the matcher itself stays immutable.
     */
    class TestDomainMatcher {
        private val builder = DomainMatcher.Companion.Builder()

        fun addTld(tld: String) = builder.addTld(tld)

        fun addDomain(domain: String) = builder.addDomain(domain)

        fun isBlocked(hostname: String): Boolean = builder.build().isBlocked(hostname)
    }
}