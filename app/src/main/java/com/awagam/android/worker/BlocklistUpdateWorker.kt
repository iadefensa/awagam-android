package com.awagam.android.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.awagam.android.data.blocklist.ExternalBlocklistManager
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
            Log.d(TAG, "Blocklist update completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Blocklist update failed", e)
            Result.retry()
        }
    }
}