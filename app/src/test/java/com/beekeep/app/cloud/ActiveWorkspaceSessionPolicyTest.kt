package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWorkspaceSessionPolicyTest {
    @Test
    fun sameSignedInAccountCanRestoreItsCachedTeamOffline() {
        assertTrue(ActiveWorkspaceSessionPolicy.ownerMatches("user-a", "user-a"))
        assertTrue(ActiveWorkspaceSessionPolicy.ownerMatches(" user-a ", "user-a"))
    }

    @Test
    fun differentAccountCannotRestorePreviousAccountTeamCache() {
        assertFalse(ActiveWorkspaceSessionPolicy.ownerMatches("user-b", "user-a"))
    }

    @Test
    fun missingSessionOrLegacyUnboundWorkspaceCannotRestoreCachedTeam() {
        assertFalse(ActiveWorkspaceSessionPolicy.ownerMatches(null, "user-a"))
        assertFalse(ActiveWorkspaceSessionPolicy.ownerMatches("", "user-a"))
        assertFalse(ActiveWorkspaceSessionPolicy.ownerMatches("user-a", null))
        assertFalse(ActiveWorkspaceSessionPolicy.ownerMatches("user-a", " "))
    }
}
