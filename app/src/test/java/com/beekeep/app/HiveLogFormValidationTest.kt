package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiveLogFormValidationTest {
    @Test
    fun parsesPositiveAmountsWithDotOrCommaDecimal() {
        assertEquals(1.25, parsePositiveLogAmount("1.25")!!, 0.0001)
        assertEquals(1.25, parsePositiveLogAmount("1,25")!!, 0.0001)
    }

    @Test
    fun rejectsBlankZeroNegativeAndNonFiniteFeedingAmounts() {
        assertNull(parsePositiveLogAmount(""))
        assertNull(parsePositiveLogAmount("0"))
        assertNull(parsePositiveLogAmount("-1"))
        assertNull(parsePositiveLogAmount("NaN"))
        assertNull(parsePositiveLogAmount("Infinity"))
    }

    @Test
    fun blankOptionalHarvestWeightsMeanNotMeasured() {
        assertEquals(0.0, parseOptionalNonNegativeLogWeight("")!!, 0.0)
        assertEquals(0.0, parseOptionalNonNegativeLogWeight("   ")!!, 0.0)
        assertEquals(2.75, parseOptionalNonNegativeLogWeight("2,75")!!, 0.0001)
        assertEquals(0.0, parseOptionalNonNegativeLogWeight("0")!!, 0.0)
    }

    @Test
    fun rejectsInvalidOptionalHarvestWeights() {
        assertNull(parseOptionalNonNegativeLogWeight("one"))
        assertNull(parseOptionalNonNegativeLogWeight("-0.1"))
        assertNull(parseOptionalNonNegativeLogWeight("NaN"))
        assertNull(parseOptionalNonNegativeLogWeight("Infinity"))
    }

    @Test
    fun treatmentDaysRequireExplicitNonNegativeWholeNumber() {
        assertNull(parseNonNegativeLogDays(""))
        assertNull(parseNonNegativeLogDays("  "))
        assertNull(parseNonNegativeLogDays("-2"))
        assertNull(parseNonNegativeLogDays("1.5"))
        assertEquals(0, parseNonNegativeLogDays("0"))
        assertEquals(42, parseNonNegativeLogDays("42"))
    }

    @Test
    fun harvestMustContainAtLeastOneQuantity() {
        assertEquals(false, hasMeaningfulHarvestRecord(0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(true, hasMeaningfulHarvestRecord(1, 0.0, 0.0, 0.0, 0.0))
        assertEquals(true, hasMeaningfulHarvestRecord(0, 4.5, 0.0, 0.0, 0.0))
        assertEquals(true, hasMeaningfulHarvestRecord(0, 0.0, 0.0, 0.25, 0.0))
    }

    @Test
    fun convertsCompatibleFeedAndHarvestUnits() {
        assertEquals("3.785", convertLogAmountUnits("1", "gal", "L"))
        assertEquals("10", convertLogAmountUnits("4.536", "kg", "lb"))
        assertEquals("", convertLogAmountUnits("2", "gal", "lb"))
        assertEquals("", convertLogAmountUnits("", "lb", "kg"))
        assertEquals("1,5", convertLogAmountUnits("1,5", "kg", "kg"))
    }
}
