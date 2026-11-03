package com.beekeep.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepNfcAccessPolicyTest {
    @Test
    fun personalNfcUsesThePersonalCommercialEntitlement() {
        val access = BeeKeepNfcAccessPolicy.resolve(
            isPersonalDataset = true,
            personalCommercialActive = true,
            workspaceOwnerCommercialActive = false,
            workspaceMemberRole = "member",
            workspaceWritable = true
        )
        assertTrue(access.canScan)
        assertTrue(access.canManageTags)
    }

    @Test
    fun personalHobbyistDoesNotInheritAWorkspaceEntitlementOutsideThatWorkspace() {
        val access = BeeKeepNfcAccessPolicy.resolve(
            isPersonalDataset = true,
            personalCommercialActive = false,
            workspaceOwnerCommercialActive = true,
            workspaceMemberRole = "member",
            workspaceWritable = true
        )
        assertFalse(access.canScan)
        assertFalse(access.canManageTags)
    }

    @Test
    fun hobbyistMemberInCommercialWorkspaceCanScanAndManageTags() {
        val access = BeeKeepNfcAccessPolicy.resolve(
            isPersonalDataset = false,
            personalCommercialActive = false,
            workspaceOwnerCommercialActive = true,
            workspaceMemberRole = "member",
            workspaceWritable = true
        )
        assertTrue(access.canScan)
        assertTrue(access.canManageTags)
    }

    @Test
    fun ownerAndAdminCanManageTagsWhenWorkspaceIsWritable() {
        listOf("owner", "admin").forEach { role ->
            val access = BeeKeepNfcAccessPolicy.resolve(
                isPersonalDataset = false,
                personalCommercialActive = false,
                workspaceOwnerCommercialActive = true,
                workspaceMemberRole = role,
                workspaceWritable = true
            )
            assertTrue("$role should be able to scan", access.canScan)
            assertTrue("$role should be able to manage tags", access.canManageTags)
        }
    }

    @Test
    fun viewerCanScanAndVerifyButCannotChangeTagAssignments() {
        val access = BeeKeepNfcAccessPolicy.resolve(
            isPersonalDataset = false,
            personalCommercialActive = false,
            workspaceOwnerCommercialActive = true,
            workspaceMemberRole = "viewer",
            workspaceWritable = false
        )
        assertTrue(access.canScan)
        assertFalse(access.canManageTags)
    }

    @Test
    fun expiredWorkspaceOwnerCommercialEntitlementLocksNfcForEveryMember() {
        listOf("owner", "admin", "member", "viewer").forEach { role ->
            val access = BeeKeepNfcAccessPolicy.resolve(
                isPersonalDataset = false,
                personalCommercialActive = true,
                workspaceOwnerCommercialActive = false,
                workspaceMemberRole = role,
                workspaceWritable = false
            )
            assertFalse("$role should not be able to scan", access.canScan)
            assertFalse("$role should not be able to manage tags", access.canManageTags)
        }
    }

    @Test
    fun workspaceMemberCannotManageTagsWhileWorkspaceIsReadOnly() {
        val access = BeeKeepNfcAccessPolicy.resolve(
            isPersonalDataset = false,
            personalCommercialActive = false,
            workspaceOwnerCommercialActive = true,
            workspaceMemberRole = "member",
            workspaceWritable = false
        )
        assertTrue(access.canScan)
        assertFalse(access.canManageTags)
    }

    @Test
    fun missingOrUnknownWorkspaceRoleFailsClosed() {
        listOf(null, "unknown").forEach { role ->
            val access = BeeKeepNfcAccessPolicy.resolve(
                isPersonalDataset = false,
                personalCommercialActive = false,
                workspaceOwnerCommercialActive = true,
                workspaceMemberRole = role,
                workspaceWritable = true
            )
            assertFalse(access.canScan)
            assertFalse(access.canManageTags)
        }
    }
}
