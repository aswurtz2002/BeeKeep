package com.beekeep.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcUidTest {
    @Test
    fun normalizesHexUidCaseAndCommonSeparators() {
        assertEquals("AABB0C", NfcUid.normalize(" aa:bb-0c "))
        assertEquals("04A1B2C3", NfcUid.normalize("04a1b2c3"))
    }

    @Test
    fun rejectsEmptyOddLengthAndNonHexUids() {
        assertNull(NfcUid.normalize(null))
        assertNull(NfcUid.normalize("   "))
        assertNull(NfcUid.normalize("ABC"))
        assertNull(NfcUid.normalize("AA:BG"))
        assertNull(NfcUid.normalize("AA/BB"))
    }

    @Test
    fun matchesOnlyValidCanonicalUids() {
        assertTrue(NfcUid.matches("aa:bb:0c", "AABB0C"))
        assertFalse(NfcUid.matches("AA:BG", "AABB"))
        assertFalse(NfcUid.matches("AABB", "AABC"))
        assertFalse(NfcUid.matches(null, "AABB"))
    }
}
