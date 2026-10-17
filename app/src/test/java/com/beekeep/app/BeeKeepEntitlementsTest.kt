package com.beekeep.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepEntitlementsTest {
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
}
