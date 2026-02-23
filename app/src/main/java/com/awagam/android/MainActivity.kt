package com.awagam.android

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.ui.theme.AWAGAMTheme
import com.awagam.android.ui.screens.HomeScreen
import com.awagam.android.ui.screens.SettingsScreen
import com.awagam.android.ui.screens.StatisticsScreen
import com.awagam.android.vpn.AWAGAMVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Main entry point for the AWAGAM app.
 * Manages VPN permission flow, service lifecycle, and navigation between screens.
 */
class MainActivity : ComponentActivity() {

    private lateinit var userPreferences: UserPreferences

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userPreferences = UserPreferences(this)

        setContent {
            AWAGAMTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("home") }

                    when (currentScreen) {
                        "home" -> HomeScreen(
                            onToggleVpn = { enabled ->
                                if (enabled) {
                                    requestVpnPermission()
                                } else {
                                    stopVpnService()
                                }
                            },
                            onNavigateToSettings = { currentScreen = "settings" },
                            onNavigateToStatistics = { currentScreen = "statistics" }
                        )
                        "settings" -> SettingsScreen(
                            onNavigateBack = { currentScreen = "home" }
                        )
                        "statistics" -> StatisticsScreen(
                            onNavigateBack = { currentScreen = "home" }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the preference says “enabled” but the service isn’t running,
        // restart the VPN from this foreground context.
        // Covers: timer expiry while app was backgrounded, OS killing the service,
        // and any other case where the UI says “active” but the tunnel is gone.
        // Skip if a stop was just requested (async preference write not yet persisted).
        lifecycleScope.launch {
            val isEnabled = userPreferences.isEnabledFlow.first()
            if (isEnabled && !AWAGAMVpnService.isServiceRunning && !AWAGAMVpnService.pendingStop) {
                startVpnService()
            }
        }
    }

    private fun requestVpnPermission() {
        // Have Android’s VPN framework handle conflicts:
        // If another VPN is active, `prepare()` shows a system dialog letting the user choose to switch;
        // if our own stale tunnel persists, `establish()` in the service will replace it
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AWAGAMVpnService::class.java).apply {
            action = AWAGAMVpnService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AWAGAMVpnService::class.java).apply {
            action = AWAGAMVpnService.ACTION_STOP
        }
        startService(intent)
    }
}
