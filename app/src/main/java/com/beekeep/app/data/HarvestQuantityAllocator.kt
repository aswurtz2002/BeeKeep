package com.beekeep.app.data

import java.math.BigDecimal
import java.math.BigInteger

internal data class HarvestQuantityAllocation(
    val supersPulled: Int,
    val wetHoneyWeight: Double,
    val dryHoneyWeight: Double,
    val waxWeight: Double,
    val propolisWeight: Double
)

/**
 * Splits apiary-total harvest amounts across hives in a stable order.
 * Whole supers and the smallest entered decimal unit are distributed with any
 * remainder assigned to the earliest hives. Decimal totals are conserved exactly
 * at the precision representable by the entered Double value (minimum 2 decimals).
 */
internal fun allocateApiaryHarvestQuantities(
    supersPulled: Int,
    wetHoneyWeight: Double,
    dryHoneyWeight: Double,
    waxWeight: Double,
    propolisWeight: Double,
    hiveCount: Int
): List<HarvestQuantityAllocation> {
    require(hiveCount > 0) { "At least one hive is required to allocate a harvest." }
    require(supersPulled >= 0) { "Supers pulled cannot be negative." }

    val supers = splitWholeQuantity(supersPulled, hiveCount)
    val wet = splitDecimalQuantity(wetHoneyWeight, hiveCount)
    val dry = splitDecimalQuantity(dryHoneyWeight, hiveCount)
    val wax = splitDecimalQuantity(waxWeight, hiveCount)
    val propolis = splitDecimalQuantity(propolisWeight, hiveCount)

    return List(hiveCount) { index ->
        HarvestQuantityAllocation(
            supersPulled = supers[index],
            wetHoneyWeight = wet[index],
            dryHoneyWeight = dry[index],
            waxWeight = wax[index],
            propolisWeight = propolis[index]
        )
    }
}

private fun splitWholeQuantity(total: Int, count: Int): List<Int> {
    require(total >= 0) { "Harvest quantities cannot be negative." }
    val base = total / count
    val remainder = total % count
    return List(count) { index -> base + if (index < remainder) 1 else 0 }
}

private fun splitDecimalQuantity(total: Double, count: Int): List<Double> {
    require(total.isFinite() && total >= 0.0) { "Harvest weights must be finite and non-negative." }
    val decimal = BigDecimal.valueOf(total).stripTrailingZeros()
    val scale = maxOf(2, decimal.scale().coerceAtLeast(0))
    val smallestUnits = decimal.movePointRight(scale).toBigIntegerExact()
    val divisor = BigInteger.valueOf(count.toLong())
    val parts = smallestUnits.divideAndRemainder(divisor)
    val base = parts[0]
    val remainder = parts[1].toInt()

    return List(count) { index ->
        val units = base + if (index < remainder) BigInteger.ONE else BigInteger.ZERO
        BigDecimal(units, scale).toDouble()
    }
}
