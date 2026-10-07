package com.beekeep.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SeasonalPlannerTest {
    @Test fun generateDatesStaysInsideAllowedMonths() {
        val template = SeasonalPlanner.templates.first { it.id == "mite-check" }
        val dates = SeasonalPlanner.generateDates(template, LocalDate.of(2026, 7, 1), 100)
        assertTrue(dates.isNotEmpty())
        assertTrue(dates.all { it.monthValue in template.months })
    }

    @Test fun generateDatesHonorsCadenceWithinSeason() {
        val template = SeasonalPlanner.templates.first { it.id == "spring-inspection" }
        val dates = SeasonalPlanner.generateDates(template, LocalDate.of(2026, 4, 1), 35)
        assertTrue(dates.size >= 2)
        assertEquals(14, java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]).toInt())
    }

    @Test fun recurrenceKindRoundTrips() {
        val kind = SeasonalPlanner.recurringKind(14)
        assertEquals(14L, SeasonalPlanner.recurrenceDays(kind))
        assertEquals(null, SeasonalPlanner.recurrenceDays("calendar"))
    }

    @Test fun seasonLabelsAreStable() {
        assertEquals("Winter", SeasonalPlanner.seasonFor(LocalDate.of(2026, 1, 10)))
        assertEquals("Spring", SeasonalPlanner.seasonFor(LocalDate.of(2026, 4, 10)))
        assertEquals("Summer", SeasonalPlanner.seasonFor(LocalDate.of(2026, 7, 10)))
        assertEquals("Fall", SeasonalPlanner.seasonFor(LocalDate.of(2026, 10, 10)))
    }
}
