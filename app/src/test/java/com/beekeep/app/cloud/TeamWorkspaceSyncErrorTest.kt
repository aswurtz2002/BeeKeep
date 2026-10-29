package com.beekeep.app.cloud

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamWorkspaceSyncErrorTest {
    @Test
    fun replacesVerboseSharedWorkspaceRlsError() {
        val raw = "new row violates row-level security policy for table \"beekeep_workspace_documents\""
        assertEquals(TeamWorkspaceSyncError.RLS_DENIED_MESSAGE, TeamWorkspaceSyncError.userFacing(raw))
    }

    @Test
    fun recognizesRlsErrorCaseInsensitively() {
        val raw = "NEW ROW VIOLATES ROW-LEVEL SECURITY POLICY FOR TABLE \"BEEKEEP_WORKSPACE_DOCUMENTS\""
        assertEquals(TeamWorkspaceSyncError.RLS_DENIED_MESSAGE, TeamWorkspaceSyncError.userFacing(raw))
    }

    @Test
    fun mapsMissingDeletedFlagConstraintToShortActionableMessage() {
        val raw = "null value in column \"deleted\" of relation \"beekeep_workspace_documents\" violates not-null constraint"
        assertEquals(TeamWorkspaceSyncError.MISSING_DELETED_FLAG_MESSAGE, TeamWorkspaceSyncError.userFacing(raw))
    }

    @Test
    fun hidesLargeTechnicalErrors() {
        val raw = "HTTP error: headers: " + "x".repeat(400)
        assertEquals(TeamWorkspaceSyncError.GENERIC_SYNC_ERROR_MESSAGE, TeamWorkspaceSyncError.userFacing(raw))
    }

    @Test
    fun preservesShortUnrelatedErrors() {
        assertEquals("network timeout", TeamWorkspaceSyncError.userFacing("network timeout"))
    }

    @Test
    fun suppliesFallbackForEmptyErrors() {
        assertTrue(TeamWorkspaceSyncError.userFacing(null).isNotBlank())
        assertTrue(TeamWorkspaceSyncError.userFacing("  ").length < 100)
    }

    @Test
    fun serializesFalseDeletedFlagForWorkspaceDocuments() {
        val document = CloudWorkspaceDocumentRow(
            workspace_id = "workspace-a",
            entity_type = "hive",
            entity_id = 42L,
            payload = "{}",
            updated_at = 100L,
            deleted = false,
            device_id = "test-device",
            updated_by = "00000000-0000-0000-0000-000000000001"
        )

        val json = Json.encodeToString(document)
        assertTrue("The false deletion flag must be explicitly sent to Supabase", json.contains("\"deleted\":false"))
    }

    @Test
    fun serializesFalseDeletedFlagForPersonalDocuments() {
        val document = CloudDocumentRow(
            user_id = "00000000-0000-0000-0000-000000000001",
            entity_type = "hive",
            entity_id = 42L,
            payload = "{}",
            updated_at = 100L,
            deleted = false,
            device_id = "test-device"
        )

        val json = Json.encodeToString(document)
        assertTrue("The false deletion flag must be explicitly sent to Supabase", json.contains("\"deleted\":false"))
    }
}
