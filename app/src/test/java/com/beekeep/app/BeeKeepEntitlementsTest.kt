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
    fun downgradeNeverLocksExistingHivesOrExcludesThemFromTheQuotaCount() {
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        val legacyPaidIds = setOf(4L, 5L)
        assertEquals(ids.size, BeeKeepEntitlements.countHivesSubjectToFreeLimit(ids, legacyPaidIds))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.HOBBYIST, createdUnderPro = true))
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
