package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.di.DependencyContainer
import com.awagam.android.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        DependencyContainer.initialize(app)
        viewModel = HomeViewModel(app)
        waitForIo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        DependencyContainer.clear()
    }

    // --- Helper Methods ---

    /** Wait for DataStore IO operations to complete on real threads. */
    private fun waitForIo() {
        Thread.sleep(500)
    }

    // --- Initial State Tests ---

    @Test
    fun `initial UI state has correct defaults`() {
        val state = viewModel.uiState.value
        assertFalse("VPN should be disabled by default", state.isEnabled)
        assertFalse("Should not be temporarily disabled", state.isTemporarilyDisabled)
        assertEquals("Countdown should be 0", 0, state.disableCountdownSeconds)
        assertNull("No VPN error by default", state.vpnError)
    }

    // --- Temporary Disable Tests ---

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

    // --- Duration Tests ---

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
}
