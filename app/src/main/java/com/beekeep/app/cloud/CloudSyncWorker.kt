package com.beekeep.app.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.beekeep.app.data.LocalHiveRepository

class CloudSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val gateway = SupabaseGateway(applicationContext, LocalHiveRepository(applicationContext), enableRealtime = false)
        return when (val sync = gateway.syncNow()) {
            CloudResult.Success, CloudResult.NotConfigured, CloudResult.NotSignedIn -> Result.success()
            is CloudResult.Failure -> if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
