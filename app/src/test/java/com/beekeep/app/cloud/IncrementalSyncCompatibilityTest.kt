package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSyncCompatibilityTest {
    @Test
    fun recognizesMissingPersonalSyncHeadRpc() {
        val error = IllegalStateException(
            "PGRST202: Could not find the function public.beekeep_personal_sync_head in the schema cache"
        )

        assertTrue(IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error))
    }

    @Test
    fun recognizesMissingWorkspaceChangesRpcInNestedCause() {
        val error = IllegalStateException(
            "PostgREST request failed",
            IllegalStateException(
                "Could not find the function public.beekeep_workspace_sync_changes in the schema cache"
            )
        )

        assertTrue(IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error))
    }

    @Test
    fun doesNotTreatNetworkFailuresAsMissingMigration() {
        val error = IllegalStateException(
            "Timed out while calling beekeep_personal_sync_head"
        )

        assertFalse(IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error))
    }

    @Test
    fun doesNotTreatPermissionErrorsAsMissingMigration() {
        val error = IllegalStateException(
            "permission denied for function beekeep_workspace_sync_changes"
        )

        assertFalse(IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error))
    }

    @Test
    fun doesNotTreatAnotherMissingRpcAsIncrementalSyncFallback() {
        val error = IllegalStateException(
            "PGRST202: Could not find the function public.beekeep_list_workspaces in the schema cache"
        )

        assertFalse(IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error))
    }
}
