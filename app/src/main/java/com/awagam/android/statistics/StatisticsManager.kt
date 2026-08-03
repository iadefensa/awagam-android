// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.statistics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*

private val Context.statisticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "statistics")

/**
 * Statistics tracker for DNS queries, blocking, and performance metrics.
 * Provides real-time and historical data for the statistics dashboard.
 */
class StatisticsManager(private val context: Context) {

    companion object {
        private val TOTAL_QUERIES = intPreferencesKey("total_queries")
        private val BLOCKED_QUERIES = intPreferencesKey("blocked_queries")
        private val CACHE_HITS = intPreferencesKey("cache_hits")
        private val CACHE_MISSES = intPreferencesKey("cache_misses")
        private val SESSION_START = longPreferencesKey("session_start")
        private val TOTAL_BYTES = longPreferencesKey("total_bytes")
    }

    private val mutex = Mutex()

    // In-memory counters for current session
    private var sessionQueries = 0
    private var sessionBlocked = 0
    private var sessionCacheHits = 0
    private var sessionCacheMisses = 0
    private var sessionBytes = 0L

    private val refreshTrigger = MutableStateFlow(0L)

    /**
     * Statistics data model for UI display.
     */
    data class Statistics(
        val totalQueries: Int,
        val blockedQueries: Int,
        val cacheHits: Int,
        val cacheMisses: Int,
        val sessionQueries: Int,
        val sessionBlocked: Int,
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
        val sessionUptime = if (preferences[SESSION_START] != null) {
            System.currentTimeMillis() - preferences[SESSION_START]!!
        } else 0L

        val totalCacheHits = preferences[CACHE_HITS] ?: 0
        val totalCacheMisses = preferences[CACHE_MISSES] ?: 0
        val cacheHitRate = if ((totalCacheHits + totalCacheMisses) > 0) {
            totalCacheHits.toDouble() / (totalCacheHits + totalCacheMisses)
        } else 0.0

        val blockRate = if (sessionQueries > 0) {
            sessionBlocked.toDouble() / sessionQueries
        } else 0.0

        Statistics(
            totalQueries = preferences[TOTAL_QUERIES] ?: 0,
            blockedQueries = preferences[BLOCKED_QUERIES] ?: 0,
            cacheHits = preferences[CACHE_HITS] ?: 0,
            cacheMisses = preferences[CACHE_MISSES] ?: 0,
            sessionQueries = sessionQueries,
            sessionBlocked = sessionBlocked,
            cacheHitRate = cacheHitRate,
            blockRate = blockRate,
            sessionUptime = sessionUptime,
            totalBytes = preferences[TOTAL_BYTES] ?: 0L
        )
    }

    /**
     * Record a DNS query.
     */
    suspend fun recordQuery(domain: String, bytes: Int) = mutex.withLock {
        sessionQueries++
        sessionBytes += bytes

        context.statisticsDataStore.edit { preferences ->
            preferences[TOTAL_QUERIES] = (preferences[TOTAL_QUERIES] ?: 0) + 1
            preferences[TOTAL_BYTES] = (preferences[TOTAL_BYTES] ?: 0L) + bytes
            if (preferences[SESSION_START] == null) {
                preferences[SESSION_START] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Record a blocked query.
     */
    suspend fun recordBlockedQuery(domain: String) = mutex.withLock {
        sessionBlocked++

        context.statisticsDataStore.edit { preferences ->
            preferences[BLOCKED_QUERIES] = (preferences[BLOCKED_QUERIES] ?: 0) + 1
        }
    }

    /**
     * Record a cache hit.
     */
    suspend fun recordCacheHit() = mutex.withLock {
        sessionCacheHits++

        context.statisticsDataStore.edit { preferences ->
            preferences[CACHE_HITS] = (preferences[CACHE_HITS] ?: 0) + 1
        }
    }

    /**
     * Record a cache miss.
     */
    suspend fun recordCacheMiss() = mutex.withLock {
        sessionCacheMisses++

        context.statisticsDataStore.edit { preferences ->
            preferences[CACHE_MISSES] = (preferences[CACHE_MISSES] ?: 0) + 1
        }
    }

    /**
     * Clear in-memory session counters (caller must hold mutex).
     */
    private fun clearSessionCounters() {
        sessionQueries = 0
        sessionBlocked = 0
        sessionCacheHits = 0
        sessionCacheMisses = 0
        sessionBytes = 0L
    }

    /**
     * Reset session statistics.
     */
    suspend fun resetSession() = mutex.withLock {
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