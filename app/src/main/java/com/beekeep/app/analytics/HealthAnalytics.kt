package com.beekeep.app.analytics

import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Deterministic health analytics used by the mobile UI.
 *
 * Important design rule: historical graph points use the inspection snapshot so editing a
 * hive today does not rewrite what that hive looked like in the past. Current hive health can
 * additionally consider freshness and recent trends in SmartInspectionEngine.
 */
data class HealthPoint(
    val timestamp: Long,
    val score: Double,
    /** Number of inspections contributing to this bucket. */
    val sampleCount: Int = 1,
    /** Number of distinct hives contributing to this bucket. */
    val hiveCount: Int = 1
)

data class HealthSummary(
    val average: Double?,
    val latest: Double?,
    val delta: Double?
)

data class LatestAverage(
    val average: Double?,
    val hivesRepresented: Int
)

object HealthAnalytics {
    enum class Range(val days: Int, val label: String, val bucketDays: Int) {
        THIRTY(30, "30D", 7),
        NINETY(90, "90D", 14),
        ONE_EIGHTY(180, "6M", 21),
        THREE_SIXTY_FIVE(365, "1Y", 30)
    }

    /** Shared baseline used by both current-hive health and historical inspection graphs. */
    fun baseHealthScore(
        strength: Int,
        queenStatus: String,
        mitePercent: Double,
        diseaseFlags: String,
        honeyStores: Int
    ): Int {
        var score = strength.coerceIn(0, 10) * 7
        score += when (queenStatus) {
            "Queenless" -> -20
            "Virgin" -> 4
            "Laying", "Spotted", "Unspotted" -> 15
            else -> 8
        }
        score += when {
            mitePercent >= 5.0 -> -15
            mitePercent >= 3.0 -> -10
            mitePercent >= 2.0 -> -4
            else -> 6
        }
        if (diseaseFlags.isNotBlank()) score -= 15
        if (honeyStores <= 1) score -= 5
        return score.coerceIn(0, 100)
    }

    fun inspectionHealthScore(inspection: Inspection): Int = baseHealthScore(
        strength = inspection.strength,
        queenStatus = inspection.queenStatus,
        mitePercent = inspection.mitePercent,
        diseaseFlags = inspection.diseaseFlags,
        honeyStores = inspection.honeyStores
    )

    fun hiveHealthBaseline(hive: Hive): Int = baseHealthScore(
        strength = hive.strength,
        queenStatus = hive.queenStatus,
        mitePercent = hive.mitePercent,
        diseaseFlags = "",
        honeyStores = Int.MAX_VALUE
    )

