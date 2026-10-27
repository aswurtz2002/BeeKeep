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
        assertEquals(35.88, BeeKeepPricing.PRO_MONTHLY_CAD * 12, 0.001)
        assertEquals(10.89, BeeKeepPricing.proYearlySavingsCad, 0.001)
        assertEquals(30, BeeKeepPricing.proYearlyDiscountPercent)
        assertEquals(83.88, BeeKeepPricing.ULTIMATE_MONTHLY_CAD * 12, 0.001)
        assertEquals(23.89, BeeKeepPricing.ultimateYearlySavingsCad, 0.001)
        assertEquals(28, BeeKeepPricing.ultimateYearlyDiscountPercent)
        assertEquals("$2.99", BeeKeepPricing.formatCad(BeeKeepPricing.PRO_MONTHLY_CAD))
        assertEquals("$24.99", BeeKeepPricing.formatCad(BeeKeepPricing.PRO_YEARLY_CAD))
        assertEquals("$6.99", BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_MONTHLY_CAD))
        assertEquals("$59.99", BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_YEARLY_CAD))
    }

    @Test
    fun capErrorsAreRecognizedAcrossFreeProAndUltimatePlans() {
        BeeKeepPlan.values().forEach { plan ->
            assertTrue(
                "Hive cap message should be recognized for ${plan.displayName}",
                BeeKeepEntitlements.isHiveLimitError(BeeKeepEntitlements.hiveLimitMessage(plan))
            )
            if (BeeKeepEntitlements.apiaryLimit(plan) == Int.MAX_VALUE) {
                assertFalse(
                    "Unlimited Free apiaries should not report a cap error",
                    BeeKeepEntitlements.isApiaryLimitError(BeeKeepEntitlements.apiaryLimitMessage(plan))
                )
            } else {
                assertTrue(
                    "Apiary cap message should be recognized for ${plan.displayName}",
                    BeeKeepEntitlements.isApiaryLimitError(BeeKeepEntitlements.apiaryLimitMessage(plan))
                )
            }
        }
        assertFalse(BeeKeepEntitlements.isHiveLimitError("Could not create your setup."))
        assertFalse(BeeKeepEntitlements.isApiaryLimitError("Could not create your setup."))
    }

    @Test
    fun customHiveLimitRaisesOnlyAnActiveUltimateAllowance() {
        assertEquals(10_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.ULTIMATE, null))
        assertEquals(10_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.ULTIMATE_YEARLY, 9_999))
        assertEquals(11_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.ULTIMATE, 11_000))
        assertEquals(25_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.ULTIMATE_YEARLY, 25_000))

        // A trusted override is only honored for Ultimate; it cannot grant extra capacity
        // to a Free or Pro account if one is accidentally left in the override table.
        assertEquals(150, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.FREE, 11_000))
        assertEquals(1_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.PRO, 11_000))
        assertEquals(10_000, BeeKeepEntitlements.effectiveHiveLimit(BeeKeepPlan.ULTIMATE, 1_000_001))
    }

    @Test
    fun customUltimateLimitMessageShowsTheIndividualAllowance() {
        val message = BeeKeepEntitlements.hiveLimitMessage(BeeKeepPlan.ULTIMATE, 11_000)
        assertTrue(message.contains("11,000"))
        assertTrue(message.contains("custom BeeKeep Ultimate hive limit"))
        assertTrue(message.contains("existing records are safe"))
        assertFalse(message.contains("10,000 hive limit"))
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
