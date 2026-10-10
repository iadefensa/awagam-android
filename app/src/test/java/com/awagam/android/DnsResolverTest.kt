// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.blocklist.BlocklistRepository
import com.awagam.android.data.preferences.DnsProviders
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

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
        withAnswer: Boolean = true,
        isResponse: Boolean = true
    ): Message {
        val response = Message(id)
        if (isResponse) {
            response.header.setFlag(Flags.QR.toInt())
        }
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
    fun `Serves and caches a matching answer`() {
        val wire = response().toWire()

        val accepted = resolver.acceptUpstreamResponse(query(), wire)

        assertArrayEquals(wire, accepted)
        assertEquals(1, cacheSize())
    }

    @Test
    fun `Question names match regardless of case`() {
        // DNS names compare case-insensitively; a resolver that echoes a query
        // back in different case (as 0x20 randomization does) must still match,
        // or every query would fail
        val wire = response(questionName = Name.fromString("ExAmPlE.CoM.")).toWire()

        val accepted = resolver.acceptUpstreamResponse(query(), wire)

        assertNotNull(accepted)
        assertEquals(1, cacheSize())
    }

    @Test
    fun `Rejects an answer to a different name without caching it`() {
        val wire = response(questionName = Name.fromString("other.example.")).toWire()

        assertNull(resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Rejects an answer for a different type without caching it`() {
        val wire = response(type = Type.AAAA).toWire()

        assertNull(resolver.acceptUpstreamResponse(query(type = Type.A), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Rejects a response without a question`() {
        val bare = Message(0x1234)
        bare.header.setFlag(Flags.QR.toInt())

        assertNull(resolver.acceptUpstreamResponse(query(), bare.toWire()))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Rejects a message without the QR flag without caching it`() {
        // A query echoed back would otherwise pass every other check: It parses,
        // its question matches, and it carries the default NOERROR
        val wire = response(isResponse = false, withAnswer = false).toWire()

        assertNull(resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Rejects an unparsable response`() {
        val garbage = byteArrayOf(0x01, 0x02, 0x03)

        assertNull(resolver.acceptUpstreamResponse(query(), garbage))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Passes SERVFAIL through without caching it`() {
        val wire = response(rcode = Rcode.SERVFAIL, withAnswer = false).toWire()

        assertArrayEquals(wire, resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Passes a truncated answer through without caching it`() {
        val wire = response(truncated = true).toWire()

        assertArrayEquals(wire, resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Caches NXDOMAIN`() {
        val wire = response(rcode = Rcode.NXDOMAIN, withAnswer = false).toWire()

        assertArrayEquals(wire, resolver.acceptUpstreamResponse(query(), wire))
        assertEquals(1, cacheSize())
    }

    @Test
    fun `Patches a mismatched transaction ID without touching the cached copy`() {
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

    // A resolver on loopback that answers one query with an A record, or swallows
    // it when `reply` is false
    private fun startLocalResolver(reply: Boolean = true): DatagramSocket {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                if (!reply) return@thread
                val received = Message(buffer.copyOf(packet.length))
                val answer = response(id = received.header.id).toWire()
                socket.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            } catch (e: Exception) {
                // Closed by the test
            }
        }
        return socket
    }

    // An IPv4/UDP packet carrying `query` to the tunnel’s DNS address
    private fun queryPacket(query: Message): ByteArray {
        val payload = query.toWire()
        val packet = ByteArray(28 + payload.size)
        packet[0] = 0x45
        packet[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
        byteArrayOf(10, 0, 0, 1).copyInto(packet, 16)
        packet[20] = 0xC3.toByte(); packet[21] = 0x50 // Source port 50000
        packet[22] = 0; packet[23] = 53
        payload.copyInto(packet, 28)
        return packet
    }

    @Test
    fun `Forwards a query to a local resolver over UDP`() {
        startLocalResolver().use { server ->
            resolver.switchUpstreamDns(DnsProviders.localResolver(server.localPort).url)
            val packet = queryPacket(query())

            val wrapped = runBlocking { resolver.resolve(packet, packet.size) }!!
            val answer = Message(DnsResolver.extractDnsPayload(wrapped, wrapped.size)!!)

            assertEquals(0x1234, answer.header.id)
            assertEquals(Rcode.NOERROR, answer.header.rcode)
            assertEquals(1, answer.getSection(Section.ANSWER).size)
            assertEquals(1, cacheSize())
        }
    }

    @Test
    fun `Answers SERVFAIL when the local resolver is not running`() {
        val port = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        resolver.switchUpstreamDns(DnsProviders.localResolver(port).url)
        val packet = queryPacket(query())

        val wrapped = runBlocking { resolver.resolve(packet, packet.size) }!!
        val answer = Message(DnsResolver.extractDnsPayload(wrapped, wrapped.size)!!)

        assertEquals(Rcode.SERVFAIL, answer.header.rcode)
        assertEquals(0, cacheSize())
    }

    @Test
    fun `Finds a running local resolver reachable`() {
        startLocalResolver().use { server ->
            assertNull(resolver.testUpstreamConnectivity(DnsProviders.localResolver(server.localPort).url))
        }
    }

    @Test
    fun `Finds a silent local resolver unreachable`() {
        startLocalResolver(reply = false).use { server ->
            assertNotNull(resolver.testUpstreamConnectivity(DnsProviders.localResolver(server.localPort).url))
        }
    }
}