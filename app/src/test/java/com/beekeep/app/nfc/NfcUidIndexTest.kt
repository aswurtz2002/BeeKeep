package com.beekeep.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcUidIndexTest {
    @Test
    fun sameUidFormattingOnSameHiveProducesOneValidAssignment() {
        val index = NfcUidIndex.fromAssignments(
            listOf("aa:bb:cc:dd" to 12L, "AABBCCDD" to 12L)
        )

        assertEquals(mapOf("AABBCCDD" to 12L), index.uniqueHiveIdByUid)
        assertTrue(index.ambiguousUids.isEmpty())
    }

    @Test
    fun samePhysicalUidLinkedToDifferentHivesIsBlockedAsAmbiguous() {
        val index = NfcUidIndex.fromAssignments(
            listOf("AA-BB-CC-DD" to 12L, "aabbccdd" to 45L)
        )

        assertFalse(index.uniqueHiveIdByUid.containsKey("AABBCCDD"))
        assertTrue(index.ambiguousUids.contains("AABBCCDD"))
    }

    @Test
    fun invalidUidsAreIgnoredRatherThanMapped() {
        val index = NfcUidIndex.fromAssignments(
            listOf("not-a-uid" to 12L, "ABC" to 45L, "04A1B2C3" to 67L)
        )

        assertEquals(mapOf("04A1B2C3" to 67L), index.uniqueHiveIdByUid)
        assertTrue(index.ambiguousUids.isEmpty())
    }
}
