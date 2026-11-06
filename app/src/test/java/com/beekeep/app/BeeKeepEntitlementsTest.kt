package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepEntitlementsTest {
    @Test
    fun onlyHobbyistAndCommercialAreActivePlans() {
        assertEquals(listOf(BeeKeepPlan.HOBBYIST, BeeKeepPlan.COMMERCIAL), BeeKeepPlan.values().toList())
    }

    @Test
    fun legacyStoredPlanNamesMapWithoutReactivatingPro() {
        listOf("FREE", "free", "HOBBYIST", "pro", "pro_monthly", "pro_yearly").forEach {
            assertEquals(BeeKeepPlan.HOBBYIST, BeeKeepPlan.fromStoredValue(it))
        }
        listOf("COMMERCIAL", "commercial_monthly", "commercial_yearly", "ultimate",
            "ultimate_monthly", "ultimate_yearly").forEach {
            assertEquals(BeeKeepPlan.COMMERCIAL, BeeKeepPlan.fromStoredValue(it))
        }
        assertEquals(BeeKeepPlan.HOBBYIST, BeeKeepPlan.fromStoredValue(null))
        assertEquals(BeeKeepPlan.HOBBYIST, BeeKeepPlan.fromStoredValue("unknown_plan"))
    }

    @Test
    fun trustedServerMappingKeepsLegacyProAtHobbyistAndUltimateAtCommercial() {
        listOf("pro", "pro_monthly", "pro_yearly").forEach {
            assertEquals(BeeKeepPlan.HOBBYIST,
                BeeKeepEntitlements.planFromServerEntitlement(it, 1_000, isActivePaidPlan = true))
        }
        listOf("ultimate", "ultimate_monthly", "ultimate_yearly", "commercial").forEach {
            assertEquals(BeeKeepPlan.COMMERCIAL,
                BeeKeepEntitlements.planFromServerEntitlement(it, 10_000, isActivePaidPlan = true))
        }
        assertEquals(BeeKeepPlan.HOBBYIST,
            BeeKeepEntitlements.planFromServerEntitlement("ultimate_yearly", 10_000, isActivePaidPlan = false))
        assertEquals(BeeKeepPlan.HOBBYIST,
            BeeKeepEntitlements.planFromServerEntitlement("unknown", 10_000, isActivePaidPlan = true))
    }

    @Test
    fun serverCommercialGrantOverridesHobbyistDebugPurchaseSimulator() {
        assertEquals(
            BeeKeepPlan.COMMERCIAL,
            BeeKeepEntitlements.planForAppFeatures(
                isDebugBuild = true,
                localPlan = BeeKeepPlan.HOBBYIST,
                serverVerifiedPlan = BeeKeepPlan.COMMERCIAL
            )
        )
    }

    @Test
    fun serverHobbyistDoesNotDisableDebugPurchaseSimulation() {
        assertEquals(
            BeeKeepPlan.COMMERCIAL,
            BeeKeepEntitlements.planForAppFeatures(
                isDebugBuild = true,
                localPlan = BeeKeepPlan.COMMERCIAL,
                serverVerifiedPlan = BeeKeepPlan.HOBBYIST
            )
        )
        assertEquals(
            BeeKeepPlan.HOBBYIST,
            BeeKeepEntitlements.planForAppFeatures(
                isDebugBuild = true,
                localPlan = BeeKeepPlan.HOBBYIST,
                serverVerifiedPlan = BeeKeepPlan.HOBBYIST
            )
        )
    }

    @Test
    fun releaseBuildOnlyUsesTheServerVerifiedPlan() {
        assertEquals(
            BeeKeepPlan.COMMERCIAL,
            BeeKeepEntitlements.planForAppFeatures(
                isDebugBuild = false,
                localPlan = BeeKeepPlan.HOBBYIST,
                serverVerifiedPlan = BeeKeepPlan.COMMERCIAL
            )
        )
        assertEquals(
            BeeKeepPlan.HOBBYIST,
            BeeKeepEntitlements.planForAppFeatures(
                isDebugBuild = false,
                localPlan = BeeKeepPlan.COMMERCIAL,
                serverVerifiedPlan = null
            )
        )
    }

    @Test
    fun personalQuotaSnapshotsMatchEachPlan() {
        assertEquals(BeeKeepQuotaLimits(BeeKeepPlan.HOBBYIST, 15, 3),
            BeeKeepQuotaLimits.forPlan(BeeKeepPlan.HOBBYIST))
        assertEquals(BeeKeepQuotaLimits(BeeKeepPlan.COMMERCIAL, 10_000, 1_000),
            BeeKeepQuotaLimits.forPlan(BeeKeepPlan.COMMERCIAL))
        assertEquals(BeeKeepQuotaLimits(BeeKeepPlan.COMMERCIAL, 12_500, 1_000),
            BeeKeepQuotaLimits.forPlan(BeeKeepPlan.COMMERCIAL, customHiveLimit = 12_500))
    }

    @Test
    fun invitedHobbyistUsesTheWorkspaceOwnersCommercialQuota() {
        val quota = BeeKeepQuotaLimits.forWorkspaceOwner(true, 10_000, 1_000)
        assertEquals(BeeKeepPlan.COMMERCIAL, quota.plan)
        assertEquals(10_000, quota.hiveLimit)
        assertEquals(1_000, quota.apiaryLimit)
    }

    @Test
    fun workspaceQuotaPreservesApprovedOwnerHiveOverride() {
        val quota = BeeKeepQuotaLimits.forWorkspaceOwner(true, 12_500, 1_000)
        assertEquals(12_500, quota.hiveLimit)
    }

    @Test
    fun inactiveWorkspaceNeverRetainsCommercialOrCustomLimits() {
        val quota = BeeKeepQuotaLimits.forWorkspaceOwner(false, 12_500, 1_000)
        assertEquals(BeeKeepQuotaLimits.forPlan(BeeKeepPlan.HOBBYIST), quota)
    }

    @Test
    fun invalidWorkspaceQuotaFallsBackToCommercialBaseLimits() {
        val quota = BeeKeepQuotaLimits.forWorkspaceOwner(true, 15, 3)
        assertEquals(BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT, quota.hiveLimit)
        assertEquals(BeeKeepEntitlements.COMMERCIAL_APIARY_LIMIT, quota.apiaryLimit)
    }

    @Test
    fun quotaWritesWaitUntilTheSelectedDatasetQuotaHasBeenApplied() {
        assertFalse(
            BeeKeepQuotaAccessPolicy.canWrite(
                isPersonalDataset = false,
                quotaContextApplied = false,
                ownerCommercialActive = true,
                memberRole = "admin",
                workspaceReadOnly = false
            )
        )
    }

    @Test
    fun personalWritesWaitForThePersonalQuotaSnapshot() {
        assertFalse(
            BeeKeepQuotaAccessPolicy.canWrite(
                isPersonalDataset = true,
                quotaContextApplied = false,
                ownerCommercialActive = null,
                memberRole = null,
                workspaceReadOnly = false
            )
        )
        assertTrue(
            BeeKeepQuotaAccessPolicy.canWrite(
                isPersonalDataset = true,
                quotaContextApplied = true,
                ownerCommercialActive = null,
                memberRole = null,
                workspaceReadOnly = false
            )
        )
    }

    @Test
    fun onlyActiveCommercialEditableTeamMembersCanWrite() {
        assertTrue(BeeKeepQuotaAccessPolicy.canWrite(false, true, true, "owner", false))
        assertTrue(BeeKeepQuotaAccessPolicy.canWrite(false, true, true, "admin", false))
        assertTrue(BeeKeepQuotaAccessPolicy.canWrite(false, true, true, "member", false))
        assertFalse(BeeKeepQuotaAccessPolicy.canWrite(false, true, false, "member", true))
        assertFalse(BeeKeepQuotaAccessPolicy.canWrite(false, true, true, "viewer", true))
        assertFalse(BeeKeepQuotaAccessPolicy.canWrite(false, true, true, "member", true))
        assertFalse(BeeKeepQuotaAccessPolicy.canWrite(false, true, null, "admin", false))
    }

    @Test
    fun nfcFeaturesAreCommercialOnly() {
        assertFalse(BeeKeepEntitlements.canUseNfc(BeeKeepPlan.HOBBYIST))
        assertTrue(BeeKeepEntitlements.canUseNfc(BeeKeepPlan.COMMERCIAL))
    }

    @Test
    fun eachActivePlanHasTheRequestedHiveAndApiaryCaps() {
        assertEquals(15, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.HOBBYIST))
        assertEquals(3, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.HOBBYIST))
        assertEquals(10_000, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.COMMERCIAL))
        assertEquals(1_000, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.COMMERCIAL))
    }

    @Test
    fun customHiveOverridesCanOnlyRaiseCommercialAllowance() {
        assertEquals(15, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.HOBBYIST, 20_000))
        assertEquals(10_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.COMMERCIAL, 9_999))
        assertEquals(12_500, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.COMMERCIAL, 12_500))
        assertEquals(10_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.COMMERCIAL, 1_000_001))
    }

    @Test
    fun hobbyistDowngradeLocksOnlyHivesBeyondTheFifteenHiveAllowance() {
        val ids = (1L..18L).toList()

        assertEquals(
            (16L..18L).toSet(),
            BeeKeepEntitlements.hiveIdsBeyondLimit(ids, BeeKeepEntitlements.HOBBYIST_HIVE_LIMIT)
        )
        // Locked records remain part of the total quota count and are never deleted.
        assertEquals(ids.size, BeeKeepEntitlements.countHivesSubjectToFreeLimit(ids, emptySet()))
    }

    @Test
    fun quotaLockingPreservesOrderAndIgnoresDuplicateHiveIds() {
        val orderedIds = listOf(30L, 10L, 20L, 20L, 40L)

        assertEquals(
            setOf(40L),
            BeeKeepEntitlements.hiveIdsBeyondLimit(orderedIds, 3)
        )
    }

    @Test
    fun commercialAllowanceDoesNotLockHivesWithinItsLimit() {
        val ids = (1L..20L).toList()

        assertTrue(
            BeeKeepEntitlements.hiveIdsBeyondLimit(
                ids,
                BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT
            ).isEmpty()
        )
        assertTrue(BeeKeepEntitlements.hiveIdsBeyondLimit(ids, Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun commercialExpiryUsesTheConfiguredTimestamp() {
        val expiry = 1_800_000_000_000L
        assertFalse(BeeKeepEntitlements.isProPlanExpired(expiry, expiry - 1L))
        assertTrue(BeeKeepEntitlements.isProPlanExpired(expiry, expiry))
        assertTrue(BeeKeepEntitlements.isProPlanExpired(expiry, expiry + 1L))
        assertFalse(BeeKeepEntitlements.isProPlanExpired(0L, 1_800_000_000_000L))
    }

    @Test
    fun commercialPricingUsesSeparateMonthlyAndYearlyPrices() {
        assertEquals(59.88, BeeKeepPricing.COMMERCIAL_MONTHLY_CAD * 12, 0.001)
        assertEquals(29.89, BeeKeepPricing.commercialYearlySavingsCad, 0.001)
        assertEquals(50, BeeKeepPricing.commercialYearlyDiscountPercent)
        assertEquals(
            "Billed yearly. Save 50% compared with monthly billing.",
            BeeKeepPricing.commercialYearlyDiscountDescription()
        )
        assertEquals("$4.99", BeeKeepPricing.formatCad(BeeKeepPricing.COMMERCIAL_MONTHLY_CAD))
        assertEquals("$29.99", BeeKeepPricing.formatCad(BeeKeepPricing.COMMERCIAL_YEARLY_CAD))
    }

    @Test
    fun limitErrorsAreRecognizedForBothActivePlans() {
        BeeKeepPlan.values().forEach { plan ->
            assertTrue(BeeKeepEntitlements.isHiveLimitError(BeeKeepEntitlements.hiveLimitMessage(plan)))
            assertTrue(BeeKeepEntitlements.isApiaryLimitError(BeeKeepEntitlements.apiaryLimitMessage(plan)))
        }
        assertFalse(BeeKeepEntitlements.isHiveLimitError("Could not create your setup."))
        assertFalse(BeeKeepEntitlements.isApiaryLimitError("Could not create your setup."))
    }

    @Test
    fun compatibilityAliasesResolveToCanonicalTiers() {
        assertEquals(BeeKeepPlan.HOBBYIST, BeeKeepPlan.FREE)
        assertEquals(BeeKeepPlan.HOBBYIST, BeeKeepPlan.PRO)
        assertEquals(BeeKeepPlan.HOBBYIST, BeeKeepPlan.PRO_YEARLY)
        assertEquals(BeeKeepPlan.COMMERCIAL, BeeKeepPlan.ULTIMATE)
        assertEquals(BeeKeepPlan.COMMERCIAL, BeeKeepPlan.ULTIMATE_YEARLY)
    }
}
