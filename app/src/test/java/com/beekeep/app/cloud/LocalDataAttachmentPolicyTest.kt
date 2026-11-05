package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataAttachmentPolicyTest {
    @Test
    fun firstLoginOnDeviceRequiresReview() {
        assertTrue(
            LocalDataAttachmentPolicy.requiresReview(
                previousUserId = null,
                pendingUserId = null,
                currentUserId = "user-a"
            )
        )
    }

    @Test
    fun switchingAccountsRequiresReviewEvenWhenOldMarkerBelongsToAnotherUser() {
        assertTrue(
            LocalDataAttachmentPolicy.requiresReview(
                previousUserId = "user-a",
                pendingUserId = "user-a",
                currentUserId = "user-b"
            )
        )
    }

    @Test
    fun returningAccountCanSyncWhenNoReviewIsPending() {
        assertFalse(
            LocalDataAttachmentPolicy.requiresReview(
                previousUserId = "user-a",
                pendingUserId = null,
                currentUserId = "user-a"
            )
        )
    }

    @Test
    fun matchingPendingMarkerKeepsSyncPausedAcrossRestarts() {
        assertTrue(
            LocalDataAttachmentPolicy.requiresReview(
                previousUserId = "user-a",
                pendingUserId = "user-a",
                currentUserId = "user-a"
            )
        )
    }

    @Test
    fun markerForAnotherAccountDoesNotPauseAConfirmedReturningAccount() {
        assertFalse(
            LocalDataAttachmentPolicy.requiresReview(
                previousUserId = "user-a",
                pendingUserId = "user-b",
                currentUserId = "user-a"
            )
        )
    }

    @Test
    fun blankUserIdFailsClosed() {
        assertTrue(
            LocalDataAttachmentPolicy.requiresReview(
                previousUserId = "user-a",
                pendingUserId = null,
                currentUserId = " "
            )
        )
    }
}
