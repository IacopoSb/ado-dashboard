package io.github.iacoposb.adodash.sync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.iacoposb.adodash.AdoApp
import io.github.iacoposb.adodash.R
import java.util.concurrent.TimeUnit

/**
 * Background polling: 15 minutes is the shortest period Android allows. Also a single sync
 * at once, asked from a widget.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AdoApp
        val sync = app.sync
        val now = inputData.getBoolean(KEY_NOW, false)
        try {
            sync.sync()
        } finally {
            when {
                now -> app.widgets.refreshEnded()
                // No news, but the widgets' ages and staleness move on
                sync.state.value.error != null -> app.widgets.updateAll()
            }
        }
        if (now) return Result.success()
        // Daily at most, for apps left open on the dashboard for days
        app.updater.checkIfDue()
        return if (sync.state.value.error == null) Result.success() else Result.retry()
    }

    /** Before Android 12 expedited work runs as a foreground service, with this notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.dashboard_syncing))
            .setSilent(true)
            .build()
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    companion object {
        private const val KEY_NOW = "now"
        private const val FOREGROUND_ID = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("ado-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** A sync as soon as possible, expedited when the app's quota allows. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(connected())
                .setInputData(workDataOf(KEY_NOW to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ado-sync-now", ExistingWorkPolicy.KEEP, request)
        }

        private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
