package com.beekeep.app

import android.content.Context
import com.beekeep.app.cloud.CloudSyncScheduler
import com.beekeep.app.data.LocalHiveRepository

/**
 * Queue a locally persisted inspection-note draft for whichever workspace is currently active,
 * then ask WorkManager to sync it as soon as network is available.
 */
internal suspend fun queueInspectionNoteDraft(
    context: Context,
    hiveId: Long,
    notes: String,
    recordedAt: Long?,
    updatedAt: Long
): Long {
    val repository = LocalHiveRepository(context.applicationContext)
    val savedAt = repository.saveInspectionNoteDraft(hiveId, notes, recordedAt, updatedAt)
    CloudSyncScheduler.runNow(context.applicationContext)
    return savedAt
}
