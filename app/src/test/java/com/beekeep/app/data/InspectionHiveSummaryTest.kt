package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InspectionHiveSummaryTest {
    @Test
    fun appliesLatestInspectionFieldsWithoutReplacingHiveProfile() {
        val hive = HiveEntity(
            id = 41L,
            number = "41",
            apiaryName = "North Yard",
            apiaryId = 9L,
            queenStatus = "Unspotted",
            queenMarkColor = "Blue",
            queenOrigin = "Purchased",
            queenAgeMonths = 8,
            queenTemperament = 2,
            strength = 2,
            mitePercent = 1.0,
            tagUid = "04AABBCC",
            updatedAt = 123L,
            status = HiveStatus.ACTIVE,
            statusChangedAt = 80L,
            latitude = 49.8,
            longitude = -97.1,
            beeCountFrames = 3.0
        )
        val inspection = InspectionEntity(
            id = 77L,
            hiveId = 41L,
            createdAt = 200L,
            strength = 5,
            queenStatus = "Laying",
            miteCount = 6,
            sampleSize = 300,
            notes = "Strong pattern",
            beeCountFrames = 7.5
        )

        val merged = hive.withInspectionSummary(inspection)

        assertEquals(5, merged.strength)
        assertEquals("Laying", merged.queenStatus)
        assertEquals(2.0, merged.mitePercent, 0.0001)
        assertEquals(7.5, merged.beeCountFrames!!, 0.0001)
        assertEquals(hive.apiaryId, merged.apiaryId)
        assertEquals(hive.apiaryName, merged.apiaryName)
        assertEquals(hive.queenMarkColor, merged.queenMarkColor)
        assertEquals(hive.queenOrigin, merged.queenOrigin)
        assertEquals(hive.queenAgeMonths, merged.queenAgeMonths)
        assertEquals(hive.queenTemperament, merged.queenTemperament)
        assertEquals(hive.tagUid, merged.tagUid)
        assertEquals(hive.updatedAt, merged.updatedAt)
        assertEquals(hive.status, merged.status)
        assertEquals(hive.statusChangedAt, merged.statusChangedAt)
        assertEquals(hive.latitude!!, merged.latitude!!, 0.0001)
        assertEquals(hive.longitude!!, merged.longitude!!, 0.0001)
    }

    @Test
    fun ignoresInspectionForAnotherHive() {
        val hive = HiveEntity(
            id = 1L,
            number = "1",
            apiaryName = "Yard",
            queenStatus = "Unspotted",
            strength = 2,
            mitePercent = 3.0
        )
        val inspection = InspectionEntity(
            id = 2L,
            hiveId = 99L,
            createdAt = 200L,
            strength = 5,
            queenStatus = "Laying",
            miteCount = 3,
            sampleSize = 100,
            notes = ""
        )

        assertSame(hive, hive.withInspectionSummary(inspection))
    }
}
