package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepEntitlementsTest {
    @Test
    fun lockedProHivesDoNotConsumeFreeHiveAllowance() {
        val allHiveIds = listOf(1L, 2L, 3L, 4L, 5L)
        val lockedProHiveIds = setOf(4L, 5L)

        assertEquals(
            3,
            BeeKeepEntitlements.countHivesSubjectToFreeLimit(allHiveIds, lockedProHiveIds)
        )
        assertEquals(
            5,
            BeeKeepEntitlements.countHivesSubjectToFreeLimit(allHiveIds, emptySet())
        )
    }

    @Test
    fun proExpiresAtTheConfiguredTimestamp() {
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
    fun onlyProCreatedHivesLockWhenThePlanIsFree() {
        assertTrue(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.FREE, createdUnderPro = true))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.FREE, createdUnderPro = false))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.PRO, createdUnderPro = true))
        assertFalse(BeeKeepEntitlements.isHiveLockedForPlan(BeeKeepPlan.ULTIMATE, createdUnderPro = true))
    }
    @Test
    fun monthlyAndYearlyProHaveDifferentSubscriptionTerms() {
        assertTrue(BeeKeepPlan.PRO.isProSubscription)
        assertTrue(BeeKeepPlan.PRO_YEARLY.isProSubscription)
        assertFalse(BeeKeepPlan.ULTIMATE.isProSubscription)
        assertEquals(30L * 24L * 60L * 60L * 1000L, BeeKeepPlan.PRO.subscriptionDurationMillis)
        assertEquals(365L * 24L * 60L * 60L * 1000L, BeeKeepPlan.PRO_YEARLY.subscriptionDurationMillis)
    }

    @Test
    fun yearlyPriceShowsCorrectSavingsComparedWithMonthlyBilling() {
        assertEquals(35.88, BeeKeepPricing.PRO_MONTHLY_CAD * 12, 0.001)
        assertEquals(25.89, BeeKeepPricing.yearlySavingsCad, 0.001)
        assertEquals(72, BeeKeepPricing.yearlyDiscountPercent)
        assertEquals("$2.99", BeeKeepPricing.formatCad(BeeKeepPricing.PRO_MONTHLY_CAD))
        assertEquals("$9.99", BeeKeepPricing.formatCad(BeeKeepPricing.PRO_YEARLY_CAD))
        assertEquals("$89.99", BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_LIFETIME_CAD))
    }

}
