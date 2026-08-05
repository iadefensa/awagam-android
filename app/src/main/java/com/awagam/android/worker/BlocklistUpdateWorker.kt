// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import com.awagam.android.data.blocklist.ExternalBlocklistConfig
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import com.awagam.android.di.DependencyContainer
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
         * Schedule periodic blocklist update checks.
         * Default: Every 6 hours when connected to network; a check only refetches
         * the lists whose last refresh is older than `BLOCKLIST_REFRESH_INTERVAL_MS`.
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

            Log.d(TAG, "Scheduled blocklist update checks every $intervalHours hours")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting blocklist update")

        return try {
            val manager = ExternalBlocklistManager(applicationContext)
            val before = refreshState(manager.blocklistsFlow.first())

            // Every list refreshes on the same cadence (24 hours), so the more
            // frequent wake only narrows how long a due list stays stale—it
            // never refetches multi-megabyte lists ahead of that cadence
            manager.refreshBlocklistsIfNeeded()

            val configs = manager.blocklistsFlow.first()

            // Refreshing only rewrites the cache files; queries are answered from
            // the repository’s in-memory matcher, which is built by
            // `loadBlocklists()` alone. Without this the tunnel would keep
            // blocking by the previous rules until the app or tunnel restarts.
            if (refreshState(configs) != before) {
                DependencyContainer.getBlocklistRepository().loadBlocklists()
                Log.d(TAG, "Blocklists changed, reloaded matcher")
            }

            // Only actual failures—“warning” also carries an `errorMessage` (a
            // bundle’s skipped imports), but that refresh succeeded
            val failedConfigs = configs.filter { it.enabled && it.status == "error" }
            if (failedConfigs.isNotEmpty()) {
                notifyRefreshFailure(failedConfigs.size)
            }

            Log.d(TAG, "Blocklist update completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Blocklist update failed", e)
            Result.retry()
        } catch (e: OutOfMemoryError) {
            // Parsing and rebuilding hold two full rule sets at once, so a large
            // enough list can exhaust the heap. Letting it propagate would take
            // the process down and with it the tunnel, to skip one refresh.
            // Contained here because the damage is bounded: `loadBlocklists()`
            // publishes the matcher only once it is fully built, so the rules
            // already in force are untouched and keep blocking.
            // Not `retry()`—an immediate second attempt would exhaust the heap
            // again; the next periodic run tries afresh.
            Log.e(TAG, "Out of memory updating blocklists, skipping this run", e)
            Result.failure()
        }
    }

    /**
     * Which enabled lists there are, and when each last refreshed successfully.
     * `lastUpdated` moves only on a refetch that succeeded, so comparing this
     * across the refresh tells whether any rules actually changed. Rebuilding
     * the matcher holds the old and the new rule set in memory at once, which is
     * not worth doing every six hours for lists that mostly did not move.
     */
    private fun refreshState(configs: List<ExternalBlocklistConfig>): Set<Pair<String, String?>> =
        configs.filter { it.enabled }.map { it.id to it.lastUpdated }.toSet()

    private fun notifyRefreshFailure(failedCount: Int) {
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Skipping blocklist failure notification: POST_NOTIFICATIONS not granted")
            return
        }

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