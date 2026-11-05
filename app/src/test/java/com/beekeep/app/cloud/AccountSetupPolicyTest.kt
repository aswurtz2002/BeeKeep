package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSetupPolicyTest {
    @Test
    fun acceptsValidBeeKeepUsernames() {
        assertTrue(AccountSetupPolicy.isValidUsername("NorthApiary"))
        assertTrue(AccountSetupPolicy.isValidUsername("bee_123"))
        assertTrue(AccountSetupPolicy.isValidUsername("abc"))
        assertTrue(AccountSetupPolicy.isValidUsername("A".repeat(20)))
    }

    @Test
    fun rejectsInvalidBeeKeepUsernames() {
        assertFalse(AccountSetupPolicy.isValidUsername(""))
        assertFalse(AccountSetupPolicy.isValidUsername("ab"))
        assertFalse(AccountSetupPolicy.isValidUsername("A".repeat(21)))
        assertFalse(AccountSetupPolicy.isValidUsername("has spaces"))
        assertFalse(AccountSetupPolicy.isValidUsername("name@example.com"))
    }

    @Test
    fun acceptsOnlyTheSignedInUsersOwnedPersonalWorkspace() {
        val personal = workspace(
            id = "personal",
            owner = "user-a",
            personal = true,
            role = "owner"
        )
        val team = workspace(
            id = "team",
            owner = "user-b",
            personal = false,
            role = "member"
        )

        assertEquals(
            personal,
            AccountSetupPolicy.personalWorkspaceForAccount(listOf(team, personal), "user-a")
        )
    }

    @Test
    fun rejectsWorkspaceOwnedByAnotherAccount() {
        val otherUsersPersonal = workspace("other", "user-b", true, "owner")

        assertNull(
            AccountSetupPolicy.personalWorkspaceForAccount(listOf(otherUsersPersonal), "user-a")
        )
    }

    @Test
    fun rejectsPersonalWorkspaceWithoutOwnerMembership() {
        val personal = workspace("personal", "user-a", true, "member")

        assertNull(
            AccountSetupPolicy.personalWorkspaceForAccount(listOf(personal), "user-a")
        )
    }

    @Test
    fun rejectsAmbiguousMultiplePersonalWorkspaces() {
        val first = workspace("first", "user-a", true, "owner")
        val second = workspace("second", "user-a", true, "owner")

        assertNull(
            AccountSetupPolicy.personalWorkspaceForAccount(listOf(first, second), "user-a")
        )
    }

    private fun workspace(
        id: String,
        owner: String,
        personal: Boolean,
        role: String
    ) = BeeKeepWorkspace(
        id = id,
        name = id,
        owner_user_id = owner,
        is_personal = personal,
        member_role = role
    )
}
