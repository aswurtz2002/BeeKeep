package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionNoteDraftTest {
    @Test
    fun personalAndTeamDraftKeysAreIsolated() {
        assertEquals("personal_hive_42", inspectionNoteDraftKey(null, 42L))
        assertEquals("personal_hive_42", inspectionNoteDraftKey("  ", 42L))
        assertEquals("workspace-a_hive_42", inspectionNoteDraftKey(" workspace-a ", 42L))
        assertEquals("workspace-b_hive_42", inspectionNoteDraftKey("workspace-b", 42L))
    }

    @Test
    fun noteDraftMetadataKeysAreStable() {
        val key = inspectionNoteDraftKey("workspace-a", 42L)
        assertEquals("workspace-a_hive_42_recorded_at", inspectionNoteDraftRecordedAtKey(key))
        assertEquals("workspace-a_hive_42_updated_at", inspectionNoteDraftUpdatedAtKey(key))
        assertEquals("workspace-a_hive_42_sync_pending", inspectionNoteDraftPendingKey(key))
    }

    @Test
    fun onlyNewerOrEqualCloudDraftsApply() {
        assertTrue(shouldApplyInspectionNoteDraft(incomingUpdatedAt = 120L, localUpdatedAt = 100L))
        assertTrue(shouldApplyInspectionNoteDraft(incomingUpdatedAt = 100L, localUpdatedAt = 100L))
        assertFalse(shouldApplyInspectionNoteDraft(incomingUpdatedAt = 99L, localUpdatedAt = 100L))
    }
}
