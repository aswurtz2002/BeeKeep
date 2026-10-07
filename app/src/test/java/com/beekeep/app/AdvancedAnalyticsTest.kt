package com.beekeep.app

import com.beekeep.app.analytics.AdvancedAnalytics
import com.beekeep.app.analytics.HealthAnalytics
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAnalyticsTest {
    private val hive1 = Hive(1, "1", "Home", "Laying", "", "", null, 3, 8, 1.0, null)
    private val hive2 = Hive(2, "2", "Home", "Queenless", "", "", null, 3, 4, 4.0, null)

    private fun inspection(id: Long, hiveId: Long, day: Int, strength: Int, mites: Int, sample: Int, brood: Int, stores: Int, queen: String = "Laying") =
        Inspection(id, hiveId, day * 86_400_000L, strength, queen, mites, sample, "", null, null, null, eggs = brood / 3, openBrood = brood / 3, cappedBrood = brood - (brood / 3) * 2, honeyStores = stores)

    @Test fun equalHiveWeightingPreventsFrequentHiveDominance() {
        val rows = buildList {
            repeat(6) { add(inspection(10L + it, 1, 10, 8, 1, 100, 6, 6)) }
            add(inspection(30, 2, 10, 4, 4, 100, 3, 2, "Queenless"))
        }
        val points = AdvancedAnalytics.allPoints(listOf(hive1, hive2), rows, emptyList(), AdvancedAnalytics.Metric.STRENGTH, HealthAnalytics.Range.NINETY, now = 11 * 86_400_000L)
        assertEquals(1, points.size)
        assertEquals(6.0, points.first().value, 0.01)
        assertEquals(2, points.first().hiveCount)
    }

    @Test fun miteRateAndBroodUseInspectionSnapshots() {
        val rows = listOf(inspection(1, 1, 20, 7, 6, 300, 9, 5), inspection(2, 1, 30, 6, 3, 300, 6, 4))
        val mites = AdvancedAnalytics.hivePoints(hive1, rows, emptyList(), AdvancedAnalytics.Metric.MITES, HealthAnalytics.Range.NINETY, now = 31 * 86_400_000L)
        val brood = AdvancedAnalytics.hivePoints(hive1, rows, emptyList(), AdvancedAnalytics.Metric.BROOD, HealthAnalytics.Range.NINETY, now = 31 * 86_400_000L)
        assertEquals(2, mites.size)
        assertTrue(abs(mites.last().value - 1.0) < 0.001)
        assertEquals(6.0, brood.last().value, 0.01)
    }

    @Test fun queenScoreIsDeterministic() {
        assertEquals(100.0, AdvancedAnalytics.queenScore("Laying"), 0.0)
        assertEquals(60.0, AdvancedAnalytics.queenScore("Virgin"), 0.0)
        assertEquals(20.0, AdvancedAnalytics.queenScore("Queenless"), 0.0)
    }

    @Test fun harvestNormalizesPoundsToKilograms() {
        val harvest = Harvest(1, 1, 20 * 86_400_000L, 1, 10.0, 10.0, "lb", 0.0, 0.0, "")
        val points = AdvancedAnalytics.hivePoints(hive1, emptyList(), listOf(harvest), AdvancedAnalytics.Metric.HARVEST, HealthAnalytics.Range.NINETY, now = 21 * 86_400_000L)
        assertEquals(4.5359237, points.first().value, 0.00001)
    }
}
