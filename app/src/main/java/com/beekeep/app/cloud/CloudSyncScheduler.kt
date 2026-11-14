package com.beekeep.app.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CloudSyncScheduler {
    private const val PERIODIC_NAME = "beekeep-cloud-sync"
    private const val NOW_NAME = "beekeep-cloud-sync-now"
    private const val SYNC_WORK_TAG = "beekeep-cloud-sync-all"

    fun schedule(context: Context) {
        if (TrustedDeviceLocalSessionState.isReplaced(context)) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(SYNC_WORK_TAG)
                .build()
        )
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(NOW_NAME)
        workManager.cancelUniqueWork(PERIODIC_NAME)
        workManager.cancelAllWorkByTag(SYNC_WORK_TAG)
    }

    fun runNow(context: Context) {
        if (TrustedDeviceLocalSessionState.isReplaced(context)) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(constraints)
                .addTag(SYNC_WORK_TAG)
                .build()
        )
    }
}
