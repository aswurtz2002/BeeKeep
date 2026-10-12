package com.beekeep.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcTagResolutionTest {
    @Test
    fun releasedTagNeverFallsBackToItsOldHivePayload() {
        assertNull(NfcTagResolution.resolveHiveId(assignedHiveId = null, payloadHiveId = 42L, tagWasReleased = true))
        assertEquals(
            BeeKeepNfcPayload.UNASSIGNED_PAYLOAD,
            NfcTagResolution.targetPayload(null, 42L, "BEEKEEP:HIVE:42", tagWasReleased = true)
        )
        assertTrue(NfcTagResolution.mayOverwriteStaleHivePayload(null, tagWasReleased = true))
    }

    @Test
    fun currentUidAssignmentWinsOverHistoricalReleaseAndPayload() {
        assertEquals(19L, NfcTagResolution.resolveHiveId(19L, 42L, tagWasReleased = true))
        assertEquals(
            "BEEKEEP:HIVE:19",
            NfcTagResolution.targetPayload(19L, 42L, "BEEKEEP:HIVE:42", tagWasReleased = true)
        )
        assertFalse(NfcTagResolution.mayOverwriteStaleHivePayload(19L, tagWasReleased = true))
    }

    @Test
    fun validPayloadStillWorksForTagsWithNoReleaseHistory() {
        assertEquals(42L, NfcTagResolution.resolveHiveId(null, 42L, tagWasReleased = false))
        assertEquals(
            "BEEKEEP:HIVE:42",
            NfcTagResolution.targetPayload(null, 42L, " BEEKEEP:HIVE:42 ", tagWasReleased = false)
        )
        assertFalse(NfcTagResolution.mayOverwriteStaleHivePayload(null, tagWasReleased = false))
    }

    @Test
    fun blankOrInvalidPayloadOnNeverAssignedTagBecomesUnassigned() {
        assertNull(NfcTagResolution.resolveHiveId(null, null, tagWasReleased = false))
        assertEquals(
            BeeKeepNfcPayload.UNASSIGNED_PAYLOAD,
            NfcTagResolution.targetPayload(null, null, null, tagWasReleased = false)
        )
    }
}
