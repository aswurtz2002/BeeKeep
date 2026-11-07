package com.beekeep.app.cloud

/**
 * Decides whether personal sync must pause for explicit local-data review.
 *
 * Cloud uploads are held for new or remapped rows, ambiguous collisions, and any queued
 * personal writes or drafts. Exact duplicates and deliberately preserved local-only tombstones
 * do not need uploading and therefore do not create a hold by themselves.
 */
internal object LocalDataAttachmentReviewPolicy {
    fun requiresReview(plan: LocalDataAttachmentMergePlan): Boolean =
        plan.newRecordCount > 0 ||
            plan.remapCount > 0 ||
            plan.reviewRequiredCount > 0 ||
            plan.pendingSyncWriteCount > 0 ||
            plan.pendingDeleteCount > 0 ||
            plan.pendingActionBatchCount > 0 ||
            plan.pendingWorkspaceActionCount > 0 ||
            plan.noteDraftCount > 0 ||
            plan.formDraftCount > 0
}
