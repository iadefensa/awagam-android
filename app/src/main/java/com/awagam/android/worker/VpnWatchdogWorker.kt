// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.worker

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.vpn.AWAGAMVpnService
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic watchdog that restarts the VPN service if it was killed by the system.
 * Runs every 15 minutes (WorkManager minimum) with no constraints.
 */
class VpnWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VpnWatchdogWorker"
        private const val WORK_NAME = "vpn_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<VpnWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Scheduled VPN watchdog every 15 minutes")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Canceled VPN watchdog")
        }
    }

    override suspend fun doWork(): Result {
        val preferences = UserPreferences(applicationContext)
        val isEnabled = preferences.isEnabledFlow.first()

        if (isEnabled && !AWAGAMVpnService.isServiceRunning && !AWAGAMVpnService.pendingStop) {
            Log.d(TAG, "VPN should be running but isn't — restarting")
            try {
                val intent = Intent(applicationContext, AWAGAMVpnService::class.java).apply {
                    action = AWAGAMVpnService.ACTION_START
                }
                applicationContext.startForegroundService(intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    Log.w(TAG, "Cannot restart VPN from background — battery optimization not exempted")
                } else {
                    Log.e(TAG, "Failed to restart VPN service", e)
                }
            }
        }

        return Result.success()
    }
}