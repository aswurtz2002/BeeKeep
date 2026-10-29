package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
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
    fun preservesUnrelatedErrors() {
        assertEquals("network timeout", TeamWorkspaceSyncError.userFacing("network timeout"))
    }

    @Test
    fun suppliesFallbackForEmptyErrors() {
        assertEquals("Team workspace sync failed", TeamWorkspaceSyncError.userFacing(null))
        assertEquals("Team workspace sync failed", TeamWorkspaceSyncError.userFacing("  "))
    }
}
