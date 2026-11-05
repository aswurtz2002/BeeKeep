package com.beekeep.app.cloud

import com.beekeep.app.data.LocalDataAttachmentSnapshotInfo

/**
 * Read-only inventory captured while personal cloud sync is held for account review.
 * cloudInventoryError means the local recovery snapshot is valid but cloud inventory could
 * not be confirmed; an empty cloud count must never be inferred from that condition.
 */
internal data class LocalDataAttachmentPreview(
    val accountId: String,
    val accountEmail: String,
    val capturedAt: Long,
    val localSnapshot: LocalDataAttachmentSnapshotInfo,
    val cloudRecordCounts: Map<String, Int>,
    val cloudDeletedCounts: Map<String, Int>,
    val cloudInventoryError: String? = null
) {
    val cloudRecordsTotal: Int get() = cloudRecordCounts.values.sum()
    val cloudDeletedTotal: Int get() = cloudDeletedCounts.values.sum()
}
