package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepEntitlementsTest {
    @Test
    fun lockedPaidPlanHivesDoNotConsumeFreeHiveAllowance() {
        val allHiveIds = listOf(1L, 2L, 3L, 4L, 5L)
        val lockedSubscriptionHiveIds = setOf(4L, 5L)

        assertEquals(
            3,
            BeeKeepEntitlements.countHivesSubjectToFreeLimit(allHiveIds, lockedSubscriptionHiveIds)
        )
        assertEquals(
            5,
            BeeKeepEntitlements.countHivesSubjectToFreeLimit(allHiveIds, emptySet())
        )
    }

    @Test
    fun subscriptionExpiresAtTheConfiguredTimestamp() {
        val expiry = 1_800_000_000_000L

        assertFalse(BeeKeepEntitlements.isProPlanExpired(expiry, expiry - 1L))
        assertTrue(BeeKeepEntitlements.isProPlanExpired(expiry, expiry))
        assertTrue(BeeKeepEntitlements.isProPlanExpired(expiry, expiry + 1L))
    }

    @Test
    fun missingExpiryDoesNotImmediatelyExpireAnExistingTestPlan() {
        assertFalse(BeeKeepEntitlements.isProPlanExpired(0L, 1_800_000_000_000L))
        assertFalse(BeeKeepEntitlements.isProPlanExpired(-1L, 1_800_000_000_000L))
    }

    @Test
    fun subscriptionCreatedHivesLockWhenThePlanReturnsToFree() {
        assertTrue(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.FREE, createdUnderPro = true))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.FREE, createdUnderPro = false))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.PRO, createdUnderPro = true))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.ULTIMATE, createdUnderPro = true))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.ULTIMATE_YEARLY, createdUnderPro = true))
    }

    @Test
    fun allMonthlyAndYearlyPaidPlansHaveSubscriptionTerms() {
        assertFalse(BeeKeepPlan.FREE.isProSubscription)
        assertTrue(BeeKeepPlan.PRO.isProSubscription)
        assertTrue(BeeKeepPlan.PRO_YEARLY.isProSubscription)
        assertTrue(BeeKeepPlan.ULTIMATE.isProSubscription)
        assertTrue(BeeKeepPlan.ULTIMATE_YEARLY.isProSubscription)
        assertEquals(30L * 24L * 60L * 60L * 1000L, BeeKeepPlan.PRO.subscriptionDurationMillis)
        assertEquals(365L * 24L * 60L * 60L * 1000L, BeeKeepPlan.PRO_YEARLY.subscriptionDurationMillis)
        assertEquals(30L * 24L * 60L * 60L * 1000L, BeeKeepPlan.ULTIMATE.subscriptionDurationMillis)
        assertEquals(365L * 24L * 60L * 60L * 1000L, BeeKeepPlan.ULTIMATE_YEARLY.subscriptionDurationMillis)
    }

    @Test
    fun yearlyPricesShowCorrectSavingsComparedWithMonthlyBilling() {
        assertEquals(71.88, BeeKeepPricing.PRO_MONTHLY_CAD * 12, 0.001)
        assertEquals(21.89, BeeKeepPricing.proYearlySavingsCad, 0.001)
        assertEquals(30, BeeKeepPricing.proYearlyDiscountPercent)
        assertEquals(119.88, BeeKeepPricing.ULTIMATE_MONTHLY_CAD * 12, 0.001)
        assertEquals(39.89, BeeKeepPricing.ultimateYearlySavingsCad, 0.001)
        assertEquals(33, BeeKeepPricing.ultimateYearlyDiscountPercent)
        assertEquals("$5.99", BeeKeepPricing.formatCad(BeeKeepPricing.PRO_MONTHLY_CAD))
        assertEquals("$49.99", BeeKeepPricing.formatCad(BeeKeepPricing.PRO_YEARLY_CAD))
        assertEquals("$9.99", BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_MONTHLY_CAD))
        assertEquals("$79.99", BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_YEARLY_CAD))
    }

    @Test
    fun eachPlanHasTheRequestedHiveAndApiaryCaps() {
        assertEquals(150, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.FREE))
        assertEquals(1_000, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.PRO))
        assertEquals(1_000, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.PRO_YEARLY))
        assertEquals(10_000, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.ULTIMATE))
        assertEquals(10_000, BeeKeepEntitlements.hiveLimit(BeeKeepPlan.ULTIMATE_YEARLY))
        assertEquals(100, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.PRO))
        assertEquals(100, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.PRO_YEARLY))
        assertEquals(1_000, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.ULTIMATE))
        assertEquals(1_000, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.ULTIMATE_YEARLY))
        assertEquals(Int.MAX_VALUE, BeeKeepEntitlements.apiaryLimit(BeeKeepPlan.FREE))
    }
}
