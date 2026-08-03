// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.statistics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val Context.statisticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "statistics")

/**
 * Statistics tracker for DNS queries, blocking, and performance metrics.
 * Provides real-time and historical data for the statistics dashboard.
 *
 * Recording happens on the DNS hot path, so counters are kept in memory as
 * deltas and flushed to disk at most once per [FLUSH_INTERVAL_MS]; a device
 * issues thousands of DNS queries per minute, and one file write per query
 * would cost battery, flash wear, and query latency.
 */
class StatisticsManager(private val context: Context) {

    companion object {
        private val TOTAL_QUERIES = longPreferencesKey("total_queries")
        private val BLOCKED_QUERIES = longPreferencesKey("blocked_queries")
        private val CACHE_HITS = longPreferencesKey("cache_hits")
        private val CACHE_MISSES = longPreferencesKey("cache_misses")
        private val SESSION_START = longPreferencesKey("session_start")
        private val TOTAL_BYTES = longPreferencesKey("total_bytes")

        private val FLUSH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)
    }

    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Counts recorded since the last flush, added to the stored totals on flush
    private val pendingQueries = AtomicLong(0)
    private val pendingBlocked = AtomicLong(0)
    private val pendingCacheHits = AtomicLong(0)
    private val pendingCacheMisses = AtomicLong(0)
    private val pendingBytes = AtomicLong(0)

    // In-memory counters for current session
    private val sessionQueries = AtomicLong(0)
    private val sessionBlocked = AtomicLong(0)
    private val sessionCacheHits = AtomicLong(0)
    private val sessionCacheMisses = AtomicLong(0)
    private val sessionBytes = AtomicLong(0)

    // Non-null while a flush is scheduled; keeps recording from queuing one per query
    @Volatile
    private var flushJob: Job? = null

    private val refreshTrigger = MutableStateFlow(0L)

    /**
     * Statistics data model for UI display.
     */
    data class Statistics(
        val totalQueries: Long,
        val blockedQueries: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
        val sessionQueries: Long,
        val sessionBlocked: Long,
        val cacheHitRate: Double,
        val blockRate: Double,
        val sessionUptime: Long,
        val totalBytes: Long,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    /**
     * DNS query statistics for top domains.
     */
    data class DomainStats(
        val domain: String,
        val queryCount: Int,
        val blockedCount: Int,
        val lastQueried: Long
    )

    // Flow of current statistics
    val statisticsFlow: Flow<Statistics> = combine(
        context.statisticsDataStore.data,
        refreshTrigger
    ) { preferences, _ ->
        val storedSessionStart = preferences.counter(SESSION_START)
        val sessionUptime = if (storedSessionStart > 0L) {
            System.currentTimeMillis() - storedSessionStart
        } else 0L

        // Add the not-yet-flushed deltas so the UI reflects the current state
        val totalQueries = preferences.counter(TOTAL_QUERIES) + pendingQueries.get()
        val totalBlocked = preferences.counter(BLOCKED_QUERIES) + pendingBlocked.get()
        val totalCacheHits = preferences.counter(CACHE_HITS) + pendingCacheHits.get()
        val totalCacheMisses = preferences.counter(CACHE_MISSES) + pendingCacheMisses.get()

        val cacheHitRate = if ((totalCacheHits + totalCacheMisses) > 0) {
            totalCacheHits.toDouble() / (totalCacheHits + totalCacheMisses)
        } else 0.0

        val queries = sessionQueries.get()
        val blocked = sessionBlocked.get()
        val blockRate = if (queries > 0) blocked.toDouble() / queries else 0.0

        Statistics(
            totalQueries = totalQueries,
            blockedQueries = totalBlocked,
            cacheHits = totalCacheHits,
            cacheMisses = totalCacheMisses,
            sessionQueries = queries,
            sessionBlocked = blocked,
            cacheHitRate = cacheHitRate,
            blockRate = blockRate,
            sessionUptime = sessionUptime,
            totalBytes = preferences.counter(TOTAL_BYTES) + pendingBytes.get()
        )
    }

    /**
     * Read a counter, tolerating values written as `Int` by earlier versions.
     * `Preferences.Key` compares by name, so the stored entry is found either
     * way and is rewritten as a `Long` by the next flush.
     */
    private fun Preferences.counter(key: Preferences.Key<Long>): Long =
        when (val value = asMap()[key]) {
            is Long -> value
            is Int -> value.toLong()
            else -> 0L
        }

    /**
     * Record a DNS query.
     */
    fun recordQuery(domain: String, bytes: Int) {
        sessionQueries.incrementAndGet()
        sessionBytes.addAndGet(bytes.toLong())
        pendingQueries.incrementAndGet()
        pendingBytes.addAndGet(bytes.toLong())
        scheduleFlush()
    }

    /**
     * Record a blocked query.
     */
    fun recordBlockedQuery(domain: String) {
        sessionBlocked.incrementAndGet()
        pendingBlocked.incrementAndGet()
        scheduleFlush()
    }

    /**
     * Record a cache hit.
     */
    fun recordCacheHit() {
        sessionCacheHits.incrementAndGet()
        pendingCacheHits.incrementAndGet()
        scheduleFlush()
    }

    /**
     * Record a cache miss.
     */
    fun recordCacheMiss() {
        sessionCacheMisses.incrementAndGet()
        pendingCacheMisses.incrementAndGet()
        scheduleFlush()
    }

    /**
     * Schedule a flush unless one is already pending. Deferring instead of
     * running a fixed timer means an idle app never wakes up to write nothing.
     */
    private fun scheduleFlush() {
        if (flushJob != null) return
        synchronized(this) {
            if (flushJob != null) return
            flushJob = scope.launch {
                try {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                } finally {
                    // Held until the write is done, so queries arriving during it
                    // batch into the next flush instead of starting another one
                    synchronized(this@StatisticsManager) { flushJob = null }
                    // Those queries would otherwise wait for the next one to be
                    // recorded, which on a quiet device can be a long time
                    if (scope.isActive && hasPendingCounts()) scheduleFlush()
                }
            }
        }
    }

    private fun hasPendingCounts(): Boolean =
        pendingQueries.get() > 0 || pendingBlocked.get() > 0 || pendingCacheHits.get() > 0 ||
            pendingCacheMisses.get() > 0 || pendingBytes.get() > 0

    /**
     * Write the pending counts to disk. Called periodically while queries come
     * in, and by the VPN service when it stops so the last batch isn’t lost.
     */
    suspend fun flush() = withContext(NonCancellable) {
        flushMutex.withLock {
            // Claim the deltas up front; concurrent recording accumulates into
            // the next batch rather than being dropped by this write
            val queries = pendingQueries.getAndSet(0)
            val blocked = pendingBlocked.getAndSet(0)
            val hits = pendingCacheHits.getAndSet(0)
            val misses = pendingCacheMisses.getAndSet(0)
            val bytes = pendingBytes.getAndSet(0)

            if (queries == 0L && blocked == 0L && hits == 0L && misses == 0L && bytes == 0L) {
                return@withLock
            }

            try {
                context.statisticsDataStore.edit { preferences ->
                    preferences[TOTAL_QUERIES] = preferences.counter(TOTAL_QUERIES) + queries
                    preferences[BLOCKED_QUERIES] = preferences.counter(BLOCKED_QUERIES) + blocked
                    preferences[CACHE_HITS] = preferences.counter(CACHE_HITS) + hits
                    preferences[CACHE_MISSES] = preferences.counter(CACHE_MISSES) + misses
                    preferences[TOTAL_BYTES] = preferences.counter(TOTAL_BYTES) + bytes
                    if (preferences.counter(SESSION_START) == 0L) {
                        preferences[SESSION_START] = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                // Put the deltas back so a failed write doesn’t lose them
                pendingQueries.addAndGet(queries)
                pendingBlocked.addAndGet(blocked)
                pendingCacheHits.addAndGet(hits)
                pendingCacheMisses.addAndGet(misses)
                pendingBytes.addAndGet(bytes)
                throw e
            }
        }
    }

    /**
     * Flush without waiting for it, for callers that can’t suspend (such as
     * `Service.onDestroy()`). Runs on this manager’s own scope, which outlives
     * the caller’s.
     */
    fun flushNow() {
        scope.launch { flush() }
    }

    /**
     * Stop the periodic flushing and abandon whatever hasn’t been written yet.
     * The manager is an application-lifetime singleton in production; this is
     * for tests and for anything else that replaces the instance, so a pending
     * flush from a discarded manager can’t write during someone else’s work.
     * Call [flush] first if the counts still matter.
     */
    fun close() {
        scope.cancel()
        synchronized(this) { flushJob = null }
    }

    /**
     * Clear in-memory session counters.
     */
    private fun clearSessionCounters() {
        sessionQueries.set(0)
        sessionBlocked.set(0)
        sessionCacheHits.set(0)
        sessionCacheMisses.set(0)
        sessionBytes.set(0)
    }

    /**
     * Reset session statistics.
     */
    suspend fun resetSession() {
        // Flush first so pending queries land in the lifetime totals
        flush()
        clearSessionCounters()

        context.statisticsDataStore.edit { preferences ->
            preferences[SESSION_START] = System.currentTimeMillis()
        }
    }

    /**
     * Trigger a re-emission of `statisticsFlow` to refresh displayed values.
     */
    fun refresh() {
        refreshTrigger.value = System.currentTimeMillis()
    }

    /**
     * Get formatted uptime string.
     */
    fun formatUptime(milliseconds: Long): String {
        val hours = milliseconds / (1000 * 60 * 60)
        val minutes = (milliseconds % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (milliseconds % (1000 * 60)) / 1000

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Get formatted data usage string.
     */
    fun formatDataUsage(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.2f MB".format(mb)
            kb >= 1 -> "%.2f KB".format(kb)
            else -> "$bytes B"
        }
    }
}