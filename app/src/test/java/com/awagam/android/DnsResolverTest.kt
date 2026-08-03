// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.blocklist.BlocklistRepository
import com.awagam.android.dns.DnsResolver
import org.junit.Assert.*
import org.junit.Before
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
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress

/**
 * Unit tests for how DnsResolver treats upstream DoH responses: which ones may
 * be served to the client, and which ones may be cached.
 *
 * Uses Robolectric for the Application context and for `android.util.LruCache`,
 * which backs the DNS cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DnsResolverTest {

    private val name = Name.fromString("example.com.")
    private lateinit var resolver: DnsResolver

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        resolver = DnsResolver(BlocklistRepository(context))
    }

    private fun query(id: Int = 0x1234, questionName: Name = name, type: Int = Type.A): Message {
        val query = Message(id)
        query.addRecord(Record.newRecord(questionName, type, DClass.IN), Section.QUESTION)
        return query
    }

    private fun response(
        id: Int = 0x1234,
        questionName: Name = name,
        type: Int = Type.A,
        rcode: Int = Rcode.NOERROR,
        truncated: Boolean = false,
        withAnswer: Boolean = true
    ): Message {
        val response = Message(id)
        response.header.setFlag(Flags.QR.toInt())
        response.header.rcode = rcode
        if (truncated) {
            response.header.setFlag(Flags.TC.toInt())
        }
        response.addRecord(Record.newRecord(questionName, type, DClass.IN), Section.QUESTION)
        if (withAnswer) {
            response.addRecord(
                ARecord(questionName, DClass.IN, 300L, InetAddress.getByName("93.184.216.34")),
                Section.ANSWER
            )
        }
        return response
    }

    private fun cacheSize(): Int = resolver.getCacheStats().size

    @Test
    fun `a matching answer is served and cached`() {
        val wire = response().toWire()

        val accepted = resolver.acceptUpstreamResponse(query(), wire)

        assertArrayEquals(wire, accepted)
        assertEquals(1, cacheSize())
    }

    @Test
    fun `question names match regardless of case`() {
        // DNS names compare case-insensitively; a resolver that echoes a query
        // back in different case (as 0x20 randomization does) must still match,
        // or every query would fail
        val wire = response(questionName = Name.fromString("ExAmPlE.CoM.")).toWire()

        val accepted = resolver.acceptUpstreamResponse(query(), wire)

        assertNotNull(accepted)
        assertEquals(1, cacheSize())
    }

    @Test
    fun `an answer to a different name is rejected and not cached`() {
        val wire = response(questionName = Name.fromString("other.example.")).toWire()

        assertNull(resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `an answer for a different type is rejected and not cached`() {
        val wire = response(type = Type.AAAA).toWire()

        assertNull(resolver.acceptUpstreamResponse(query(type = Type.A), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `a response without a question is rejected`() {
        val bare = Message(0x1234)
        bare.header.setFlag(Flags.QR.toInt())

        assertNull(resolver.acceptUpstreamResponse(query(), bare.toWire()))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `an unparsable response is rejected`() {
        val garbage = byteArrayOf(0x01, 0x02, 0x03)

        assertNull(resolver.acceptUpstreamResponse(query(), garbage))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `SERVFAIL is passed through but not cached`() {
        val wire = response(rcode = Rcode.SERVFAIL, withAnswer = false).toWire()

        assertArrayEquals(wire, resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `a truncated answer is passed through but not cached`() {
        val wire = response(truncated = true).toWire()

        assertArrayEquals(wire, resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `NXDOMAIN is cached`() {
        val wire = response(rcode = Rcode.NXDOMAIN, withAnswer = false).toWire()

        assertArrayEquals(wire, resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(1, cacheSize())
    }

    @Test
    fun `a mismatched transaction ID is patched without touching the cached copy`() {
        val wire = response(id = 0x0000).toWire()
        val original = wire.copyOf()

        val accepted = resolver.acceptUpstreamResponse(query(id = 0x1234), wire)!!

        // The client matches on its own ID
        assertEquals(0x12.toByte(), accepted[0])
        assertEquals(0x34.toByte(), accepted[1])
        // What was handed to the cache is untouched, so the entry stays as received
        assertArrayEquals(original, wire)
        assertEquals(1, cacheSize())
    }
}
