package com.awagam.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.awagam.android.di.DependencyContainer
import com.awagam.android.worker.BlocklistUpdateWorker
import com.awagam.android.worker.VpnWatchdogWorker

/**
 * Application class for AWAGAM.
 * Initializes the dependency container, notification channel, and background workers.
 */
class AWAGAMApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize dependency container
        DependencyContainer.initialize(this)
        createNotificationChannel()
        scheduleBlocklistUpdates()
        scheduleVpnWatchdog()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when AWAGAM DNS filtering is active"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun scheduleBlocklistUpdates() {
        // Schedule periodic blocklist updates every 6 hours
        BlocklistUpdateWorker.schedule(this, intervalHours = 6)
    }

    private fun scheduleVpnWatchdog() {
        VpnWatchdogWorker.schedule(this)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "awagam_vpn_status"
        const val NOTIFICATION_ID = 1
    }
}
