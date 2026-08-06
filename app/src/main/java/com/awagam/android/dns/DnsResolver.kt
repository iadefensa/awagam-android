// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.dns

import android.util.Log
import com.awagam.android.data.blocklist.BlocklistRepository
import com.awagam.android.data.preferences.DnsProviders
import com.awagam.android.statistics.StatisticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.net.SocketFactory
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS resolver that filters queries against the blocklist
 * and forwards allowed queries to an upstream DoH server.
 */
class DnsResolver(private val blocklistRepository: BlocklistRepository) {

    companion object {
        private const val TAG = "DnsResolver"
        private const val DOH_CONTENT_TYPE = "application/dns-message"

        // A DNS message can’t exceed 65535 bytes; anything larger is a broken or
        // hostile upstream, and reading it would be unbounded memory per query
        private const val MAX_DNS_RESPONSE_SIZE = 65535L

        // Blocked response: 0.0.0.0 for A records, :: for AAAA records
        private val BLOCKED_IPV4 = InetAddress.getByName("0.0.0.0")
        private val BLOCKED_IPV6 = InetAddress.getByName("::")

        // Hardcoded IPs for DoH servers to avoid DNS lookup chicken-and-egg problem
        // When the VPN is active, DNS queries go through us, so we can’t use DNS to resolve DoH servers.
        // Internal so a test can hold `DnsProviders` to it: A selectable provider
        // missing from here would fall through to system DNS
        internal val DOH_SERVER_IPS = mapOf(
            // DNS4EU (EU-based, GDPR-compliant)
            "protective.joindns4.eu" to listOf("86.54.11.1", "86.54.11.201", "2a13:1001::86:54:11:1", "2a13:1001::86:54:11:201"),
            "child.joindns4.eu" to listOf("86.54.11.12", "86.54.11.212", "2a13:1001::86:54:11:12", "2a13:1001::86:54:11:212"),
            "noads.joindns4.eu" to listOf("86.54.11.13", "86.54.11.213", "2a13:1001::86:54:11:13", "2a13:1001::86:54:11:213"),
            "child-noads.joindns4.eu" to listOf("86.54.11.11", "86.54.11.211", "2a13:1001::86:54:11:11", "2a13:1001::86:54:11:211"),
            "unfiltered.joindns4.eu" to listOf("86.54.11.100", "86.54.11.200", "2a13:1001::86:54:11:100", "2a13:1001::86:54:11:200"),
            // Cloudflare
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
            "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"),
            // Google
            "dns.google" to listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
            // Quad9
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
            // OpenDNS
            "doh.opendns.com" to listOf("208.67.222.222", "208.67.220.220"),
            // AdGuard
            "dns.adguard.com" to listOf("94.140.14.14", "94.140.15.15")
        )

        /**
         * Calculate the IP header checksum per RFC 791.
         * The checksum field (bytes 10–11) must be zeroed before calling this.
         */
        internal fun calculateIpChecksum(header: ByteArray, length: Int): Int {
            var sum = 0L
            for (i in 0 until length step 2) {
                val word = if (i + 1 < length) {
                    ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
                } else {
                    (header[i].toInt() and 0xFF) shl 8
                }
                sum += word
            }
            while (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFF).toInt()
        }

        internal fun extractDnsPayload(packet: ByteArray, length: Int): ByteArray? {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            val udpHeaderLength = 8
            val dnsStart = ipHeaderLength + udpHeaderLength
            if (dnsStart >= length) return null
            return packet.copyOfRange(dnsStart, length)
        }

