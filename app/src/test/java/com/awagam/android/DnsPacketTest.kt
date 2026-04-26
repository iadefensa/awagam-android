package com.awagam.android

import com.awagam.android.dns.DnsResolver
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DNS packet handling: IP checksum calculation,
 * payload extraction, and response wrapping.
 */
class DnsPacketTest {

    @Test
    fun `IP checksum matches RFC 791 example`() {
        // Known IP header (checksum field zeroed):
        // Version=4, IHL=5, TotalLen=60, ID=0x1c46, Flags+Offset=0x4000,
        // TTL=64, Proto=6(TCP), Checksum=0x0000,
        // Src=172.16.10.99, Dst=172.16.10.12
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3c,
            0x1c, 0x46, 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00,
            0xac.toByte(), 0x10, 0x0a, 0x63,
            0xac.toByte(), 0x10, 0x0a, 0x0c
        )

        val checksum = DnsResolver.calculateIpChecksum(header, header.size)

        assertEquals(0xb1e6, checksum)
    }

    @Test
    fun `IP checksum of all zeros is 0xFFFF`() {
        val header = ByteArray(20)
        val checksum = DnsResolver.calculateIpChecksum(header, header.size)
        assertEquals(0xFFFF, checksum)
    }

    @Test
    fun `IP checksum verifies to zero when including correct checksum`() {
        // A valid IP header with correct checksum should verify to 0
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3c,
            0x1c, 0x46, 0x40, 0x00,
            0x40, 0x06, 0xb1.toByte(), 0xe6.toByte(),
            0xac.toByte(), 0x10, 0x0a, 0x63,
            0xac.toByte(), 0x10, 0x0a, 0x0c
        )

        val checksum = DnsResolver.calculateIpChecksum(header, header.size)
        assertEquals(0, checksum)
    }

    @Test
    fun `IP checksum handles odd-length header`() {
        // IP headers are always even-length (multiples of 4),
        // but the function should handle odd lengths gracefully
        val header = byteArrayOf(0x01, 0x02, 0x03)
        val checksum = DnsResolver.calculateIpChecksum(header, header.size)
        // 0x0102 + 0x0300 = 0x0402, complement = 0xFBFD
        assertEquals(0xFBFD, checksum)
    }

    @Test
    fun `extractDnsPayload returns payload for valid packet`() {
        // Minimal IPv4 + UDP + DNS packet
        // IP header: 20 bytes (IHL=5), UDP header: 8 bytes, DNS payload: 2 bytes
        val packet = ByteArray(30)
        packet[0] = 0x45 // Version=4, IHL=5 (20 bytes)
        // DNS payload at offset 28
        packet[28] = 0xAB.toByte()
        packet[29] = 0xCD.toByte()

        val payload = DnsResolver.extractDnsPayload(packet, 30)

        assertNotNull(payload)
        assertEquals(2, payload!!.size)
        assertEquals(0xAB.toByte(), payload[0])
        assertEquals(0xCD.toByte(), payload[1])
    }

    @Test
    fun `extractDnsPayload returns null when packet too short`() {
        val packet = ByteArray(20)
        packet[0] = 0x45 // IHL=5 (20 bytes), need 28 minimum
        val payload = DnsResolver.extractDnsPayload(packet, 20)
        assertNull(payload)
    }

    @Test
    fun `extractDnsPayload handles IP options`() {
        // IP header with options: IHL=6 (24 bytes)
        val packet = ByteArray(34)
        packet[0] = 0x46 // Version=4, IHL=6 (24 bytes)
        // DNS starts at 24 + 8 = 32
        packet[32] = 0x12
        packet[33] = 0x34

        val payload = DnsResolver.extractDnsPayload(packet, 34)

        assertNotNull(payload)
        assertEquals(2, payload!!.size)
        assertEquals(0x12.toByte(), payload[0])
        assertEquals(0x34.toByte(), payload[1])
    }

    @Test
    fun `wrapDnsResponse swaps IP addresses`() {
        val queryPacket = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 12345,
            dstPort = 53
        )
        val dnsResponse = byteArrayOf(0x01, 0x02)

        val response = DnsResolver.wrapDnsResponse(queryPacket, dnsResponse)

        // Response source IP should be original destination (10.0.0.1)
        assertEquals(10.toByte(), response[12])
        assertEquals(0.toByte(), response[13])
        assertEquals(0.toByte(), response[14])
        assertEquals(1.toByte(), response[15])

        // Response destination IP should be original source (10.0.0.2)
        assertEquals(10.toByte(), response[16])
        assertEquals(0.toByte(), response[17])
        assertEquals(0.toByte(), response[18])
        assertEquals(2.toByte(), response[19])
    }

    @Test
    fun `wrapDnsResponse swaps ports`() {
        val queryPacket = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 12345,
            dstPort = 53
        )
        val dnsResponse = byteArrayOf(0x01, 0x02)

        val response = DnsResolver.wrapDnsResponse(queryPacket, dnsResponse)

        // Response source port should be 53
        val srcPort = ((response[20].toInt() and 0xFF) shl 8) or (response[21].toInt() and 0xFF)
        assertEquals(53, srcPort)

        // Response destination port should be 12345
        val dstPort = ((response[22].toInt() and 0xFF) shl 8) or (response[23].toInt() and 0xFF)
        assertEquals(12345, dstPort)
    }

    @Test
    fun `wrapDnsResponse sets correct lengths`() {
        val queryPacket = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 12345,
            dstPort = 53
        )
        val dnsResponse = ByteArray(50) // 50-byte DNS response

        val response = DnsResolver.wrapDnsResponse(queryPacket, dnsResponse)

        // Total IP length = 20 (IP) + 8 (UDP) + 50 (DNS) = 78
        val ipTotalLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertEquals(78, ipTotalLength)

        // UDP length = 8 + 50 = 58
        val udpLength = ((response[24].toInt() and 0xFF) shl 8) or (response[25].toInt() and 0xFF)
        assertEquals(58, udpLength)

        // Total packet size
        assertEquals(78, response.size)
    }

    @Test
    fun `wrapDnsResponse produces valid IP checksum`() {
        val queryPacket = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 12345,
            dstPort = 53
        )
        val dnsResponse = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val response = DnsResolver.wrapDnsResponse(queryPacket, dnsResponse)

        // Verifying the checksum: Recalculating over the header should yield 0
        val verify = DnsResolver.calculateIpChecksum(response, 20)
        assertEquals("IP checksum should verify to 0", 0, verify)
    }

    @Test
    fun `wrapDnsResponse contains DNS payload`() {
        val queryPacket = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 12345,
            dstPort = 53
        )
        val dnsResponse = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        val response = DnsResolver.wrapDnsResponse(queryPacket, dnsResponse)

        // DNS payload starts at offset 28 (20 IP + 8 UDP)
        assertEquals(0xDE.toByte(), response[28])
        assertEquals(0xAD.toByte(), response[29])
        assertEquals(0xBE.toByte(), response[30])
        assertEquals(0xEF.toByte(), response[31])
    }

    @Test
    fun `round-trip extract and wrap preserves structure`() {
        val dnsPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val queryPacket = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 53,
            dnsPayload = dnsPayload
        )

        // Extract DNS payload from the query
        val extracted = DnsResolver.extractDnsPayload(queryPacket, queryPacket.size)
        assertNotNull(extracted)
        assertArrayEquals(dnsPayload, extracted)

        // Wrap a response using the query packet
        val responsePayload = byteArrayOf(0x0A, 0x0B, 0x0C)
        val responsePacket = DnsResolver.wrapDnsResponse(queryPacket, responsePayload)

        // Extract DNS payload from the response
        val responseExtracted = DnsResolver.extractDnsPayload(responsePacket, responsePacket.size)
        assertNotNull(responseExtracted)
        assertArrayEquals(responsePayload, responseExtracted)

        // Response checksum should be valid
        val verify = DnsResolver.calculateIpChecksum(responsePacket, 20)
        assertEquals(0, verify)
    }

    // TCP RST (Private DNS DoT Rejection) Tests

    @Test
    fun `isTcpSynToPort853 detects DoT SYN`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )
        assertTrue(DnsResolver.isTcpSynToPort853(packet, packet.size))
    }

    @Test
    fun `isTcpSynToPort853 rejects non-853 port`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 443,
            seqNum = 1000L
        )
        assertFalse(DnsResolver.isTcpSynToPort853(packet, packet.size))
    }

    @Test
    fun `isTcpSynToPort853 rejects UDP packet to port 853`() {
        val packet = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853
        )
        assertFalse(DnsResolver.isTcpSynToPort853(packet, packet.size))
    }

    @Test
    fun `isTcpSynToPort853 rejects TCP ACK without SYN`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )
        // Clear SYN flag (byte 33 = flags), set ACK only
        packet[33] = 0x10.toByte()
        assertFalse(DnsResolver.isTcpSynToPort853(packet, packet.size))
    }

    @Test
    fun `isTcpSynToPort853 rejects too-short packet`() {
        val packet = ByteArray(30)
        assertFalse(DnsResolver.isTcpSynToPort853(packet, packet.size))
    }

    @Test
    fun `createTcpRst swaps IP addresses`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        // RST source = original destination (10.0.0.1)
        assertEquals(10.toByte(), rst[12])
        assertEquals(0.toByte(), rst[13])
        assertEquals(0.toByte(), rst[14])
        assertEquals(1.toByte(), rst[15])

        // RST destination = original source (10.0.0.2)
        assertEquals(10.toByte(), rst[16])
        assertEquals(0.toByte(), rst[17])
        assertEquals(0.toByte(), rst[18])
        assertEquals(2.toByte(), rst[19])
    }

    @Test
    fun `createTcpRst swaps ports`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        // RST source port = 853
        val srcPort = ((rst[20].toInt() and 0xFF) shl 8) or (rst[21].toInt() and 0xFF)
        assertEquals(853, srcPort)

        // RST destination port = 54321
        val dstPort = ((rst[22].toInt() and 0xFF) shl 8) or (rst[23].toInt() and 0xFF)
        assertEquals(54321, dstPort)
    }

    @Test
    fun `createTcpRst sets RST+ACK flags`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        // TCP flags at offset 33: RST (0x04) + ACK (0x10) = 0x14
        assertEquals(0x14.toByte(), rst[33])
    }

    @Test
    fun `createTcpRst sets ACK number to SYN sequence plus one`() {
        val seqNum = 0x12345678L
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = seqNum
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        // ACK number at TCP offset 8-11 (absolute offset 28-31)
        val ackNum = ((rst[28].toLong() and 0xFF) shl 24) or
                ((rst[29].toLong() and 0xFF) shl 16) or
                ((rst[30].toLong() and 0xFF) shl 8) or
                (rst[31].toLong() and 0xFF)
        assertEquals(seqNum + 1, ackNum)
    }

    @Test
    fun `createTcpRst has valid IP checksum`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        val verify = DnsResolver.calculateIpChecksum(rst, 20)
        assertEquals("IP checksum should verify to 0", 0, verify)
    }

    @Test
    fun `createTcpRst has valid TCP checksum`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        // Verify TCP checksum: build pseudo-header + TCP segment and check
        val tcpLen = 20
        val pseudoAndTcp = ByteArray(12 + tcpLen)
        System.arraycopy(rst, 12, pseudoAndTcp, 0, 4) // src IP
        System.arraycopy(rst, 16, pseudoAndTcp, 4, 4) // dst IP
        pseudoAndTcp[8] = 0     // zero
        pseudoAndTcp[9] = 6     // TCP
        pseudoAndTcp[10] = 0
        pseudoAndTcp[11] = tcpLen.toByte()
        System.arraycopy(rst, 20, pseudoAndTcp, 12, tcpLen)

        val verify = DnsResolver.calculateIpChecksum(pseudoAndTcp, pseudoAndTcp.size)
        assertEquals("TCP checksum should verify to 0", 0, verify)
    }

    @Test
    fun `createTcpRst has correct total length`() {
        val packet = buildTcpSynPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            dstIp = byteArrayOf(10, 0, 0, 1),
            srcPort = 54321,
            dstPort = 853,
            seqNum = 1000L
        )

        val rst = DnsResolver.createTcpRst(packet, packet.size)

        // IP header (20) + TCP header (20) = 40
        assertEquals(40, rst.size)
        val ipTotalLength = ((rst[2].toInt() and 0xFF) shl 8) or (rst[3].toInt() and 0xFF)
        assertEquals(40, ipTotalLength)
    }

    /**
     * Build a minimal IPv4+UDP DNS query packet for testing.
     */
    private fun buildDnsQueryPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray = byteArrayOf()
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + dnsPayload.size
        val packet = ByteArray(totalLen)

        // IP header
        packet[0] = 0x45 // Version=4, IHL=5
        packet[1] = 0x00 // DSCP/ECN
        packet[2] = ((totalLen shr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00 // Identification
        packet[5] = 0x00
        packet[6] = 0x40 // Flags: Don't Fragment
        packet[7] = 0x00
        packet[8] = 0x40 // TTL=64
        packet[9] = 0x11 // Protocol=17 (UDP)
        packet[10] = 0x00 // Checksum (zeroed, will calculate)
        packet[11] = 0x00
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        // Calculate and set IP checksum
        val checksum = DnsResolver.calculateIpChecksum(packet, ipHeaderLen)
        packet[10] = ((checksum shr 8) and 0xFF).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // UDP header
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        val udpLen = udpHeaderLen + dnsPayload.size
        packet[24] = ((udpLen shr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00 // UDP checksum (0 = not used)
        packet[27] = 0x00

        // DNS payload
        System.arraycopy(dnsPayload, 0, packet, 28, dnsPayload.size)

        return packet
    }

    /**
     * Build a minimal IPv4+TCP SYN packet for testing.
     */
    private fun buildTcpSynPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen
        val packet = ByteArray(totalLen)

        // IP header
        packet[0] = 0x45 // Version=4, IHL=5
        packet[1] = 0x00
        packet[2] = ((totalLen shr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00 // Identification
        packet[5] = 0x00
        packet[6] = 0x40 // Don't Fragment
        packet[7] = 0x00
        packet[8] = 0x40 // TTL=64
        packet[9] = 0x06 // Protocol=6 (TCP)
        packet[10] = 0x00
        packet[11] = 0x00
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val checksum = DnsResolver.calculateIpChecksum(packet, ipHeaderLen)
        packet[10] = ((checksum shr 8) and 0xFF).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // TCP header
        val t = ipHeaderLen
        packet[t] = ((srcPort shr 8) and 0xFF).toByte()
        packet[t + 1] = (srcPort and 0xFF).toByte()
        packet[t + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[t + 3] = (dstPort and 0xFF).toByte()

        // Sequence number
        packet[t + 4] = ((seqNum shr 24) and 0xFF).toByte()
        packet[t + 5] = ((seqNum shr 16) and 0xFF).toByte()
        packet[t + 6] = ((seqNum shr 8) and 0xFF).toByte()
        packet[t + 7] = (seqNum and 0xFF).toByte()

        // ACK number = 0
        packet[t + 8] = 0; packet[t + 9] = 0; packet[t + 10] = 0; packet[t + 11] = 0

        // Data offset = 5 (20 bytes)
        packet[t + 12] = (5 shl 4).toByte()

        // Flags: SYN
        packet[t + 13] = 0x02.toByte()

        // Window size
        packet[t + 14] = 0xFF.toByte()
        packet[t + 15] = 0xFF.toByte()

        // Checksum = 0 (not validated by our code under test)
        packet[t + 16] = 0; packet[t + 17] = 0

        // Urgent pointer = 0
        packet[t + 18] = 0; packet[t + 19] = 0

        return packet
    }
}