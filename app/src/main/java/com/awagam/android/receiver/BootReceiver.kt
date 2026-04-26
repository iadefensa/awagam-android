package com.awagam.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.vpn.AWAGAMVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receiver to optionally start VPN on device boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoStart = UserPreferences(context).autoStartFlow.first()
                if (autoStart) {
                    context.startForegroundService(
                        Intent(context, AWAGAMVpnService::class.java).apply {
                            action = AWAGAMVpnService.ACTION_START
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read auto-start preference on boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
