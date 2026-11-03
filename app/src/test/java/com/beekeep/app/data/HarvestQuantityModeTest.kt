package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HarvestQuantityModeTest {
    @Test
    fun readsBothSupportedModes() {
        assertEquals(HarvestQuantityMode.PER_HIVE, HarvestQuantityMode.fromStorage("per_hive"))
        assertEquals(HarvestQuantityMode.APIARY_TOTAL, HarvestQuantityMode.fromStorage("apiary_total"))
    }

    @Test
    fun unknownOrMissingValuesFailClosedToExistingPerHiveBehavior() {
        assertEquals(HarvestQuantityMode.PER_HIVE, HarvestQuantityMode.fromStorage(null))
        assertEquals(HarvestQuantityMode.PER_HIVE, HarvestQuantityMode.fromStorage("future_mode"))
    }
}
