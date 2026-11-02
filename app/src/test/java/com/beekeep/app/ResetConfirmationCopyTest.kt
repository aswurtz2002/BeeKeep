package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetConfirmationCopyTest {
    @Test
    fun teamResetClearlyWarnsThatAllMembersSharedDataIsAffected() {
        val copy = resetConfirmationCopy(isTeamWorkspace = true)

        assertTrue(copy.title.contains("team workspace", ignoreCase = true))
        assertTrue(copy.cloudImpact.contains("all members", ignoreCase = true))
        assertTrue(copy.cloudImpact.contains("personal", ignoreCase = true))
        assertTrue(copy.cloudImpact.contains("will not be changed", ignoreCase = true))
        assertEquals("team workspace data", copy.localDataLabel)
    }

    @Test
    fun personalResetClearlyProtectsTeamWorkspaces() {
        val copy = resetConfirmationCopy(isTeamWorkspace = false)

        assertTrue(copy.title.contains("personal", ignoreCase = true))
        assertTrue(copy.cloudImpact.contains("team workspaces", ignoreCase = true))
        assertTrue(copy.cloudImpact.contains("will not be changed", ignoreCase = true))
        assertEquals("personal data", copy.localDataLabel)
    }

    @Test
    fun resetScopesUseDifferentConfirmationCopy() {
        val personal = resetConfirmationCopy(isTeamWorkspace = false)
        val team = resetConfirmationCopy(isTeamWorkspace = true)

        assertNotEquals(personal.title, team.title)
        assertNotEquals(personal.cloudDataLabel, team.cloudDataLabel)
        assertNotEquals(personal.recordScope, team.recordScope)
    }
}
