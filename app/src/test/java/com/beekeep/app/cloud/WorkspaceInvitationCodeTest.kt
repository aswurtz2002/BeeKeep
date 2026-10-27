package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceInvitationCodeTest {
    @Test
    fun acceptsBeePrefixWithSixDigitsRegardlessOfCase() {
        assertTrue(isSupportedWorkspaceInvitationCode("Bee123456"))
        assertTrue(isSupportedWorkspaceInvitationCode("BEE123456"))
        assertTrue(isSupportedWorkspaceInvitationCode("bee000007"))
        assertTrue(isSupportedWorkspaceInvitationCode("Bee000000"))
    }

    @Test
    fun preservesCompatibilityWithPreviouslyIssuedCodes() {
        assertTrue(isSupportedWorkspaceInvitationCode("ABCD2345"))
        assertTrue(isSupportedWorkspaceInvitationCode("a1b2c3d4".replace('1', '2')))
        assertTrue(isSupportedWorkspaceInvitationCode("a".repeat(64)))
        assertTrue(isSupportedWorkspaceInvitationCode("b".repeat(32)))
        assertTrue(isSupportedWorkspaceInvitationCode("c".repeat(128)))
    }

    @Test
    fun rejectsMalformedNewCodesAndUnsupportedLengths() {
        assertFalse(isSupportedWorkspaceInvitationCode("Bee12345X"))
        assertFalse(isSupportedWorkspaceInvitationCode("Bee12A456"))
        assertFalse(isSupportedWorkspaceInvitationCode("Bee12345"))
        assertFalse(isSupportedWorkspaceInvitationCode("Bee1234567"))
        assertFalse(isSupportedWorkspaceInvitationCode("123456"))
        assertFalse(isSupportedWorkspaceInvitationCode("x".repeat(31)))
        assertFalse(isSupportedWorkspaceInvitationCode("x".repeat(129)))
    }
}
