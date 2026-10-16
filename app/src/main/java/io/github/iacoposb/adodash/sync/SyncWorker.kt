package io.github.iacoposb.adodash.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.iacoposb.adodash.AdoApp
import java.util.concurrent.TimeUnit

/** Background polling: 15 minutes is the shortest period Android allows. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AdoApp
        val sync = app.sync
        sync.sync()
        // Daily at most, for apps left open on the dashboard for days
        app.updater.checkIfDue()
        return if (sync.state.value.error == null) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("ado-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
