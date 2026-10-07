package com.beekeep.app

import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import com.beekeep.app.data.Task
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit

data class SmartRecommendation(
    val id: String,
    val title: String,
    val reason: String,
    val action: String,
    val daysFromNow: Long,
    val priority: Priority
) {
    enum class Priority { HIGH, MEDIUM, ROUTINE }
}

data class InspectionComparison(
    val current: Inspection,
    val previous: Inspection?,
    val strengthDelta: Int?,
    val miteDelta: Double?,
    val honeyDelta: Int?,
    val broodDelta: Int?,
    val queenChanged: Boolean
)

object SmartInspectionEngine {
    private val fourteenDays = TimeUnit.DAYS.toMillis(14)

    fun healthScore(hive: Hive, inspections: List<Inspection>, now: Long = System.currentTimeMillis()): Int {
        val latest = inspections.maxByOrNull { it.createdAt }
        val recent = inspections.sortedByDescending { it.createdAt }.take(3)
        var score = com.beekeep.app.analytics.HealthAnalytics.hiveHealthBaseline(hive)
        if (latest == null || now - latest.createdAt > fourteenDays) score -= 8
        if (recent.size >= 2 && recent[0].strength <= recent[1].strength - 2) score -= 8
        if (recent.size >= 3 && recent[0].strength < recent[1].strength && recent[1].strength < recent[2].strength) score -= 4
        if (latest?.diseaseFlags?.isNotBlank() == true) score -= 15
        if (latest != null && latest.honeyStores <= 1) score -= 5
        return score.coerceIn(0, 100)
    }

    fun healthLabel(score: Int): String = when {
        score >= 80 -> "Strong"
        score >= 60 -> "Watch"
        score >= 40 -> "Needs attention"
        else -> "High attention"
    }

    fun recommendations(
        hive: Hive,
        inspections: List<Inspection>,
        tasks: List<Task> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): List<SmartRecommendation> {
        val sorted = inspections.sortedByDescending { it.createdAt }
        val latest = sorted.firstOrNull()
        val previous = sorted.getOrNull(1)
        val result = mutableListOf<SmartRecommendation>()
        fun addOnce(id: String, title: String, reason: String, action: String, days: Long, priority: SmartRecommendation.Priority) {
            if (result.none { it.id == id }) result += SmartRecommendation(id, title, reason, action, days, priority)
        }
        fun hasOpenTaskContaining(text: String): Boolean = tasks.any { !it.completed && it.title.contains(text, ignoreCase = true) }

        if (hive.queenStatus == "Queenless" && !hasOpenTaskContaining("queenless")) {
            addOnce("queenless", "Confirm queen / egg laying", "Hive is currently marked queenless.", "Inspect for queen, fresh eggs, or signs of a virgin queen.", 1, SmartRecommendation.Priority.HIGH)
        }
        if (hive.mitePercent >= 3.0 && !hasOpenTaskContaining("mite")) {
            addOnce("mites-high", "Repeat mite check", "Current mite rate is ${format(hive.mitePercent)}%.", "Run another wash and review your treatment plan.", 1, SmartRecommendation.Priority.HIGH)
        } else if (previous != null && latest != null && latest.mitePercent > previous.mitePercent + 0.5 && !hasOpenTaskContaining("mite")) {
            addOnce("mites-rising", "Recheck mites", "Mite rate is trending upward from ${format(previous.mitePercent)}% to ${format(latest.mitePercent)}%.", "Repeat a mite wash and watch the trend.", 3, SmartRecommendation.Priority.MEDIUM)
        }
        if (latest?.diseaseFlags?.isNotBlank() == true && !hasOpenTaskContaining("disease")) {
            addOnce("health-flags", "Review health flags", "Latest inspection recorded: ${latest.diseaseFlags}.", "Reinspect affected frames and document what you see.", 1, SmartRecommendation.Priority.HIGH)
        }
        if (latest != null && latest.emergencyCells + latest.supercedureCells + latest.swarmCells > 0 && !hasOpenTaskContaining("queen-cell")) {
            addOnce("queen-cells", "Follow up on queen cells", "Queen cells were recorded in the latest inspection.", "Confirm the colony's queen status and egg laying.", 14, SmartRecommendation.Priority.MEDIUM)
        }
        if (previous != null && latest != null && latest.strength <= previous.strength - 2 && !hasOpenTaskContaining("strength")) {
            addOnce("strength-drop", "Check brood and stores", "Strength dropped from ${previous.strength}/10 to ${latest.strength}/10.", "Look for brood pattern, food stores, queen performance, and pests.", 3, SmartRecommendation.Priority.HIGH)
        }
        if (latest != null && latest.honeyStores <= 1 && !hasOpenTaskContaining("stores")) {
            addOnce("stores-low", "Check honey stores", "Only ${latest.honeyStores} honey-store frame${if (latest.honeyStores == 1) " is" else "s are"} recorded.", "Verify available food and decide whether feeding is needed.", 2, SmartRecommendation.Priority.MEDIUM)
        }
        val lastQueenConfirmed = sorted.firstOrNull { it.queenStatus in setOf("Laying", "Spotted", "Unspotted") }
        if ((lastQueenConfirmed == null || now - lastQueenConfirmed.createdAt > fourteenDays) && !hasOpenTaskContaining("queen")) {
            addOnce("queen-confirm", "Confirm queen status", "Queen confirmation is more than 14 days old.", "Look for the queen, eggs, or a reliable laying pattern.", 1, SmartRecommendation.Priority.MEDIUM)
        }
        if (latest == null || now - latest.createdAt > fourteenDays) {
            addOnce("routine-inspection", "Routine inspection due", "No inspection has been recorded in the last 14 days.", "Complete a normal colony inspection.", 0, SmartRecommendation.Priority.ROUTINE)
        }
        return result.sortedWith(compareBy<SmartRecommendation> { it.priority.ordinal }.thenBy { it.daysFromNow }.thenBy { it.title })
    }

    fun compare(inspections: List<Inspection>): InspectionComparison? {
        val sorted = inspections.sortedByDescending { it.createdAt }
        val current = sorted.firstOrNull() ?: return null
        val previous = sorted.getOrNull(1)
        return InspectionComparison(
            current = current,
            previous = previous,
            strengthDelta = previous?.let { current.strength - it.strength },
            miteDelta = previous?.let { current.mitePercent - it.mitePercent },
            honeyDelta = previous?.let { current.honeyStores - it.honeyStores },
            broodDelta = previous?.let {
                (current.eggs + current.openBrood + current.cappedBrood) -
                    (it.eggs + it.openBrood + it.cappedBrood)
            },
            queenChanged = previous != null && current.queenStatus != previous.queenStatus
        )
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
