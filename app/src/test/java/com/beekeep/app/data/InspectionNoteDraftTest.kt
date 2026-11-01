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
    fun permanentHiveDeleteClearsOnlyScopedNoteDraftKeys() {
        assertEquals(
            setOf(
                "workspace-a_hive_42",
                "workspace-a_hive_42_recorded_at",
                "workspace-a_hive_42_updated_at",
                "workspace-a_hive_42_sync_pending"
            ),
            inspectionNoteDraftKeysForDeletedHive("workspace-a", 42L)
        )
    }

    @Test
    fun permanentPersonalHiveDeleteAlsoClearsLegacyNoteDraftKeys() {
        assertEquals(
            setOf(
                "personal_hive_42",
                "personal_hive_42_recorded_at",
                "personal_hive_42_updated_at",
                "personal_hive_42_sync_pending",
                "hive_42",
                "hive_42_recorded_at"
            ),
            inspectionNoteDraftKeysForDeletedHive(null, 42L)
        )
    }

    @Test
    fun onlyNewerOrEqualCloudDraftsApplyWhenNoLocalEditIsPending() {
        assertTrue(shouldApplyInspectionNoteDraft(incomingUpdatedAt = 120L, localUpdatedAt = 100L))
        assertTrue(shouldApplyInspectionNoteDraft(incomingUpdatedAt = 100L, localUpdatedAt = 100L))
        assertFalse(shouldApplyInspectionNoteDraft(incomingUpdatedAt = 99L, localUpdatedAt = 100L))
    }

    @Test
    fun pendingLocalEditWinsEvenWhenCloudTimestampIsNewer() {
        assertFalse(
            shouldApplyInspectionNoteDraft(
                incomingUpdatedAt = 500L,
                localUpdatedAt = 100L,
                localPending = true
            )
        )
    }
}