    fun rangeStart(
        range: Range,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.minusDays((range.days - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    fun isInRange(timestamp: Long, range: Range, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        return timestamp in rangeStart(range, now, zone)..now
    }

    fun hivePoints(
        hive: Hive,
        inspections: List<Inspection>,
        range: Range,
        now: Long = System.currentTimeMillis()
    ): List<HealthPoint> {
        val since = rangeStart(range, now)
        return inspections
            .asSequence()
            .filter { it.hiveId == hive.id && it.createdAt in since..now }
            .sortedBy { it.createdAt }
            .map { HealthPoint(it.createdAt, inspectionHealthScore(it).toDouble(), sampleCount = 1, hiveCount = 1) }
            .toList()
    }

    fun apiaryPoints(
        apiaryName: String,
        hives: List<Hive>,
        inspections: List<Inspection>,
        range: Range,
        now: Long = System.currentTimeMillis()
    ): List<HealthPoint> {
        val apiaryHiveIds = hives
            .filter { it.apiary.equals(apiaryName, ignoreCase = true) }
            .map { it.id }
            .toSet()
        if (apiaryHiveIds.isEmpty()) return emptyList()
        val since = rangeStart(range, now)
        return groupedAveragePoints(apiaryHiveIds, inspections, range, since, now)
    }

    fun allApiaryPoints(
        hives: List<Hive>,
        inspections: List<Inspection>,
        range: Range,
        now: Long = System.currentTimeMillis()
    ): List<HealthPoint> {
        val ids = hives.map { it.id }.toSet()
        if (ids.isEmpty()) return emptyList()
        val since = rangeStart(range, now)
        return groupedAveragePoints(ids, inspections, range, since, now)
    }

    /** Latest inspection per hive within the selected range, with equal hive weighting. */
    fun latestAverage(
        hiveIds: Set<Long>,
        inspections: List<Inspection>,
        range: Range,
        now: Long = System.currentTimeMillis()
    ): LatestAverage {
        if (hiveIds.isEmpty()) return LatestAverage(null, 0)
        val since = rangeStart(range, now)
        val latestByHive = inspections
            .asSequence()
            .filter { it.hiveId in hiveIds && it.createdAt in since..now }
            .groupBy { it.hiveId }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { it.createdAt } }
        return LatestAverage(
            average = latestByHive.takeIf { it.isNotEmpty() }?.map { inspectionHealthScore(it).toDouble() }?.average(),
            hivesRepresented = latestByHive.size
        )
    }

    /** Backwards-compatible helper: latest known inspection per hive across all history. */
    fun currentAverage(hiveIds: Set<Long>, inspections: List<Inspection>): Double? {
        if (hiveIds.isEmpty()) return null
        val latestByHive = inspections
            .asSequence()
            .filter { it.hiveId in hiveIds }
            .groupBy { it.hiveId }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { it.createdAt } }
        return latestByHive.takeIf { it.isNotEmpty() }?.map { inspectionHealthScore(it).toDouble() }?.average()
    }

    fun observedHiveCount(
        hiveIds: Set<Long>,
        inspections: List<Inspection>,
        range: Range,
        now: Long = System.currentTimeMillis()
    ): Int {
        if (hiveIds.isEmpty()) return 0
        val since = rangeStart(range, now)
        return inspections.asSequence()
            .filter { it.hiveId in hiveIds && it.createdAt in since..now }
            .map { it.hiveId }
            .toSet()
            .size
    }

    fun summary(points: List<HealthPoint>): HealthSummary {
        if (points.isEmpty()) return HealthSummary(null, null, null)
        val ordered = points.sortedBy { it.timestamp }
        val average = ordered.map { it.score }.average()
        val latest = ordered.last().score
        val first = ordered.first().score
        return HealthSummary(average, latest, latest - first)
    }

    fun roundedScore(value: Double): Int = value.roundToInt().coerceIn(0, 100)

    fun bucketLabel(timestamp: Long, range: Range, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when (range) {
            Range.THIRTY, Range.NINETY, Range.ONE_EIGHTY -> "${date.monthValue}/${date.dayOfMonth}"
            Range.THREE_SIXTY_FIVE -> date.month.name.take(3).lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    private fun groupedAveragePoints(
        hiveIds: Set<Long>,
        inspections: List<Inspection>,
        range: Range,
        since: Long,
        now: Long
    ): List<HealthPoint> {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(since).atZone(zone).toLocalDate()
        val grouped = inspections
            .asSequence()
            .filter { it.hiveId in hiveIds && it.createdAt in since..now }
            .groupBy { bucketKey(it.createdAt, range.bucketDays, startDate, zone) }

        return grouped.entries
            .sortedBy { it.key }
            .mapNotNull { (bucketStart, rows) ->
                // Equal-weight each hive within the bucket so a frequently inspected hive
                // cannot dominate the apiary average.
                val perHive = rows.groupBy { it.hiveId }
                    .mapValues { (_, hiveRows) -> hiveRows.map { inspectionHealthScore(it).toDouble() }.average() }
                    .values
                perHive.takeIf { it.isNotEmpty() }?.let {
                    HealthPoint(
                        timestamp = bucketStart,
                        score = it.average(),
                        sampleCount = rows.size,
                        hiveCount = it.size
                    )
                }
            }
    }

    private fun bucketKey(timestamp: Long, bucketDays: Int, startDate: LocalDate, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val daysFromStart = ChronoUnit.DAYS.between(startDate, date).toInt().coerceAtLeast(0)
        val bucketOffset = (daysFromStart / bucketDays) * bucketDays
        return startDate.plusDays(bucketOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
