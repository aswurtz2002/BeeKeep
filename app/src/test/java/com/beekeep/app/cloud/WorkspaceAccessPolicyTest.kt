package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAccessPolicyTest {
    @Test
    fun ownerIsWritableOnlyWithActiveCommercialEntitlement() {
        assertFalse(WorkspaceAccessPolicy.isReadOnly("owner", ownerCommercialEntitlementActive = true))
        assertTrue(WorkspaceAccessPolicy.isReadOnly("owner", ownerCommercialEntitlementActive = false))
    }

    @Test
    fun viewerRemainsReadOnlyEvenWhenOwnerHasCommercial() {
        assertTrue(WorkspaceAccessPolicy.isReadOnly("viewer", ownerCommercialEntitlementActive = true))
        assertTrue(WorkspaceAccessPolicy.isReadOnly("viewer", ownerCommercialEntitlementActive = false))
    }

    @Test
    fun invitedAdminsAndMembersAreLockedWhenWorkspaceOwnerLosesCommercial() {
        assertFalse(WorkspaceAccessPolicy.isReadOnly("admin", ownerCommercialEntitlementActive = true))
        assertFalse(WorkspaceAccessPolicy.isReadOnly("member", ownerCommercialEntitlementActive = true))
        assertTrue(WorkspaceAccessPolicy.isReadOnly("admin", ownerCommercialEntitlementActive = false))
        assertTrue(WorkspaceAccessPolicy.isReadOnly("member", ownerCommercialEntitlementActive = false))
    }

    @Test
    fun unknownRoleFailsClosedAsReadOnly() {
        assertTrue(WorkspaceAccessPolicy.isReadOnly(null, ownerCommercialEntitlementActive = false))
        assertTrue(WorkspaceAccessPolicy.isReadOnly("unknown", ownerCommercialEntitlementActive = false))
    }
}
