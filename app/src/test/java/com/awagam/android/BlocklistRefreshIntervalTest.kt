// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.blocklist.BLOCKLIST_REFRESH_INTERVAL_MS
import com.awagam.android.data.blocklist.ExternalBlocklistConfig
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Unit tests for `ExternalBlocklistManager.needsUpdate`.
 * Every list refreshes on [BLOCKLIST_REFRESH_INTERVAL_MS]; the `updateInterval`
 * an imported config carries is kept for export but must not change when this
 * device fetches. Separate from `ExternalBlocklistManagerTest` because the
 * manager needs a context, and those tests are plain JVM ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlocklistRefreshIntervalTest {

    private lateinit var manager: ExternalBlocklistManager

    @Before
    fun setup() {
        manager = ExternalBlocklistManager(ApplicationProvider.getApplicationContext<Application>())
    }

    // Helper Methods

    /** Timestamp in the format blocklist configs store, [millisAgo] in the past. */
    private fun timestamp(millisAgo: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(System.currentTimeMillis() - millisAgo))
    }

    private fun config(
        lastUpdated: String?,
        updateInterval: Long = BLOCKLIST_REFRESH_INTERVAL_MS
    ) = ExternalBlocklistConfig(
        id = "test-id",
        name = "Test",
        url = "https://example.com/blocklist.json",
        lastUpdated = lastUpdated,
        updateInterval = updateInterval
    )

    // Interval Tests

    @Test
    fun `a list that was never refreshed is due`() {
        assertTrue(manager.needsUpdate(config(lastUpdated = null)))
    }

    @Test
    fun `a list refreshed longer ago than the interval is due`() {
        val ago = BLOCKLIST_REFRESH_INTERVAL_MS + TimeUnit.HOURS.toMillis(1)
        assertTrue(manager.needsUpdate(config(timestamp(ago))))
    }

    @Test
    fun `a list refreshed within the interval is not due`() {
        assertTrue(
            "Test assumes an interval longer than an hour",
            BLOCKLIST_REFRESH_INTERVAL_MS > TimeUnit.HOURS.toMillis(1)
        )
        assertFalse(manager.needsUpdate(config(timestamp(TimeUnit.HOURS.toMillis(1)))))
    }

    @Test
    fun `an imported short interval does not make a list due early`() {
        val config = config(
            lastUpdated = timestamp(TimeUnit.HOURS.toMillis(1)),
            updateInterval = TimeUnit.MINUTES.toMillis(1)
        )
        assertFalse("An imported config must not raise the fetch rate", manager.needsUpdate(config))
    }

    @Test
    fun `an imported long interval does not keep a list from being due`() {
        val ago = BLOCKLIST_REFRESH_INTERVAL_MS + TimeUnit.HOURS.toMillis(1)
        val config = config(
            lastUpdated = timestamp(ago),
            updateInterval = TimeUnit.DAYS.toMillis(30)
        )
        assertTrue("An imported config must not stall refreshes", manager.needsUpdate(config))
    }

    @Test
    fun `an unparseable timestamp makes a list due`() {
        assertTrue(manager.needsUpdate(config(lastUpdated = "not a timestamp")))
    }
}