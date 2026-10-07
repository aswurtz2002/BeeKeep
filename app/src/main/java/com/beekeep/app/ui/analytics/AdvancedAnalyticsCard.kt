package com.beekeep.app.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.analytics.AdvancedAnalytics
import com.beekeep.app.analytics.HealthAnalytics
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import java.util.Locale

private data class AnalyticsUiState(
    val points: List<AdvancedAnalytics.Point>,
    val summary: AdvancedAnalytics.Summary
)

@Composable
fun AdvancedAnalyticsCard(
    hives: List<Hive>,
    inspections: List<Inspection>,
    harvests: List<Harvest>
) {
    var metricKey by rememberSaveable { mutableStateOf(AdvancedAnalytics.Metric.STRENGTH.name) }
    var rangeKey by rememberSaveable { mutableStateOf(HealthAnalytics.Range.NINETY.name) }
    var scopeKey by rememberSaveable { mutableStateOf("all") }
    var showScopePicker by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val metric = AdvancedAnalytics.Metric.valueOf(metricKey)
    val range = HealthAnalytics.Range.valueOf(rangeKey)
    val now = remember { System.currentTimeMillis() }
    val apiaries = hives.map { it.apiary }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    val selectedHiveId = scopeKey.removePrefix("hive:").toLongOrNull().takeIf { scopeKey.startsWith("hive:") }
    val selectedHive = selectedHiveId?.let { id -> hives.firstOrNull { it.id == id } }
    val selectedApiary = scopeKey.removePrefix("apiary:").takeIf { scopeKey.startsWith("apiary:") }
    val label = selectedHive?.let { "Hive ${it.number} • ${it.apiary}" } ?: selectedApiary ?: "All Apiaries"
    val scopeHiveIds = when {
        selectedHive != null -> setOf(selectedHive.id)
        selectedApiary != null -> hives.filter { it.apiary.equals(selectedApiary, ignoreCase = true) }.map { it.id }.toSet()
        else -> hives.map { it.id }.toSet()
    }
    val analyticsState by produceState(
        initialValue = AnalyticsUiState(emptyList(), AdvancedAnalytics.Summary(null, null, null, 0, 0)),
        hives, inspections, harvests, metricKey, rangeKey, scopeKey
    ) {
        value = withContext(Dispatchers.Default) {
            val points = when {
                selectedHive != null -> AdvancedAnalytics.hivePoints(selectedHive, inspections, harvests, metric, range, now)
                selectedApiary != null -> AdvancedAnalytics.apiaryPoints(selectedApiary, hives, inspections, harvests, metric, range, now)
                else -> AdvancedAnalytics.allPoints(hives, inspections, harvests, metric, range, now)
            }
            AnalyticsUiState(points, AdvancedAnalytics.summary(points))
        }
    }
    val points = analyticsState.points
    val summary = analyticsState.summary
    val queryTrimmed = query.trim()
    val filteredApiaries = apiaries.filter { it.contains(queryTrimmed, ignoreCase = true) }
    val filteredHives = hives.filter { it.number.contains(queryTrimmed, ignoreCase = true) || it.apiary.contains(queryTrimmed, ignoreCase = true) }

    if (showScopePicker) {
        AlertDialog(
            onDismissRequest = { showScopePicker = false },
            title = { Text("Analytics scope") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Find apiary or hive") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            TextButton(
                                onClick = { scopeKey = "all"; query = ""; showScopePicker = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("All Apiaries") }
                        }
                        if (filteredApiaries.isNotEmpty()) {
                            item { Text("Apiaries", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            filteredApiaries.forEach { name ->
                                item(key = "a:$name") {
                                    TextButton(onClick = { scopeKey = "apiary:$name"; query = ""; showScopePicker = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text(name)
                                    }
                                }
                            }
                        }
                        if (filteredHives.isNotEmpty()) {
                            item { Text("Hives", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            filteredHives.forEach { hive ->
                                item(key = "h:${hive.id}") {
                                    TextButton(onClick = { scopeKey = "hive:${hive.id}"; query = ""; showScopePicker = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Hive ${hive.number} • ${hive.apiary}")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showScopePicker = false }) { Text("DONE") } }
        )
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hive & apiary analytics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("Compare the metrics behind hive health over time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Analytics, null, tint = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = { query = ""; showScopePicker = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Text(label, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AdvancedAnalytics.Metric.values().forEach { candidate ->
                    FilterChip(selected = metric == candidate, onClick = { metricKey = candidate.name }, label = { Text(candidate.label) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HealthAnalytics.Range.values().forEach { candidate ->
                    FilterChip(selected = range == candidate, onClick = { rangeKey = candidate.name }, label = { Text(candidate.label) })
                }
            }
            MetricSummaryRow(summary, metric)
            if (points.isEmpty()) {
                Text(
                    "No ${metric.label.lowercase(Locale.US)} data in this range. Complete more inspections or record a harvest to build this trend.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                MetricTrendGraph(points, metric, range)
                if (metric == AdvancedAnalytics.Metric.QUEEN_STATUS) {
                    Text("Queen score: 100 = laying/spotted/unspotted • 60 = virgin • 20 = queenless. This is a visualization, not a diagnosis.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (metric == AdvancedAnalytics.Metric.HARVEST) {
                    Text("Harvest is normalized to kilograms and shown as the average recorded harvest per producing hive in each period.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetricSummaryRow(summary: AdvancedAnalytics.Summary, metric: AdvancedAnalytics.Metric) {
    fun fmt(value: Double?): String = when {
        value == null -> "—"
        metric == AdvancedAnalytics.Metric.STRENGTH -> String.format(Locale.US, "%.1f/10", value)
        metric == AdvancedAnalytics.Metric.MITES -> String.format(Locale.US, "%.2f%%", value)
        metric == AdvancedAnalytics.Metric.QUEEN_STATUS -> "${value.toInt()}/100"
        metric == AdvancedAnalytics.Metric.HARVEST -> String.format(Locale.US, "%.1f kg", value)
        else -> String.format(Locale.US, "%.1f", value)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallMetric("Average", fmt(summary.average), Modifier.weight(1f))
        SmallMetric("Latest", fmt(summary.latest), Modifier.weight(1f))
        SmallMetric("Change", summary.delta?.let { String.format(Locale.US, "%+.1f", it) } ?: "—", Modifier.weight(1f))
    }
    if (metric == AdvancedAnalytics.Metric.HARVEST) {
        Text(
            "Range total ${String.format(Locale.US, "%.1f kg", summary.total)} • ${summary.hivesRepresented} hive${if (summary.hivesRepresented == 1) "" else "s"} represented",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            "${summary.observations} periods • ${summary.hivesRepresented} hive${if (summary.hivesRepresented == 1) "" else "s"} represented at peak",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun MetricTrendGraph(points: List<AdvancedAnalytics.Point>, metric: AdvancedAnalytics.Metric, range: HealthAnalytics.Range) {
    val sorted = points.sortedBy { it.timestamp }
    val bounds = AdvancedAnalytics.yBounds(metric, points)
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val surface = MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().height(190.dp)) {
        Column(Modifier.width(44.dp).height(190.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(formatAxis(bounds.endInclusive, metric), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatAxis((bounds.start + bounds.endInclusive) / 2.0, metric), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatAxis(bounds.start, metric), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).height(190.dp).clip(RoundedCornerShape(14.dp)).background(surface)) {
            val left = 10f
            val right = size.width - 10f
            val top = 10f
            val bottom = size.height - 10f
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = bottom - fraction * (bottom - top)
                drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            }
            val minTime = sorted.first().timestamp.toDouble()
            val maxTime = sorted.last().timestamp.toDouble()
            val span = (maxTime - minTime).coerceAtLeast(1.0)
            fun x(t: Long) = left + (((t - minTime) / span) * (right - left)).toFloat()
            fun y(v: Double): Float = bottom - (((v.coerceIn(bounds.start, bounds.endInclusive) - bounds.start) / (bounds.endInclusive - bounds.start)) * (bottom - top)).toFloat()
            val line = Path().apply {
                moveTo(x(sorted.first().timestamp), y(sorted.first().value))
                sorted.drop(1).forEach { lineTo(x(it.timestamp), y(it.value)) }
            }
            if (sorted.size > 1) {
                val fill = Path().apply {
                    moveTo(x(sorted.first().timestamp), bottom)
                    sorted.forEach { lineTo(x(it.timestamp), y(it.value)) }
                    lineTo(x(sorted.last().timestamp), bottom)
                    close()
                }
                drawPath(fill, primary.copy(alpha = 0.10f))
            }
            drawPath(line, primary, style = Stroke(width = 5f))
            sorted.forEach { point ->
                val center = Offset(x(point.timestamp), y(point.value))
                drawCircle(primary, 6f, center)
                drawCircle(surface, 2.5f, center)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 46.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        sorted.takeIf { it.isNotEmpty() }?.let { data ->
            val indexes = when {
                data.size == 1 -> listOf(0)
                data.size == 2 -> listOf(0, 1)
                else -> listOf(0, data.lastIndex / 2, data.lastIndex).distinct()
            }
            indexes.forEach { index ->
                val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(data[index].timestamp))
                Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatAxis(value: Double, metric: AdvancedAnalytics.Metric): String = when (metric) {
    AdvancedAnalytics.Metric.STRENGTH -> String.format(Locale.US, "%.0f", value)
    AdvancedAnalytics.Metric.MITES -> String.format(Locale.US, "%.1f", value)
    AdvancedAnalytics.Metric.BROOD, AdvancedAnalytics.Metric.HONEY_STORES -> String.format(Locale.US, "%.0f", value)
    AdvancedAnalytics.Metric.QUEEN_STATUS -> String.format(Locale.US, "%.0f", value)
    AdvancedAnalytics.Metric.HARVEST -> String.format(Locale.US, "%.1f", value)
}
