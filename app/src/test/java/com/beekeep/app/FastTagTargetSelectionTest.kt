package com.beekeep.app

import com.beekeep.app.data.Hive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastTagTargetSelectionTest {
    @Test
    fun usesApiaryIdWhenNamesMatchButIdsDoNot() {
        val selectedApiaryHive = hive(1L, "1", 100L, "North")
        val otherApiaryHive = hive(2L, "2", 200L, "North")
        val result = FastTagTargetSelection.nextUntaggedHive(
            hives = listOf(otherApiaryHive, selectedApiaryHive),
            apiaryId = 100L,
            apiaryName = "North",
            startHiveId = 1L,
            includeStart = true
        )
        assertEquals(1L, result?.id)
    }

    @Test
    fun skipsLedgerAssignedHiveEvenIfTagCacheIsBlank() {
        val alreadyAssigned = hive(1L, "1", 100L, "North")
        val untagged = hive(2L, "2", 100L, "North")
        val result = FastTagTargetSelection.nextUntaggedHive(
            hives = listOf(alreadyAssigned, untagged),
            apiaryId = 100L,
            apiaryName = "North",
            startHiveId = 1L,
            includeStart = true,
            excludedHiveIds = setOf(1L)
        )
        assertEquals(2L, result?.id)
    }

    @Test
    fun returnsNoTargetWhenEveryHiveIsAlreadyAssigned() {
        assertNull(
            FastTagTargetSelection.nextUntaggedHive(
                hives = listOf(hive(1L, "1", 100L, "North")),
                apiaryId = 100L,
                apiaryName = "North",
                startHiveId = 1L,
                includeStart = true,
                excludedHiveIds = setOf(1L)
            )
        )
    }

    private fun hive(id: Long, number: String, apiaryId: Long, apiaryName: String) = Hive(
        id = id,
        number = number,
        apiary = apiaryName,
        queenStatus = "Laying",
        queenMarkColor = "",
        queenOrigin = "",
        queenAgeMonths = null,
        queenTemperament = 3,
        strength = 3,
        mitePercent = 0.0,
        tagUid = null,
        apiaryId = apiaryId
    )
}
