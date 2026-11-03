package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class HarvestQuantityAllocatorTest {
    @Test
    fun distributesWholeSupersAndDecimalRemaindersToFirstHives() {
        val allocations = allocateApiaryHarvestQuantities(
            supersPulled = 10,
            wetHoneyWeight = 10.0,
            dryHoneyWeight = 25.0,
            waxWeight = 2.0,
            propolisWeight = 1.0,
            hiveCount = 3
        )

        assertEquals(listOf(4, 3, 3), allocations.map { it.supersPulled })
        assertEquals(listOf(3.34, 3.33, 3.33), allocations.map { it.wetHoneyWeight })
        assertEquals(listOf(8.34, 8.33, 8.33), allocations.map { it.dryHoneyWeight })
        assertEquals(listOf(0.67, 0.67, 0.66), allocations.map { it.waxWeight })
        assertEquals(listOf(0.34, 0.33, 0.33), allocations.map { it.propolisWeight })
    }

    @Test
    fun conservesTotalsAtEnteredDecimalPrecision() {
        val allocations = allocateApiaryHarvestQuantities(
            supersPulled = 7,
            wetHoneyWeight = 8.125,
            dryHoneyWeight = 1.0,
            waxWeight = 0.0,
            propolisWeight = 0.003,
            hiveCount = 4
        )

        assertEquals(7, allocations.sumOf { it.supersPulled })
        assertEquals(0, BigDecimal("8.125").compareTo(allocations.sumOf { BigDecimal.valueOf(it.wetHoneyWeight) }))
        assertEquals(0, BigDecimal("1.00").compareTo(allocations.sumOf { BigDecimal.valueOf(it.dryHoneyWeight) }))
        assertEquals(0, BigDecimal("0.000").compareTo(allocations.sumOf { BigDecimal.valueOf(it.waxWeight) }))
        assertEquals(0, BigDecimal("0.003").compareTo(allocations.sumOf { BigDecimal.valueOf(it.propolisWeight) }))
    }

    @Test
    fun rejectsEmptyTargetAndInvalidAmounts() {
        assertThrows(IllegalArgumentException::class.java) {
            allocateApiaryHarvestQuantities(1, 1.0, 1.0, 1.0, 1.0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            allocateApiaryHarvestQuantities(1, Double.NaN, 1.0, 1.0, 1.0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            allocateApiaryHarvestQuantities(-1, 1.0, 1.0, 1.0, 1.0, 2)
        }
    }
}
