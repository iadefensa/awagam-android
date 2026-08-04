// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.blocklist.BLOCKLIST_REFRESH_INTERVAL_MS
import com.awagam.android.data.blocklist.ExternalBlocklistConfig
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import com.awagam.android.di.DependencyContainer
import com.awagam.android.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Unit tests for the configuration export.
 * The browser extension honors a per-list `updateInterval` and substitutes its
 * own, shorter default for a missing one, so exports have to state the value
 * rather than let it fall back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelTest {

    private companion object {
        // Only a backstop against a hang: The wait below ends as soon as the
        // ViewModel has seen the stored list, not after a fixed delay
        const val IO_TIMEOUT_MS = 10_000L
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var app: Application

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        DependencyContainer.initialize(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        DependencyContainer.clear()
    }

    // Helper Methods

    /**
     * Store a blocklist, then read the export the settings screen would show.
     * The ViewModel goes through a store so clearing it cancels the collectors
     * its `init` starts, which would otherwise outlive the test.
     */
    private fun exportWith(config: ExternalBlocklistConfig): String {
        runBlocking { ExternalBlocklistManager(app).addBlocklist(config) }

        val store = ViewModelStore()
        return try {
            val factory = viewModelFactory { initializer { SettingsViewModel(app) } }
            val viewModel = ViewModelProvider(store, factory)[SettingsViewModel::class.java]
            runBlocking {
                withTimeout(IO_TIMEOUT_MS) {
                    viewModel.uiState.first { it.blocklists.isNotEmpty() }
                }.exportJson
            }
        } finally {
            store.clear()
        }
    }

    private fun config(updateInterval: Long) = ExternalBlocklistConfig(
        id = "test-id",
        name = "Test",
        url = "https://example.com/blocklist.json",
        updateInterval = updateInterval
    )

    // Export Tests

    @Test
    fun `export states the refresh interval of a list added here`() {
        val exportJson = exportWith(config(BLOCKLIST_REFRESH_INTERVAL_MS))

        assertTrue(
            "Export must name the field, not leave it to the importer’s default: $exportJson",
            exportJson.contains("\"updateInterval\"")
        )
        assertTrue(
            "Export must carry this app’s interval: $exportJson",
            exportJson.contains(BLOCKLIST_REFRESH_INTERVAL_MS.toString())
        )
    }

    @Test
    fun `export preserves the interval an imported list came with`() {
        val imported = TimeUnit.HOURS.toMillis(1)
        val exportJson = exportWith(config(imported))

        assertTrue(
            "An imported interval must survive the round trip: $exportJson",
            exportJson.contains(imported.toString())
        )
    }
}
