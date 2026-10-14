package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BeeCountFormattingTest {
    @Test
    fun formatsHalfFrameStepsForInspectionAndHiveLists() {
        assertEquals("0.5", formatBeeCountFrames(0.5))
        assertEquals("1", formatBeeCountFrames(1.0))
        assertEquals("1.5", formatBeeCountFrames(1.5))
        assertEquals("5.5", formatBeeCountFrames(5.5))
        assertEquals("10", formatBeeCountFrames(10.0))
    }

    @Test
    fun clampsBeeFrameFormattingToSupportedRange() {
        assertEquals("0.5", formatBeeCountFrames(0.0))
        assertEquals("10", formatBeeCountFrames(12.0))
    }
}
