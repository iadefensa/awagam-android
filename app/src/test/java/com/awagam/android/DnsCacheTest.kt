// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import com.awagam.android.dns.DnsCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress

/**
 * Unit tests for the DNS cache: TTL derivation for positive and negative
 * answers, and cache key handling.
 * Uses Robolectric because the cache is backed by `android.util.LruCache`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DnsCacheTest {

    private val name = Name.fromString("example.com.")

    private fun query(): Message {
        val query = Message(0x1234)
        query.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION)
        return query
    }

    private fun response(rcode: Int, block: (Message) -> Unit): Message {
        val response = Message(0x1234)
        response.header.setFlag(Flags.QR.toInt())
        response.header.rcode = rcode
        response.addRecord(Record.newRecord(name, Type.A, DClass.IN), Section.QUESTION)
        block(response)
        return response
    }

    @Test
    fun `positive answers use the smallest answer TTL`() {
        val message = response(Rcode.NOERROR) {
            it.addRecord(ARecord(name, DClass.IN, 900L, InetAddress.getByName("93.184.216.34")), Section.ANSWER)
            it.addRecord(ARecord(name, DClass.IN, 300L, InetAddress.getByName("93.184.216.35")), Section.ANSWER)
        }

        assertEquals(300L, DnsCache().getEffectiveTtl(message))
    }

    @Test
    fun `positive answers are clamped to the minimum`() {
        val message = response(Rcode.NOERROR) {
            it.addRecord(ARecord(name, DClass.IN, 5L, InetAddress.getByName("93.184.216.34")), Section.ANSWER)
        }

        assertEquals(60L, DnsCache().getEffectiveTtl(message))
    }

    @Test
    fun `negative answers honor the SOA negative TTL`() {
        val soa = SOARecord(
            name, DClass.IN, 3600L,
            Name.fromString("ns.example.com."), Name.fromString("hostmaster.example.com."),
            1L, 7200L, 3600L, 1209600L, 300L
        )
        val message = response(Rcode.NXDOMAIN) { it.addRecord(soa, Section.AUTHORITY) }

        // The SOA minimum (300) is smaller than the record’s own TTL (3600)
        assertEquals(300L, DnsCache().getEffectiveTtl(message))
    }

    @Test
    fun `negative answers are capped so new domains resolve soon`() {
        val soa = SOARecord(
            name, DClass.IN, 86400L,
            Name.fromString("ns.example.com."), Name.fromString("hostmaster.example.com."),
            1L, 7200L, 3600L, 1209600L, 86400L
        )
        val message = response(Rcode.NXDOMAIN) { it.addRecord(soa, Section.AUTHORITY) }

        assertEquals(900L, DnsCache().getEffectiveTtl(message))
    }

    @Test
    fun `negative answers without an SOA fall back to the minimum`() {
        val message = response(Rcode.NXDOMAIN) { }

        assertEquals(60L, DnsCache().getEffectiveTtl(message))
    }

    @Test
    fun `cached responses are returned verbatim`() {
        val cache = DnsCache()
        val message = response(Rcode.NOERROR) {
            it.addRecord(ARecord(name, DClass.IN, 300L, InetAddress.getByName("93.184.216.34")), Section.ANSWER)
        }
        val wire = message.toWire()

        cache.put(query(), message, wire)

        assertArrayEquals(wire, cache.get(query()))
    }

    @Test
    fun `queries for other names miss`() {
        val cache = DnsCache()
        val message = response(Rcode.NOERROR) {
            it.addRecord(ARecord(name, DClass.IN, 300L, InetAddress.getByName("93.184.216.34")), Section.ANSWER)
        }
        cache.put(query(), message, message.toWire())

        val other = Message(0x4321)
        other.addRecord(
            Record.newRecord(Name.fromString("other.example."), Type.A, DClass.IN),
            Section.QUESTION
        )

        assertNull(cache.get(other))
    }
}
