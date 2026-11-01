package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConflictPolicyTest {
    @Test
    fun explicitDeleteWinsEvenWhenDeviceClockIsBehindTheCloudRow() {
        assertTrue(
            CloudConflictPolicy.shouldUploadLocal(
                localUpdatedAt = 100L,
                remoteUpdatedAt = 900L,
                localDeleted = true
            )
        )
        assertEquals(
            901L,
            CloudConflictPolicy.effectiveUploadTimestamp(
                localUpdatedAt = 100L,
                remoteUpdatedAt = 900L,
                localDeleted = true
            )
        )
        assertEquals(
            100L,
            CloudConflictPolicy.effectiveUploadTimestamp(
                localUpdatedAt = 100L,
                remoteUpdatedAt = 900L,
                localDeleted = false
            )
        )
    }

    @Test
    fun draftClearDoesNotOverrideANewerDraftWhenTheDeviceClockIsBehind() {
        assertFalse(
            CloudConflictPolicy.shouldUploadLocal(
                localUpdatedAt = 100L,
                remoteUpdatedAt = 900L,
                localDeleted = true,
                allowDraftRecreation = true
            )
        )
        assertEquals(
            100L,
            CloudConflictPolicy.effectiveUploadTimestamp(
                localUpdatedAt = 100L,
                remoteUpdatedAt = 900L,
                localDeleted = true,
                allowDraftRecreation = true
            )
        )
    }

    @Test
    fun localUploadOnlyWinsWhenItsTimestampIsStrictlyNewer() {
        assertTrue(CloudConflictPolicy.shouldUploadLocal(101L, 100L))
        assertTrue(CloudConflictPolicy.shouldUploadLocal(100L, null))
        assertFalse(CloudConflictPolicy.shouldUploadLocal(100L, 100L))
        assertFalse(CloudConflictPolicy.shouldUploadLocal(99L, 100L))
    }

    @Test
    fun cloudCopyWinsEqualTimestampAndRejectsOlderCopies() {
        assertTrue(CloudConflictPolicy.shouldApplyRemote(101L, 100L))
        assertTrue(CloudConflictPolicy.shouldApplyRemote(100L, 100L))
        assertFalse(CloudConflictPolicy.shouldApplyRemote(99L, 100L))
    }

    @Test
    fun permanentCloudTombstoneBlocksNewerActiveOutboxRows() {
        assertFalse(
            CloudConflictPolicy.shouldUploadLocal(
                localUpdatedAt = 500L,
                remoteUpdatedAt = 100L,
                remoteDeleted = true
            )
        )
        assertTrue(
            CloudConflictPolicy.shouldUploadLocal(
                localUpdatedAt = 500L,
                remoteUpdatedAt = 100L,
                remoteDeleted = true,
                allowDraftRecreation = true
            )
        )
        assertFalse(
            CloudConflictPolicy.shouldUploadLocal(
                localUpdatedAt = 500L,
                remoteUpdatedAt = 100L,
                remoteDeleted = true,
                localDeleted = true
            )
        )
    }
}
