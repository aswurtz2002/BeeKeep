package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConflictPolicyTest {
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
}
