package com.awagam.android.dns

import android.util.LruCache
import org.xbill.DNS.Message
import java.util.concurrent.TimeUnit

/**
 * DNS response cache to reduce upstream queries and improve performance.
 * Uses LRU eviction policy and respects DNS TTL values.
 */
class DnsCache {

    companion object {
        private const val DEFAULT_MAX_SIZE = 1000
        private const val MIN_TTL_SECONDS = 60L // Minimum cache time
        private val MAX_TTL_SECONDS = TimeUnit.HOURS.toSeconds(24) // Maximum cache time
    }

    private val cache = LruCache<String, CacheEntry>(DEFAULT_MAX_SIZE)

    /**
     * Represents a cached DNS response with TTL tracking.
     */
    data class CacheEntry(
        val response: ByteArray,
        val timestamp: Long,
        val ttlSeconds: Long
    ) {
        fun isExpired(): Boolean {
            val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
            return ageSeconds >= ttlSeconds
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CacheEntry
            return response.contentEquals(other.response)
        }

        override fun hashCode(): Int {
            return response.contentHashCode()
        }
    }

    /**
     * Generate cache key from DNS query.
     */
    private fun generateCacheKey(query: Message): String {
        val question = query.question ?: return ""
        val name = question.name.toString(true)
        val type = question.type
        return "$name:$type"
    }

    /**
     * Get cached DNS response if available and not expired.
     */
    fun get(query: Message): ByteArray? {
        val key = generateCacheKey(query)
        val entry = cache.get(key) ?: return null

        return if (entry.isExpired()) {
            cache.remove(key)
            null
        } else {
            entry.response
        }
    }

    /**
     * Cache DNS response with appropriate TTL.
     */
    fun put(query: Message, response: Message) {
        val key = generateCacheKey(query)
        val ttl = getEffectiveTtl(response)
        val entry = CacheEntry(
            response = response.toWire(),
            timestamp = System.currentTimeMillis(),
            ttlSeconds = ttl
        )
        cache.put(key, entry)
    }

    /**
     * Get effective TTL from DNS response, clamped to reasonable bounds.
     */
    private fun getEffectiveTtl(response: Message): Long {
        // Find minimum TTL from answer records
        val answers = response.getSection(org.xbill.DNS.Section.ANSWER)
        val minTtl = answers?.minOfOrNull { record ->
            if (record.ttl > 0) record.ttl else MIN_TTL_SECONDS
        } ?: MIN_TTL_SECONDS

        // Clamp to reasonable bounds
        return minTtl.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
    }

    /**
     * Clear all cached entries.
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * Get cache statistics.
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size(),
            maxSize = cache.maxSize(),
            hitCount = cache.hitCount(),
            missCount = cache.missCount(),
            hitRate = if (cache.hitCount() + cache.missCount() > 0) {
                cache.hitCount().toDouble() / (cache.hitCount() + cache.missCount())
            } else 0.0
        )
    }

    /**
     * Cache statistics for monitoring.
     */
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitCount: Int,
        val missCount: Int,
        val hitRate: Double
    )
}