package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MiteMathTest {
    @Test
    fun calculatesInfestationPercent() {
        val inspection = Inspection(1, 1, 1, 7, "Laying", 9, 300, "", null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, "")
        assertEquals(3.0, inspection.mitePercent, 0.0001)
    }

    @Test
    fun zeroSampleIsSafe() {
        val inspection = Inspection(1, 1, 1, 7, "Laying", 9, 0, "", null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, "")
        assertEquals(0.0, inspection.mitePercent, 0.0001)
    }
}