        internal fun wrapDnsResponse(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val totalLength = ipHeaderLength + 8 + dnsResponse.size
            val response = ByteArray(totalLength)

            System.arraycopy(originalPacket, 0, response, 0, ipHeaderLength)

            // Swap source and destination IP addresses
            System.arraycopy(originalPacket, 12, response, 16, 4)
            System.arraycopy(originalPacket, 16, response, 12, 4)

            // Update IP total length
            response[2] = ((totalLength shr 8) and 0xFF).toByte()
            response[3] = (totalLength and 0xFF).toByte()

            // Calculate IP header checksum
            response[10] = 0
            response[11] = 0
            val checksum = calculateIpChecksum(response, ipHeaderLength)
            response[10] = ((checksum shr 8) and 0xFF).toByte()
            response[11] = (checksum and 0xFF).toByte()

            // Build UDP header
            val udpOffset = ipHeaderLength
            response[udpOffset] = originalPacket[udpOffset + 2]
            response[udpOffset + 1] = originalPacket[udpOffset + 3]
            response[udpOffset + 2] = originalPacket[udpOffset]
            response[udpOffset + 3] = originalPacket[udpOffset + 1]

            val udpLength = 8 + dnsResponse.size
            response[udpOffset + 4] = ((udpLength shr 8) and 0xFF).toByte()
            response[udpOffset + 5] = (udpLength and 0xFF).toByte()

            // UDP checksum (optional for IPv4, set to 0)
            response[udpOffset + 6] = 0
            response[udpOffset + 7] = 0

            System.arraycopy(dnsResponse, 0, response, udpOffset + 8, dnsResponse.size)

            return response
        }

        /**
         * Checks whether the packet is a TCP SYN destined for port 853 (DNS-over-TLS).
         */
        internal fun isTcpSynToPort853(packet: ByteArray, length: Int): Boolean {
            if (length < 40) return false // Minimum IP (20) + TCP (20)

            val version = (packet[0].toInt() and 0xF0) shr 4
            if (version != 4) return false

            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 6) return false // TCP

            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            if (length < ipHeaderLength + 20) return false

            val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                    (packet[ipHeaderLength + 3].toInt() and 0xFF)
            if (destPort != 853) return false

