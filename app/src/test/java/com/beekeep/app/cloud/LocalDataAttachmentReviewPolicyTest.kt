package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataAttachmentReviewPolicyTest {
    private fun plan(
        disposition: LocalDataAttachmentDisposition? = null,
        taskCount: Int = 0,
        noteDraftCount: Int = 0,
        formDraftCount: Int = 0,
        pendingSyncWriteCount: Int = 0,
        pendingDeleteCount: Int = 0,
        pendingActionBatchCount: Int = 0,
        pendingWorkspaceActionCount: Int = 0
    ) = LocalDataAttachmentMergePlan(
        accountId = "account-a",
        items = disposition?.let {
            listOf(
                LocalDataAttachmentMergeItem(
                    entityType = "hive",
                    sourceId = 10L,
                    targetId = 10L,
                    disposition = it,
                    reason = "test"
                )
            )
        }.orEmpty(),
        taskCount = taskCount,
        noteDraftCount = noteDraftCount,
        formDraftCount = formDraftCount,
        pendingSyncWriteCount = pendingSyncWriteCount,
        pendingDeleteCount = pendingDeleteCount,
        pendingActionBatchCount = pendingActionBatchCount,
        pendingWorkspaceActionCount = pendingWorkspaceActionCount
    )

    @Test
    fun newLocalRowsHoldPersonalSync() {
        assertTrue(
            LocalDataAttachmentReviewPolicy.requiresReview(
                plan(disposition = LocalDataAttachmentDisposition.UPLOAD_NEW)
            )
        )
    }

    @Test
    fun remappedIdCollisionsHoldPersonalSync() {
        assertTrue(
            LocalDataAttachmentReviewPolicy.requiresReview(
                plan(disposition = LocalDataAttachmentDisposition.REMAP_ID)
            )
        )
    }

    @Test
    fun ambiguousCloudCollisionsHoldPersonalSync() {
        assertTrue(
            LocalDataAttachmentReviewPolicy.requiresReview(
                plan(disposition = LocalDataAttachmentDisposition.REVIEW_REQUIRED)
            )
        )
    }

    @Test
    fun exactDuplicatesDoNotHoldPersonalSync() {
        assertFalse(
            LocalDataAttachmentReviewPolicy.requiresReview(
                plan(disposition = LocalDataAttachmentDisposition.SKIP_DUPLICATE)
            )
        )
    }

    @Test
    fun preservedLocalOnlyTasksDoNotHoldPersonalSyncByThemselves() {
        assertFalse(LocalDataAttachmentReviewPolicy.requiresReview(plan(taskCount = 3)))
    }

    @Test
    fun pendingOutboxAndDraftsHoldPersonalSync() {
        assertTrue(LocalDataAttachmentReviewPolicy.requiresReview(plan(pendingSyncWriteCount = 1)))
        assertTrue(LocalDataAttachmentReviewPolicy.requiresReview(plan(pendingDeleteCount = 1)))
        assertTrue(LocalDataAttachmentReviewPolicy.requiresReview(plan(pendingActionBatchCount = 1)))
        assertTrue(LocalDataAttachmentReviewPolicy.requiresReview(plan(pendingWorkspaceActionCount = 1)))
        assertTrue(LocalDataAttachmentReviewPolicy.requiresReview(plan(noteDraftCount = 1)))
        assertTrue(LocalDataAttachmentReviewPolicy.requiresReview(plan(formDraftCount = 1)))
    }

    @Test
    fun emptyPlanDoesNotHoldPersonalSync() {
        assertFalse(LocalDataAttachmentReviewPolicy.requiresReview(plan()))
    }
}
