// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import com.awagam.android.di.DependencyContainer
import com.awagam.android.statistics.StatisticsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isEnabled: Boolean = false,
    val tldCount: Int = 0,
    val domainCount: Int = 0,
    val blockedCount: Long = 0,
    val isTemporarilyDisabled: Boolean = false,
    val disableCountdownSeconds: Int = 0,
    val vpnError: String? = null,
    val batteryPromptDismissed: Boolean = false
)

/**
 * ViewModel for the home screen.
 * Manages VPN toggle state, blocklist statistics, and temporary disable timer.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val userPreferences = UserPreferences(application)
    private val blocklistRepository = DependencyContainer.getBlocklistRepository()
    private val externalBlocklistManager = ExternalBlocklistManager(application)
    private val statisticsManager = DependencyContainer.getStatisticsManager()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Event channel to signal UI to restart VPN when temporary disable expires
    private val _restartVpnEvent = Channel<Unit>(Channel.BUFFERED)
    val restartVpnEvent = _restartVpnEvent.receiveAsFlow()

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            userPreferences.isEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            blocklistRepository.blocklistStats.collect { stats ->
                _uiState.update {
                    it.copy(
                        tldCount = stats.tldCount,
                        domainCount = stats.domainCount
                    )
                }
            }
        }

        viewModelScope.launch {
            statisticsManager.statisticsFlow.collect { stats ->
                _uiState.update { it.copy(blockedCount = stats.blockedQueries) }
            }
        }

        viewModelScope.launch {
            userPreferences.vpnErrorFlow.collect { error ->
                _uiState.update { it.copy(vpnError = error) }
            }
        }

        viewModelScope.launch {
            userPreferences.batteryPromptDismissedFlow.collect { dismissed ->
                _uiState.update { it.copy(batteryPromptDismissed = dismissed) }
            }
        }

        // Load blocklists to populate stats
        viewModelScope.launch {
            blocklistRepository.loadBlocklists()
        }

        // Reload blocklists when external blocklist configs change
        viewModelScope.launch {
            externalBlocklistManager.blocklistsFlow.collect {
                blocklistRepository.loadBlocklists()
            }
        }

        // Check for existing temporary disable on init
        viewModelScope.launch {
            checkTemporaryDisable()
        }
    }

    private suspend fun checkTemporaryDisable() {
        val disableUntil = userPreferences.disableUntilFlow.first()
        if (disableUntil > 0) {
            val remaining = ((disableUntil - System.currentTimeMillis()) / 1000).toInt()
            if (remaining > 0) {
                _uiState.update {
                    it.copy(
                        isTemporarilyDisabled = true,
                        disableCountdownSeconds = remaining
                    )
                }
                startCountdown(disableUntil)
            } else {
                // Timer expired while app was closed, re-enable
                userPreferences.clearTemporaryDisable()
                userPreferences.setEnabled(true)
                _restartVpnEvent.send(Unit)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Clear any temporary disable when manually toggling
            if (enabled) {
                userPreferences.clearTemporaryDisable()
                userPreferences.clearVpnError()
                countdownJob?.cancel()
                _uiState.update {
                    it.copy(isTemporarilyDisabled = false, disableCountdownSeconds = 0, vpnError = null)
                }
            }
            userPreferences.setEnabled(enabled)
        }
    }

    fun clearVpnError() {
        viewModelScope.launch {
            userPreferences.clearVpnError()
        }
    }

    fun dismissBatteryPrompt() {
        viewModelScope.launch {
            userPreferences.setBatteryPromptDismissed(true)
        }
    }

    fun temporaryDisable(minutes: Int) {
        viewModelScope.launch {
            val disableUntil = System.currentTimeMillis() + (minutes * 60 * 1000L)
            userPreferences.setTemporaryDisable(disableUntil)
            userPreferences.setEnabled(false)

            _uiState.update {
                it.copy(
                    isEnabled = false,
                    isTemporarilyDisabled = true,
                    disableCountdownSeconds = minutes * 60
                )
            }

            startCountdown(disableUntil)
        }
    }

    private fun startCountdown(disableUntil: Long) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                val remaining = ((disableUntil - System.currentTimeMillis()) / 1000).toInt()
                if (remaining <= 0) {
                    // Timer expired, re-enable
                    userPreferences.clearTemporaryDisable()
                    userPreferences.setEnabled(true)
                    _uiState.update {
                        it.copy(
                            isEnabled = true,
                            isTemporarilyDisabled = false,
                            disableCountdownSeconds = 0
                        )
                    }
                    _restartVpnEvent.send(Unit)
                    break
                }

                _uiState.update { it.copy(disableCountdownSeconds = remaining) }
                delay(1000)
            }
        }
    }

    fun cancelTemporaryDisable() {
        viewModelScope.launch {
            countdownJob?.cancel()
            userPreferences.clearTemporaryDisable()
            // Don’t set `enabled=true` here—let the VPN service do it after successful start
            // This prevents race condition with VPN detection
            _uiState.update {
                it.copy(
                    isTemporarilyDisabled = false,
                    disableCountdownSeconds = 0
                )
            }
        }
    }
}