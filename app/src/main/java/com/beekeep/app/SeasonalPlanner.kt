package com.beekeep.app

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Editable starting templates for common seasonal hive-work rhythms.
 * These are planning aids, not treatment instructions or diagnosis rules.
 */
data class SeasonalTemplate(
    val id: String,
    val title: String,
    val description: String,
    val months: Set<Int>,
    val everyDays: Int,
    val iconLabel: String
)

object SeasonalPlanner {
    val templates = listOf(
        SeasonalTemplate("spring-inspection", "Spring colony inspections", "Regular inspection rhythm as colonies build.", setOf(3, 4, 5), 14, "SPRING"),
        SeasonalTemplate("swarm-watch", "Swarm-watch inspections", "Frequent checks during the higher-swarm-risk part of the season.", setOf(5, 6), 7, "SWARM"),
        SeasonalTemplate("flow-stores", "Flow & stores checks", "Track colony stores and general progress during the main season.", setOf(6, 7, 8), 14, "FLOW"),
        SeasonalTemplate("mite-check", "Mite checks", "Put routine mite monitoring on the calendar so it is not forgotten.", setOf(7, 8, 9), 14, "MITES"),
        SeasonalTemplate("winter-prep", "Winter-prep checks", "Review stores and colony condition while preparing for winter.", setOf(9, 10), 14, "FALL"),
        SeasonalTemplate("winter-check", "Winter checks", "Gentle periodic winter monitoring without opening the colony unnecessarily.", setOf(11, 12, 1, 2), 30, "WINTER")
    )

    fun seasonFor(date: LocalDate): String = when (date.monthValue) {
        3, 4, 5 -> "Spring"
        6, 7, 8 -> "Summer"
        9, 10, 11 -> "Fall"
        else -> "Winter"
    }

    fun generateDates(
        template: SeasonalTemplate,
        start: LocalDate,
        daysAhead: Int = 180
    ): List<LocalDate> {
        val endExclusive = start.plusDays(daysAhead.toLong())
        val dates = mutableListOf<LocalDate>()
        var lastGenerated: LocalDate? = null
        var date = start
        while (date.isBefore(endExclusive)) {
            if (date.monthValue in template.months) {
                val daysSinceLast = lastGenerated?.let { ChronoUnit.DAYS.between(it, date) } ?: Long.MAX_VALUE
                if (lastGenerated == null || daysSinceLast >= template.everyDays) {
                    dates += date
                    lastGenerated = date
                }
            } else {
                // Start a fresh cadence when entering another allowed season later in the year.
                lastGenerated = null
            }
            date = date.plusDays(1)
        }
        return dates
    }

    fun fingerprint(ruleId: String, date: LocalDate): String = "seasonal:$ruleId:${date}"

    fun isSeasonalKind(kind: String): Boolean = kind.startsWith("seasonal:")

    fun recurringKind(days: Long): String = "repeat:$days"
    fun recurrenceDays(kind: String): Long? = kind.removePrefix("repeat:").toLongOrNull()?.takeIf { kind.startsWith("repeat:") && it > 0 }
}
