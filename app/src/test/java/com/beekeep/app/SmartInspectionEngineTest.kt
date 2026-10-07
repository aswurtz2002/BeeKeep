package com.beekeep.app

import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SmartInspectionEngineTest {
    private val hive = Hive(1, "27", "Home Yard", "Laying", "Yellow", "Graft", 12, 3, 7, 1.2, null)

    @Test fun healthScoreFlagsHighMitesAndDisease() {
        val inspection = Inspection(1, 1, System.currentTimeMillis(), 5, "Laying", 18, 300, "", diseaseFlags = "AFB")
        val score = SmartInspectionEngine.healthScore(hive.copy(mitePercent = 6.0), listOf(inspection))
        assertTrue(score < 60)
        assertEquals("High attention", SmartInspectionEngine.healthLabel(score))
    }

    @Test fun recommendationsDetectQueenlessAndStrengthDrop() {
        val now = System.currentTimeMillis()
        val previous = Inspection(2, 1, now - TimeUnit.DAYS.toMillis(5), 8, "Laying", 3, 300, "", honeyStores = 4)
        val latest = Inspection(3, 1, now, 5, "Queenless", 4, 300, "", honeyStores = 2)
        val result = SmartInspectionEngine.recommendations(hive.copy(queenStatus = "Queenless"), listOf(latest, previous))
        assertTrue(result.any { it.id == "queenless" })
        assertTrue(result.any { it.id == "strength-drop" })
    }

    @Test fun comparisonCalculatesDeltas() {
        val now = System.currentTimeMillis()
        val previous = Inspection(2, 1, now - 1000, 6, "Laying", 3, 300, "", honeyStores = 4, openBrood = 2, cappedBrood = 2)
        val latest = Inspection(3, 1, now, 8, "Spotted", 6, 300, "", honeyStores = 6, openBrood = 3, cappedBrood = 4)
        val c = SmartInspectionEngine.compare(listOf(latest, previous))!!
        assertEquals(2, c.strengthDelta)
        assertEquals(1.0, c.miteDelta!!, 0.001)
        assertEquals(2, c.honeyDelta)
        assertEquals(3, c.broodDelta)
        assertTrue(c.queenChanged)
    }
}
