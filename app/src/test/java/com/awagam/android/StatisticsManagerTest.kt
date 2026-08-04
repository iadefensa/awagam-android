// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.statistics.StatisticsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for StatisticsManager: counting happens in memory on the DNS hot
 * path, and only reaches disk on flush.
 *
 * Uses Robolectric for the Application context the DataStore needs. Lifetime
 * totals are asserted as differences, since the store is shared by every
 * manager built from the same context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatisticsManagerTest {

    private val managers = mutableListOf<StatisticsManager>()

    /**
     * Every manager is closed after the test: each one recording a count arms a
     * delayed flush on its own scope, and one firing during a later test would
     * shift the totals that test is measuring against.
     */
    @After
    fun tearDown() {
        managers.forEach { it.close() }
        managers.clear()
    }

    private fun manager(): StatisticsManager =
        StatisticsManager(ApplicationProvider.getApplicationContext<Application>())
            .also { managers.add(it) }

    private suspend fun stored(): StatisticsManager.Statistics =
        manager().statisticsFlow.first()

    @Test
    fun `recorded queries are visible before they are flushed`() = runTest {
        val statistics = manager()
        val before = statistics.statisticsFlow.first()

        repeat(3) { statistics.recordQuery("example.com", 40) }
        statistics.recordBlockedQuery("ads.example")
        statistics.recordCacheHit()
        statistics.recordCacheMiss()

        val after = statistics.statisticsFlow.first()
        assertEquals(3L, after.totalQueries - before.totalQueries)
        assertEquals(1L, after.blockedQueries - before.blockedQueries)
        assertEquals(1L, after.cacheHits - before.cacheHits)
        assertEquals(1L, after.cacheMisses - before.cacheMisses)
        assertEquals(120L, after.totalBytes - before.totalBytes)
        assertEquals(3L, after.sessionQueries)
    }

    @Test
    fun `flushed counts survive a new manager instance`() = runTest {
        val before = stored()

        val statistics = manager()
        repeat(5) { statistics.recordQuery("example.com", 10) }
        statistics.recordBlockedQuery("ads.example")
        statistics.flush()

        // A second manager reads what was written, without the in-memory deltas
        val after = stored()
        assertEquals(5L, after.totalQueries - before.totalQueries)
        assertEquals(1L, after.blockedQueries - before.blockedQueries)
        assertEquals(50L, after.totalBytes - before.totalBytes)
        assertEquals(0L, after.sessionQueries)
    }

    @Test
    fun `flushing twice does not count the same queries again`() = runTest {
        val before = stored()

        val statistics = manager()
        repeat(4) { statistics.recordQuery("example.com", 25) }
        statistics.flush()
        statistics.flush()

        assertEquals(4L, stored().totalQueries - before.totalQueries)
    }

    @Test
    fun `the flow re-emits on its own so the UI does not freeze between flushes`() = runTest {
        // Recording only touches memory, so without a periodic re-emission the
        // displayed counters would sit still until the next flush
        val emissions = manager().statisticsFlow.take(3).toList()

        assertEquals(3, emissions.size)
    }

    @Test
    fun `resetting the session keeps lifetime totals`() = runTest {
        val statistics = manager()
        val before = statistics.statisticsFlow.first()

        repeat(2) { statistics.recordQuery("example.com", 10) }
        statistics.resetSession()

        val after = statistics.statisticsFlow.first()
        assertEquals(2L, after.totalQueries - before.totalQueries)
        assertEquals(0L, after.sessionQueries)
    }
}
