package com.awagam.android.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.awagam.android.AWAGAMApplication
import com.awagam.android.MainActivity
import com.awagam.android.R
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically updates blocklists.
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BlocklistUpdateWorker"
        private const val WORK_NAME = "blocklist_update"

        /**
         * Schedule periodic blocklist updates.
         * Default: every 6 hours when connected to network.
         */
        fun schedule(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Scheduled blocklist updates every $intervalHours hours")
        }

        /**
         * Cancel scheduled updates.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Canceled blocklist updates")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting blocklist update")

        return try {
            val manager = ExternalBlocklistManager(applicationContext)
            manager.refreshAllBlocklists()

            val failedConfigs = manager.blocklistsFlow.first()
                .filter { it.enabled && it.errorMessage != null }
            if (failedConfigs.isNotEmpty()) {
                notifyRefreshFailure(failedConfigs.size)
            }

            Log.d(TAG, "Blocklist update completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Blocklist update failed", e)
            Result.retry()
        }
    }

    private fun notifyRefreshFailure(failedCount: Int) {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val message = applicationContext.resources.getQuantityString(
            R.plurals.blocklist_update_failed_text, failedCount, failedCount
        )
        val notification = NotificationCompat.Builder(applicationContext, AWAGAMApplication.BLOCKLIST_ERROR_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.blocklist_update_failed_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(AWAGAMApplication.BLOCKLIST_ERROR_NOTIFICATION_ID, notification)
    }
}