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
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val vpnChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when AWAGAM DNS filtering is active"
            setShowBadge(false)
        }

        val errorChannel = NotificationChannel(
            BLOCKLIST_ERROR_CHANNEL_ID,
            getString(R.string.blocklist_error_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.blocklist_error_channel_description)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannels(listOf(vpnChannel, errorChannel))
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
        const val BLOCKLIST_ERROR_CHANNEL_ID = "awagam_blocklist_errors"
        const val NOTIFICATION_ID = 1
        const val BLOCKLIST_ERROR_NOTIFICATION_ID = 2
    }
}
