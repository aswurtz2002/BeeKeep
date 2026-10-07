package com.beekeep.app.analytics

import com.beekeep.app.data.Hive
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Inspection
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Secondary time-series metrics for hive/apiary trend analysis.
 * Inspection-derived metrics preserve historical snapshots. Harvests are aggregated by period.
 */
object AdvancedAnalytics {
    enum class Metric(val label: String, val unit: String) {
        STRENGTH("Strength", "/10"),
        MITES("Mites", "%"),
        BROOD("Total brood", "frames"),
        HONEY_STORES("Honey stores", "frames"),
        QUEEN_STATUS("Queen score", "/100"),
        HARVEST("Dry honey harvest", "kg")
    }

    data class Point(
        val timestamp: Long,
        val value: Double,
        val sampleCount: Int = 1,
        val hiveCount: Int = 1
    )

    data class Summary(
        val average: Double?,
        val latest: Double?,
        val delta: Double?,
        val observations: Int,
        val hivesRepresented: Int,
        val total: Double = 0.0
    )

    fun value(metric: Metric, inspection: Inspection): Double = when (metric) {
        Metric.STRENGTH -> inspection.strength.toDouble()
        Metric.MITES -> inspection.mitePercent
        Metric.BROOD -> (inspection.eggs + inspection.openBrood + inspection.cappedBrood).toDouble()
        Metric.HONEY_STORES -> inspection.honeyStores.toDouble()
        Metric.QUEEN_STATUS -> queenScore(inspection.queenStatus)
        Metric.HARVEST -> 0.0
    }

    fun queenScore(status: String): Double = when (status.trim().lowercase()) {
        "laying", "spotted", "unspotted" -> 100.0
        "virgin" -> 60.0
        "queenless" -> 20.0
        else -> 50.0
    }

    fun hivePoints(
        hive: Hive,
        inspections: List<Inspection>,
        harvests: List<Harvest>,
        metric: Metric,
        range: HealthAnalytics.Range,
        now: Long = System.currentTimeMillis()
    ): List<Point> {
        return if (metric == Metric.HARVEST) {
            harvestPoints(setOf(hive.id), harvests, range, now)
        } else {
            val since = HealthAnalytics.rangeStart(range, now)
            inspections
                .asSequence()
                .filter { it.hiveId == hive.id && it.createdAt in since..now }
                .sortedBy { it.createdAt }
                .map { Point(it.createdAt, value(metric, it)) }
                .toList()
        }
    }

    fun apiaryPoints(
        apiaryName: String,
        hives: List<Hive>,
        inspections: List<Inspection>,
        harvests: List<Harvest>,
        metric: Metric,
        range: HealthAnalytics.Range,
        now: Long = System.currentTimeMillis()
    ): List<Point> {
        val ids = hives.filter { it.apiary.equals(apiaryName, ignoreCase = true) }.map { it.id }.toSet()
        return scopedPoints(ids, inspections, harvests, metric, range, now)
    }

    fun allPoints(
        hives: List<Hive>,
        inspections: List<Inspection>,
        harvests: List<Harvest>,
        metric: Metric,
        range: HealthAnalytics.Range,
        now: Long = System.currentTimeMillis()
    ): List<Point> = scopedPoints(hives.map { it.id }.toSet(), inspections, harvests, metric, range, now)

    fun summary(points: List<Point>): Summary {
        if (points.isEmpty()) return Summary(null, null, null, 0, 0)
        val ordered = points.sortedBy { it.timestamp }
        val average = ordered.map { it.value }.average()
        val latest = ordered.last().value
        return Summary(average, latest, latest - ordered.first().value, points.size, points.maxOf { it.hiveCount }, ordered.sumOf { it.value })
    }

    fun yBounds(metric: Metric, points: List<Point>): ClosedFloatingPointRange<Double> {
        val values = points.map { it.value }.filter { it.isFinite() }
        if (values.isEmpty()) return when (metric) {
            Metric.STRENGTH -> 0.0..10.0
            Metric.MITES -> 0.0..5.0
            Metric.BROOD, Metric.HONEY_STORES -> 0.0..10.0
            Metric.QUEEN_STATUS -> 0.0..100.0
            Metric.HARVEST -> 0.0..10.0
        }
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: min
        val padding = ((max - min) * 0.18).coerceAtLeast(1.0)
        val low = when (metric) {
            Metric.MITES, Metric.BROOD, Metric.HONEY_STORES, Metric.HARVEST -> (min - padding).coerceAtLeast(0.0)
            Metric.STRENGTH -> 0.0
            Metric.QUEEN_STATUS -> 0.0
        }
        val high = when (metric) {
            Metric.STRENGTH -> 10.0
            Metric.QUEEN_STATUS -> 100.0
            else -> max + padding
        }
        return low..high.coerceAtLeast(low + 1.0)
    }

    private fun scopedPoints(
        ids: Set<Long>,
        inspections: List<Inspection>,
        harvests: List<Harvest>,
        metric: Metric,
        range: HealthAnalytics.Range,
        now: Long
    ): List<Point> {
        if (ids.isEmpty()) return emptyList()
        return if (metric == Metric.HARVEST) {
            harvestPoints(ids, harvests, range, now)
        } else {
            val since = HealthAnalytics.rangeStart(range, now)
            val startDate = Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalDate()
            inspections
                .asSequence()
                .filter { it.hiveId in ids && it.createdAt in since..now }
                .groupBy {
                    val date = Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    val days = ChronoUnit.DAYS.between(startDate, date).toInt().coerceAtLeast(0)
                    val offset = (days / range.bucketDays) * range.bucketDays
                    startDate.plusDays(offset.toLong())
                }
                .entries
                .sortedBy { it.key }
                .map { (bucketDate, rows) ->
                    val perHive = rows.groupBy { it.hiveId }
                        .values
                        .map { hiveRows -> hiveRows.map { value(metric, it) }.average() }
                    Point(
                        bucketDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        perHive.average(),
                        sampleCount = rows.size,
                        hiveCount = perHive.size
                    )
                }
        }
    }

    private fun harvestPoints(
        ids: Set<Long>,
        harvests: List<Harvest>,
        range: HealthAnalytics.Range,
        now: Long
    ): List<Point> {
        val since = HealthAnalytics.rangeStart(range, now)
        val startDate = Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).toLocalDate()
        return harvests
            .asSequence()
            .filter { it.hiveId in ids && it.createdAt in since..now }
            .groupBy {
                val date = Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                val days = ChronoUnit.DAYS.between(startDate, date).toInt().coerceAtLeast(0)
                val offset = (days / range.bucketDays) * range.bucketDays
                startDate.plusDays(offset.toLong())
            }
            .entries
            .sortedBy { it.key }
            .map { (bucketDate, rows) ->
                val perHive = rows.groupBy { it.hiveId }
                    .mapValues { (_, hiveRows) -> hiveRows.sumOf { harvestWeightKg(it) } }
                    .values
                Point(
                    bucketDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    perHive.average(),
                    sampleCount = rows.size,
                    hiveCount = perHive.size
                )
            }
    }
    private fun harvestWeightKg(harvest: Harvest): Double {
        val value = if (harvest.dryHoneyWeight > 0) harvest.dryHoneyWeight else harvest.wetHoneyWeight
        return when (harvest.weightUnit.trim().lowercase()) {
            "kg", "kgs", "kilogram", "kilograms" -> value.coerceAtLeast(0.0)
            "lb", "lbs", "pound", "pounds" -> (value * 0.45359237).coerceAtLeast(0.0)
            else -> value.coerceAtLeast(0.0)
        }
    }

}
