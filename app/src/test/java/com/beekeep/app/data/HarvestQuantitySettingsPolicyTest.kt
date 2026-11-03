package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarvestQuantitySettingsPolicyTest {
    @Test
    fun scopeKeySeparatesPersonalAccountsAndTeamWorkspaces() {
        assertEquals("personal:local", workspaceActionSettingsScopeKey(null, null))
        assertEquals("personal:user-a", workspaceActionSettingsScopeKey(null, " user-a "))
        assertEquals("workspace:team-a", workspaceActionSettingsScopeKey(" team-a ", "user-a"))
    }

    @Test
    fun personalOwnerCanChangeTheirOwnSetting() {
        assertTrue(
            canEditHarvestQuantitySetting(
                isPersonalScope = true,
                role = null,
                roleVerified = false,
                workspaceReadOnly = false
            )
        )
    }

    @Test
    fun onlyVerifiedWritableWorkspaceOwnersAndAdminsCanChangeSetting() {
        listOf("owner", "admin").forEach { role ->
            assertTrue(
                canEditHarvestQuantitySetting(
                    isPersonalScope = false,
                    role = role,
                    roleVerified = true,
                    workspaceReadOnly = false
                )
            )
        }
    }

    @Test
    fun membersViewersUnverifiedAndReadOnlyWorkspacesCannotChangeSetting() {
        listOf("member", "viewer", null).forEach { role ->
            assertFalse(
                canEditHarvestQuantitySetting(
                    isPersonalScope = false,
                    role = role,
                    roleVerified = true,
                    workspaceReadOnly = false
                )
            )
        }
        assertFalse(
            canEditHarvestQuantitySetting(
                isPersonalScope = false,
                role = "admin",
                roleVerified = false,
                workspaceReadOnly = false
            )
        )
        assertFalse(
            canEditHarvestQuantitySetting(
                isPersonalScope = false,
                role = "owner",
                roleVerified = true,
                workspaceReadOnly = true
            )
        )
        assertFalse(
            canEditHarvestQuantitySetting(
                isPersonalScope = false,
                role = "admin",
                roleVerified = true,
                workspaceReadOnly = false,
                workspaceOwnerCommercialVerified = false
            )
        )
    }
}
