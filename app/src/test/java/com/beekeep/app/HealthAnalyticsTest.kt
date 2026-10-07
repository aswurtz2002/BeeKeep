package com.beekeep.app

import com.beekeep.app.analytics.HealthAnalytics
import com.beekeep.app.analytics.HealthPoint
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthAnalyticsTest {
    private val hive1 = Hive(1, "01", "Home Yard", "Laying", "White", "Graft", 12, 2, 8, 1.0, null)
    private val hive2 = Hive(2, "02", "Home Yard", "Laying", "Yellow", "Package", 8, 3, 6, 1.4, null)
    private val outHive = Hive(3, "03", "Out Yard A", "Laying", "Red", "Swarm", 20, 4, 7, 2.0, null)

    private fun at(date: LocalDate, hour: Int = 0): Long = date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun inspectionScoreIsBoundedAndRewardsHealthyColony() {
        val healthy = Inspection(1, 1, at(LocalDate.of(2026, 6, 1)), 9, "Laying", 2, 300, "", honeyStores = 5)
        val stressed = healthy.copy(id = 2, strength = 4, queenStatus = "Queenless", miteCount = 20, diseaseFlags = "AFB", honeyStores = 0)
        assertTrue(HealthAnalytics.inspectionHealthScore(healthy) > HealthAnalytics.inspectionHealthScore(stressed))
        assertTrue(HealthAnalytics.inspectionHealthScore(stressed) in 0..100)
    }

    @Test fun apiaryAverageWeightsEachHiveEqually() {
        val date = at(LocalDate.of(2026, 6, 1))
        val h1 = Inspection(1, 1, date, 10, "Laying", 0, 300, "", honeyStores = 8)
        val h1Repeat = Inspection(2, 1, date + 3600000, 10, "Laying", 0, 300, "", honeyStores = 8)
        val h2 = Inspection(3, 2, date, 4, "Queenless", 20, 300, "", honeyStores = 0)
        val points = HealthAnalytics.apiaryPoints("Home Yard", listOf(hive1, hive2, outHive), listOf(h1, h1Repeat, h2), HealthAnalytics.Range.THIRTY, date + 2 * 86_400_000L)
        assertEquals(1, points.size)
        val perHive1 = HealthAnalytics.inspectionHealthScore(h1).toDouble()
        val perHive2 = HealthAnalytics.inspectionHealthScore(h2).toDouble()
        assertEquals((perHive1 + perHive2) / 2.0, points.single().score, 0.001)
        assertEquals(3, points.single().sampleCount)
        assertEquals(2, points.single().hiveCount)
    }

    @Test fun rangeUsesCalendarDaysAndExcludesOlderAndFutureRecords() {
        val now = at(LocalDate.of(2026, 6, 30), 12)
        val start = HealthAnalytics.rangeStart(HealthAnalytics.Range.THIRTY, now)
        val inside = Inspection(1, 1, start, 7, "Laying", 1, 300, "", honeyStores = 5)
        val outside = inside.copy(id = 2, createdAt = start - 1)
        val future = inside.copy(id = 3, createdAt = now + 1)
        val points = HealthAnalytics.hivePoints(hive1, listOf(outside, inside, future), HealthAnalytics.Range.THIRTY, now)
        assertEquals(1, points.size)
        assertEquals(start, points.single().timestamp)
    }

    @Test fun apiaryBucketsAlignToTheSelectedRangeStart() {
        val now = at(LocalDate.of(2026, 6, 30), 12)
        val start = HealthAnalytics.rangeStart(HealthAnalytics.Range.NINETY, now)
        val first = Inspection(11, 1, start, 8, "Laying", 0, 300, "", honeyStores = 5)
        val second = first.copy(id = 12, hiveId = 2, createdAt = start + 24 * 60 * 60 * 1000L)
        val points = HealthAnalytics.allApiaryPoints(listOf(hive1, hive2), listOf(first, second), HealthAnalytics.Range.NINETY, now)
        assertTrue(points.isNotEmpty())
        assertEquals(start, points.first().timestamp)
        assertEquals(2, points.first().hiveCount)
    }

    @Test fun latestAverageOnlyUsesHivesWithAnInspectionInsideSelectedRange() {
        val now = at(LocalDate.of(2026, 6, 30), 12)
        val recent = Inspection(1, 1, now, 8, "Laying", 1, 300, "", honeyStores = 5)
        val old = Inspection(2, 2, HealthAnalytics.rangeStart(HealthAnalytics.Range.THIRTY, now) - 1, 10, "Laying", 0, 300, "", honeyStores = 8)
        val result = HealthAnalytics.latestAverage(setOf(hive1.id, hive2.id), listOf(recent, old), HealthAnalytics.Range.THIRTY, now)
        assertEquals(1, result.hivesRepresented)
        assertEquals(HealthAnalytics.inspectionHealthScore(recent).toDouble(), result.average!!, 0.001)
        assertEquals(1, HealthAnalytics.observedHiveCount(setOf(hive1.id, hive2.id), listOf(recent, old), HealthAnalytics.Range.THIRTY, now))
    }

    @Test fun emptyAnalyticsStayEmpty() {
        assertTrue(HealthAnalytics.allApiaryPoints(emptyList(), emptyList(), HealthAnalytics.Range.NINETY).isEmpty())
        assertNull(HealthAnalytics.latestAverage(emptySet(), emptyList(), HealthAnalytics.Range.NINETY).average)
        assertEquals(0, HealthAnalytics.latestAverage(emptySet(), emptyList(), HealthAnalytics.Range.NINETY).hivesRepresented)
    }

    @Test fun summaryHandlesUnsortedPoints() {
        val points = listOf(
            HealthPoint(3, 70.0),
            HealthPoint(1, 50.0),
            HealthPoint(2, 60.0)
        )
        val summary = HealthAnalytics.summary(points)
        assertEquals(60.0, summary.average!!, 0.001)
        assertEquals(70.0, summary.latest!!, 0.001)
        assertEquals(20.0, summary.delta!!, 0.001)
    }

    @Test fun hiveAndInspectionBaselineUseTheSameWeights() {
        val inspection = Inspection(10, hive1.id, at(LocalDate.of(2026, 6, 1)), 8, "Laying", 3, 300, "", honeyStores = 5)
        val matchingHive = hive1.copy(strength = 8, queenStatus = "Laying", mitePercent = inspection.mitePercent)
        assertEquals(
            HealthAnalytics.inspectionHealthScore(inspection),
            HealthAnalytics.hiveHealthBaseline(matchingHive)
        )
    }
}
