// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.di.DependencyContainer
import com.awagam.android.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for HomeViewModel’s temporary disable flow.
 *
 * Uses Robolectric for Application context (DataStore) and an
 * UnconfinedTestDispatcher so viewModelScope coroutines execute eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    // Owns the ViewModel so `tearDown` can end its scope; a plain constructor
    // call leaves no way to do that
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: HomeViewModel
    private lateinit var userPreferences: UserPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        DependencyContainer.initialize(app)
        userPreferences = UserPreferences(app)
        // The DataStore instance is cached for the life of the process, so what
        // one test writes the next one reads—start each from a known-off state,
        // or a leftover `disable_until` restarts a countdown during `init` and a
        // leftover `is_enabled` makes the ViewModel believe protection is up
        runBlocking {
            userPreferences.setEnabled(false)
            userPreferences.clearTemporaryDisable()
            userPreferences.clearVpnError()
        }
        viewModel = ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[HomeViewModel::class.java]
        waitForIo()
    }

    @After
    fun tearDown() {
        // Nothing calls `onCleared` for us, so without this every test leaves its
        // ViewModel’s collectors, countdown, and start timeout running against the
        // shared DataStore for the rest of the class
        viewModelStore.clear()
        Dispatchers.resetMain()
        DependencyContainer.clear()
    }

    // Helper Methods

    /** Wait for DataStore IO operations to complete on real threads. */
    private fun waitForIo() {
        Thread.sleep(500)
    }

    // Initial State Tests

    @Test
    fun `initial UI state has correct defaults`() {
        val state = viewModel.uiState.value
        assertFalse("VPN should be disabled by default", state.isEnabled)
        assertFalse("Should not be temporarily disabled", state.isTemporarilyDisabled)
        assertEquals("Countdown should be 0", 0, state.disableCountdownSeconds)
        assertNull("No VPN error by default", state.vpnError)
    }

    // Temporary Disable Tests

    @Test
    fun `temporaryDisable sets correct UI state`() {
        viewModel.temporaryDisable(5)
        waitForIo()

        val state = viewModel.uiState.value
        assertFalse("VPN should be disabled", state.isEnabled)
        assertTrue("Should be temporarily disabled", state.isTemporarilyDisabled)
        // Countdown ticks in real time, so allow a small margin
        assertTrue(
            "Countdown should be ~300s but was ${state.disableCountdownSeconds}",
            state.disableCountdownSeconds in 295..300
        )
    }

    @Test
    fun `cancelTemporaryDisable clears state`() {
        viewModel.temporaryDisable(5)
        waitForIo()
        assertTrue(viewModel.uiState.value.isTemporarilyDisabled)

        viewModel.cancelTemporaryDisable()
        waitForIo()

        val state = viewModel.uiState.value
        assertFalse("Temporary disable should be cleared", state.isTemporarilyDisabled)
        assertEquals("Countdown should be 0", 0, state.disableCountdownSeconds)
    }

    @Test
    fun `setEnabled true clears temporary disable`() {
        viewModel.temporaryDisable(5)
        waitForIo()
        assertTrue(viewModel.uiState.value.isTemporarilyDisabled)

        viewModel.setEnabled(true)
        waitForIo()

        val state = viewModel.uiState.value
        assertFalse("Temporary disable should be cleared", state.isTemporarilyDisabled)
        assertEquals("Countdown should be 0", 0, state.disableCountdownSeconds)
    }

    // Duration Tests

    @Test
    fun `temporaryDisable with different durations sets correct countdown`() {
        viewModel.temporaryDisable(15)
        waitForIo()
        assertTrue(
            "Countdown should be ~900s but was ${viewModel.uiState.value.disableCountdownSeconds}",
            viewModel.uiState.value.disableCountdownSeconds in 895..900
        )

        viewModel.cancelTemporaryDisable()
        waitForIo()

        viewModel.temporaryDisable(60)
        waitForIo()
        assertTrue(
            "Countdown should be ~3600s but was ${viewModel.uiState.value.disableCountdownSeconds}",
            viewModel.uiState.value.disableCountdownSeconds in 3595..3600
        )
    }

    // Start Request Tests
    //
    // The switch reads `isEnabled`, which only the VPN service writes, so a start
    // that never completes has to show and then withdraw a pending state—without
    // it the toggle sits at “off” however often it is tapped

    @Test
    fun `startRequested shows the pending state`() {
        viewModel.startRequested()

        assertTrue("Switch should move on tap", viewModel.uiState.value.isStarting)
    }

    @Test
    fun `setEnabled false clears the pending state`() {
        viewModel.startRequested()
        assertTrue(viewModel.uiState.value.isStarting)

        viewModel.setEnabled(false)
        waitForIo()

        assertFalse("Turning off cancels a pending start", viewModel.uiState.value.isStarting)
    }

    @Test
    fun `a failed start clears the pending state`() {
        viewModel.startRequested()
        waitForIo()

        runBlocking { userPreferences.setVpnError(UserPreferences.VPN_ERROR_ANOTHER_VPN) }
        waitForIo()

        assertFalse("A start failure withdraws the pending state", viewModel.uiState.value.isStarting)
    }

    @Test
    fun `a DoH error leaves the pending state alone`() {
        viewModel.startRequested()
        waitForIo()

        // Reported by a tunnel that did come up, so it says nothing about the start
        runBlocking {
            userPreferences.setVpnError("${UserPreferences.VPN_ERROR_DOH_FAILED}:timeout")
        }
        waitForIo()

        assertTrue("An upstream error is not a start failure", viewModel.uiState.value.isStarting)
    }

    // The 20-second timeout itself is not covered: Driving it needs the virtual
    // clock advanced, and doing that here wakes the countdown loops of ViewModels
    // earlier tests left running, which hangs the whole class. Testing it would
    // mean making the timeout injectable.
}