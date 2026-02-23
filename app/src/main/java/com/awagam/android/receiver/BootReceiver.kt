package com.awagam.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.vpn.AWAGAMVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Receiver to optionally start VPN on device boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val preferences = UserPreferences(context)

        runBlocking {
            val autoStart = preferences.autoStartFlow.first()
            if (autoStart) {
                val vpnIntent = Intent(context, AWAGAMVpnService::class.java).apply {
                    action = AWAGAMVpnService.ACTION_START
                }
                context.startForegroundService(vpnIntent)
            }
        }
    }
}