            // Check that SYN flag is set
            val flags = packet[ipHeaderLength + 13].toInt() and 0xFF
            return (flags and 0x02) != 0
        }

        /**
         * Crafts a TCP RST+ACK response to an incoming TCP SYN.
         * This causes the sender to receive an immediate “connection refused.”
         */
        internal fun createTcpRst(packet: ByteArray, length: Int): ByteArray {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            val tcpHeaderSize = 20 // Minimum TCP header, no options
            val totalLength = ipHeaderLength + tcpHeaderSize
            val rst = ByteArray(totalLength)

            // Copy IP header and swap source/destination addresses
            System.arraycopy(packet, 0, rst, 0, ipHeaderLength)
            System.arraycopy(packet, 12, rst, 16, 4) // original src -> rst dst
            System.arraycopy(packet, 16, rst, 12, 4) // original dst -> rst src

            // Update IP total length
            rst[2] = ((totalLength shr 8) and 0xFF).toByte()
            rst[3] = (totalLength and 0xFF).toByte()

            // Don't Fragment flag, clear identification
            rst[4] = 0; rst[5] = 0
            rst[6] = 0x40.toByte(); rst[7] = 0

            // Recalculate IP header checksum
            rst[10] = 0; rst[11] = 0
            val ipChecksum = calculateIpChecksum(rst, ipHeaderLength)
            rst[10] = ((ipChecksum shr 8) and 0xFF).toByte()
            rst[11] = (ipChecksum and 0xFF).toByte()

            // Build TCP header with swapped ports
            val t = ipHeaderLength
            rst[t] = packet[ipHeaderLength + 2]     // src port = original dst port
            rst[t + 1] = packet[ipHeaderLength + 3]
            rst[t + 2] = packet[ipHeaderLength]     // dst port = original src port
            rst[t + 3] = packet[ipHeaderLength + 1]

            // Sequence number = 0
            rst[t + 4] = 0; rst[t + 5] = 0; rst[t + 6] = 0; rst[t + 7] = 0

            // ACK number = incoming sequence + 1
            val incomingSeq = ((packet[ipHeaderLength + 4].toLong() and 0xFF) shl 24) or
                    ((packet[ipHeaderLength + 5].toLong() and 0xFF) shl 16) or
                    ((packet[ipHeaderLength + 6].toLong() and 0xFF) shl 8) or
                    (packet[ipHeaderLength + 7].toLong() and 0xFF)
            val ackNum = incomingSeq + 1
            rst[t + 8] = ((ackNum shr 24) and 0xFF).toByte()
            rst[t + 9] = ((ackNum shr 16) and 0xFF).toByte()
            rst[t + 10] = ((ackNum shr 8) and 0xFF).toByte()
            rst[t + 11] = (ackNum and 0xFF).toByte()

            // Data offset = 5 (20 bytes), no options
            rst[t + 12] = (5 shl 4).toByte()

            // Flags: RST + ACK
            rst[t + 13] = 0x14.toByte()

            // Window size = 0
            rst[t + 14] = 0; rst[t + 15] = 0

            // Urgent pointer = 0
            rst[t + 18] = 0; rst[t + 19] = 0

            // Calculate TCP checksum (requires pseudo-header)
            rst[t + 16] = 0; rst[t + 17] = 0
            val pseudoAndTcp = ByteArray(12 + tcpHeaderSize)
            System.arraycopy(rst, 12, pseudoAndTcp, 0, 4) // src IP
            System.arraycopy(rst, 16, pseudoAndTcp, 4, 4) // dst IP
            pseudoAndTcp[8] = 0                            // zero
            pseudoAndTcp[9] = 6                            // protocol = TCP
            pseudoAndTcp[10] = ((tcpHeaderSize shr 8) and 0xFF).toByte()
            pseudoAndTcp[11] = (tcpHeaderSize and 0xFF).toByte()
            System.arraycopy(rst, t, pseudoAndTcp, 12, tcpHeaderSize)
            val tcpChecksum = calculateIpChecksum(pseudoAndTcp, pseudoAndTcp.size)
            rst[t + 16] = ((tcpChecksum shr 8) and 0xFF).toByte()
            rst[t + 17] = (tcpChecksum and 0xFF).toByte()

            return rst
        }
    }

    // Default: DNS4EU Protective (EU-based, blocks malware/phishing).
    // Volatile because the settings screen can switch providers mid-session,
    // from a different thread than the one resolving queries.
    @Volatile
    private var upstreamDnsUrl = DnsProviders.DEFAULT.url

    // Bumped on every upstream switch, so a query already in flight can tell that
    // its answer predates the switch. Written under the instance lock, read
    // outside it, hence volatile.
    @Volatile
    private var resolverGeneration = 0

    private val dnsCache = DnsCache()
    private var statisticsManager: StatisticsManager? = null
    private var protectedSocketFactory: SocketFactory? = null

    private var httpClient = createHttpClient()

    private fun createHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .dns(createBypassDns()) // Use hardcoded IPs to avoid DNS loop

        protectedSocketFactory?.let { factory ->
            builder.socketFactory(factory)
        }

        return builder.build()
    }

    /**
     * Creates a DNS resolver that uses hardcoded IPs for known DoH servers.
     * This avoids the chicken-and-egg problem where resolving the DoH server
     * hostname would require DNS, which goes through the VPN, which needs DoH…
     */
    private fun createBypassDns(): Dns {
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                // Check if we have hardcoded IPs for this hostname
                val ips = DOH_SERVER_IPS[hostname]
                if (ips != null) {
                    Log.d(TAG, "Using hardcoded IPs for $hostname: $ips")
                    return ips.mapNotNull { ip ->
                        try {
                            InetAddress.getByName(ip)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }

                // For other hostnames, fall back to system DNS
                // This shouldn’t happen for DoH servers, but just in case
                Log.w(TAG, "No hardcoded IPs for DoH server, using system DNS")
                return Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    /**
     * Set a socket factory that protects sockets from being routed through the VPN.
     * This prevents DoH requests from looping back through the VPN tunnel.
     */
    fun setProtectedSocketFactory(factory: SocketFactory) {
        protectedSocketFactory = factory
        httpClient = createHttpClient()
    }

    /**
     * Initialize with statistics tracking.
     */
    fun initialize(statisticsManager: StatisticsManager) {
        this.statisticsManager = statisticsManager
    }

    fun setUpstreamDns(url: String) {
        upstreamDnsUrl = url
    }

    /**
     * Switch the upstream resolver and drop everything the previous one answered.
     * The three steps are one operation against `acceptUpstreamResponse`, which
     * caches under the same lock: a query still in flight when the switch happens
     * carries the old generation and is kept out of the cache, rather than
     * refilling it right after the clear with an answer the new resolver may
     * filter differently.
     */
    @Synchronized
    fun switchUpstreamDns(url: String) {
        upstreamDnsUrl = url
        resolverGeneration++
        dnsCache.clear()
        Log.d(TAG, "Upstream switched, DNS cache cleared")
    }

    /**
     * Test DoH connectivity by sending a query for example.com.
     * Returns null on success, or an error description on failure.
     * `url` defaults to the current upstream, but a probe that spans retries
     * passes the one it started against, so its verdict covers a single resolver.
     */
    fun testUpstreamConnectivity(url: String = upstreamDnsUrl): String? {
        return try {
            val query = Message(0)
            val name = org.xbill.DNS.Name.fromString("example.com.")
            query.addRecord(
                Record.newRecord(name, Type.A, DClass.IN),
                Section.QUESTION
            )
            query.header.setFlag(Flags.RD.toInt())

            val dnsPayload = query.toWire()
            val request = Request.Builder()
                .url(url)
                .post(dnsPayload.toRequestBody(DOH_CONTENT_TYPE.toMediaType()))
                .header("Accept", DOH_CONTENT_TYPE)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "DoH connectivity test passed (${response.code})")
                    null
                } else {
                    "HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH connectivity test failed", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * What a probe found.
     * `Stale` keeps a verdict about a resolver switched away from mid-probe out
     * of the UI: it describes an upstream no longer in use, and whoever switched
     * probes the new one themselves.
     */
    sealed interface ProbeResult {
        object Reachable : ProbeResult
        data class Unreachable(val detail: String) : ProbeResult
        object Stale : ProbeResult
    }

    /**
     * The upstream together with the generation it belongs to, read under the
     * switch lock so the pair can’t straddle a switch.
     */
    @Synchronized
    private fun currentUpstream(): Pair<String, Int> = upstreamDnsUrl to resolverGeneration

    /**
     * Probe the upstream, retrying before it counts as unreachable.
     * A single failure says little—the tunnel may still be settling, or the
     * network may have blinked—so callers decide how much patience the moment
     * warrants through `attempts` and `retryDelayMs`.
     */
    suspend fun probeUpstream(attempts: Int, retryDelayMs: Long): ProbeResult =
        withContext(Dispatchers.IO) {
            // Pinned for the whole run: Reading the field per attempt would let a
            // switch spread one verdict across two resolvers, and report the
            // second one’s failure against the first one’s name
            val (url, generation) = currentUpstream()
            var lastError: String? = null
            repeat(attempts) { attempt ->
                if (attempt > 0) delay(retryDelayMs)
                // A switch makes the rest of the run moot, so stop rather than
                // spend the remaining timeouts on it
                if (generation != resolverGeneration) return@withContext ProbeResult.Stale
                val error = testUpstreamConnectivity(url)
                    ?: return@withContext ProbeResult.Reachable
                Log.d(TAG, "DoH probe attempt ${attempt + 1} failed: $error")
                lastError = error
            }
            // Once more, since the last attempt could have been overtaken while
            // it was waiting on its own timeout
            if (generation != resolverGeneration) return@withContext ProbeResult.Stale
            // Nothing reported a failure only when there were no attempts to make;
            // silence is the safe reading, a warning nobody probed for is not
            lastError?.let { ProbeResult.Unreachable(it) } ?: ProbeResult.Reachable
        }

    /**
     * Resolve a DNS query packet.
     * Returns a response packet to send back through the VPN interface.
     */
    suspend fun resolve(packet: ByteArray, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Extract DNS payload from IP+UDP packet
            val dnsPayload = extractDnsPayload(packet, length)
            if (dnsPayload == null) {
                Log.w(TAG, "Could not extract DNS payload")
                return@withContext null
            }

            // Parse DNS message
            val query = Message(dnsPayload)
            val question = query.question ?: return@withContext null

            val hostname = question.name.toString(true) // omit trailing dot
            val queryType = question.type

            Log.d(TAG, "DNS query: $hostname (type ${Type.string(queryType)})")

            // Record query statistics
            statisticsManager?.recordQuery(hostname, length)

            // Check if blocked
            val dnsResponse = if (blocklistRepository.isBlocked(hostname)) {
                Log.d(TAG, "Blocking: $hostname")
                statisticsManager?.recordBlockedQuery(hostname)
                createBlockedResponse(query, queryType)
            } else {
                // Check cache first
                val cachedResponse = dnsCache.get(query)
                if (cachedResponse != null) {
                    Log.d(TAG, "Cache hit: $hostname")
                    statisticsManager?.recordCacheHit()
                    // Patch transaction ID to match current query
                    val patched = cachedResponse.copyOf()
                    val queryId = query.header.id
                    patched[0] = ((queryId shr 8) and 0xFF).toByte()
                    patched[1] = (queryId and 0xFF).toByte()
                    patched
                } else {
                    Log.d(TAG, "Cache miss: $hostname")
                    statisticsManager?.recordCacheMiss()
                    // Forward to upstream DoH server, noting which resolver the
                    // query is going out to
                    val generation = resolverGeneration
                    val upstreamResponse = forwardToUpstream(dnsPayload)?.let {
                        acceptUpstreamResponse(query, it, generation)
                    }
                    if (upstreamResponse != null) {
                        upstreamResponse
                    } else {
                        // Return SERVFAIL so the client gets a proper DNS error
                        // instead of timing out with no response
                        Log.w(TAG, "DoH failed, returning SERVFAIL")
                        createServfailResponse(query)
                    }
                }
            }

            // Wrap DNS response in IP+UDP packet
            wrapDnsResponse(packet, dnsResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving DNS", e)
            null
        }
    }

    private fun createBlockedResponse(query: Message, queryType: Int): ByteArray {
        val response = Message(query.header.id)
        response.header.setFlag(Flags.QR.toInt()) // This is a response
        response.header.setFlag(Flags.AA.toInt()) // Authoritative answer
        response.header.rcode = Rcode.NOERROR

        // Copy the question
        val question = query.question
        response.addRecord(question, Section.QUESTION)

        // Add answer based on query type
        val name = question.name
        val ttl = 300L // 5 minutes

        when (queryType) {
            Type.A -> {
                val record = Record.newRecord(name, Type.A, DClass.IN, ttl, BLOCKED_IPV4.address)
                response.addRecord(record, Section.ANSWER)
            }
            Type.AAAA -> {
                val record = Record.newRecord(name, Type.AAAA, DClass.IN, ttl, BLOCKED_IPV6.address)
                response.addRecord(record, Section.ANSWER)
            }
            else -> {
                // For other record types, return NXDOMAIN
                response.header.rcode = Rcode.NXDOMAIN
            }
        }

        return response.toWire()
    }

    /**
     * Check an upstream response against the query before it is handed to the
     * client or cached, and cache it when it is worth caching.
     * `generation` is the one the query was sent under; an answer from a resolver
     * since switched away from is still served, but not cached.
     * Returns the response to send back, or null to fail the query.
     */
    internal fun acceptUpstreamResponse(
        query: Message,
        response: ByteArray,
        generation: Int = resolverGeneration
    ): ByteArray? {
        val responseMessage = try {
            Message(response)
        } catch (e: Exception) {
            Log.w(TAG, "Upstream returned an unparsable response", e)
            return null
        }

        if (!responseMessage.header.getFlag(Flags.QR.toInt())) {
            Log.w(TAG, "Upstream returned a query rather than a response")
            return null
        }

        // A response whose question doesn’t match the query answers something
        // else; serving or caching it would put a foreign answer under this key
        val question = responseMessage.question
        if (question == null || question != query.question) {
            Log.w(TAG, "Upstream response question does not match the query")
            return null
        }

        // Only cache answers a resolver may reuse: failures are transient, and a
        // truncated answer is incomplete by definition
        val rcode = responseMessage.header.rcode
        val cacheable = !responseMessage.header.getFlag(Flags.TC.toInt()) &&
                (rcode == Rcode.NOERROR || rcode == Rcode.NXDOMAIN)
        if (cacheable) {
            cacheIfCurrent(generation, query, responseMessage, response)
        }

        // The transaction ID is what the client matches on, so make sure it is
        // the one it sent even if the upstream echoed something else. Patching a
        // copy keeps the cached entry as it was received, since cache hits set
        // the ID of whichever query they answer
        val queryId = query.header.id
        if (responseMessage.header.id == queryId) {
            return response
        }
        val patched = response.copyOf()
        patched[0] = ((queryId shr 8) and 0xFF).toByte()
        patched[1] = (queryId and 0xFF).toByte()
        return patched
    }

    /**
     * Cache the response unless the upstream was switched while it was in flight.
     * Synchronized on the same lock as `switchUpstreamDns`, so the check and the
     * write can’t straddle that switch’s cache clear.
     */
    @Synchronized
    private fun cacheIfCurrent(
        generation: Int,
        query: Message,
        responseMessage: Message,
        response: ByteArray
    ) {
        if (generation != resolverGeneration) {
            Log.d(TAG, "Not caching an answer from the previous upstream")
            return
        }
        dnsCache.put(query, responseMessage, response)
    }

    /**
     * Called whenever an upstream query succeeds, so a caller that warned about
     * an unreachable upstream can retract that once traffic is flowing again.
     */
    @Volatile
    var onUpstreamSuccess: (() -> Unit)? = null

    private fun forwardToUpstream(dnsPayload: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(upstreamDnsUrl)
            .post(dnsPayload.toRequestBody(DOH_CONTENT_TYPE.toMediaType()))
            .header("Accept", DOH_CONTENT_TYPE)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    onUpstreamSuccess?.invoke()
                    // Read with a hard cap rather than `bytes()`, which would buffer
                    // however much the upstream chooses to send (chunked responses
                    // don’t announce a length up front)
                    val source = response.body.source()
                    if (source.request(MAX_DNS_RESPONSE_SIZE + 1)) {
                        Log.w(TAG, "DoH response exceeds $MAX_DNS_RESPONSE_SIZE bytes")
                        return@use null
                    }
                    // A zero-length body would otherwise be wrapped into an empty
                    // DNS packet; treat it as a failure so the caller returns SERVFAIL
                    source.readByteArray().takeIf { it.isNotEmpty() }
                        ?: run {
                            Log.w(TAG, "DoH returned an empty body")
                            null
                        }
                } else {
                    Log.w(TAG, "DoH request failed: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH request error", e)
            null
        }
    }

    private fun createServfailResponse(query: Message): ByteArray {
        val response = Message(query.header.id)
        response.header.setFlag(Flags.QR.toInt())
        response.header.rcode = Rcode.SERVFAIL
        val question = query.question
        if (question != null) {
            response.addRecord(question, Section.QUESTION)
        }
        return response.toWire()
    }

    /**
     * Get DNS cache statistics.
     */
    fun getCacheStats(): DnsCache.CacheStats = dnsCache.getStats()

    /**
     * Clear DNS cache.
     */
    fun clearCache() {
        dnsCache.clear()
        Log.d(TAG, "DNS cache cleared")
    }
}