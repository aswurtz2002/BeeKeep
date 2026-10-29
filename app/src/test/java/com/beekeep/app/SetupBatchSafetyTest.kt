package com.beekeep.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupBatchSafetyTest {
    @Test
    fun ordinaryBatchesDoNotRequireExtraConfirmation() {
        assertFalse(SetupBatchSafety.requiresConfirmation(0))
        assertFalse(SetupBatchSafety.requiresConfirmation(50))
    }

    @Test
    fun largeBatchesRequireExplicitConfirmation() {
        assertTrue(SetupBatchSafety.requiresConfirmation(51))
        assertTrue(SetupBatchSafety.requiresConfirmation(150))
    }
}
