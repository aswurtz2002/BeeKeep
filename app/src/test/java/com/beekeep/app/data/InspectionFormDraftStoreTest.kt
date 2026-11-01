package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectionFormDraftStoreTest {
    @Test
    fun permanentHiveDeleteClearsOnlyTheActiveWorkspaceDraftKeys() {
        assertEquals(
            setOf(
                "workspace_workspace-a_hive_42",
                "workspace_workspace-a_hive_42_pending",
                "bee_count_history_start_workspace_workspace-a_42"
            ),
            InspectionFormDraftStore.keysForDeletedHive("workspace-a", 42L)
        )
    }

    @Test
    fun permanentPersonalHiveDeleteAlsoClearsLegacyDraftKeys() {
        assertEquals(
            setOf(
                "personal_hive_42",
                "personal_hive_42_pending",
                "bee_count_history_start_personal_42",
                "hive_42",
                "bee_count_history_start_42"
            ),
            InspectionFormDraftStore.keysForDeletedHive(null, 42L)
        )
    }
}
