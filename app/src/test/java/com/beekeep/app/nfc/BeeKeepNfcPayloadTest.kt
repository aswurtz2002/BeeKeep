package com.beekeep.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepNfcPayloadTest {
    @Test fun roundTrip() {
        val payload = BeeKeepNfcPayload.forHive(42)
        assertEquals("BEEKEEP:HIVE:42", payload)
        assertEquals(42L, BeeKeepNfcPayload.hiveId(payload))
        assertTrue(BeeKeepNfcPayload.isBeeKeepPayload(payload))
    }

    @Test fun invalidPayloadsDoNotParse() {
        assertNull(BeeKeepNfcPayload.hiveId(null))
        assertNull(BeeKeepNfcPayload.hiveId("hello"))
        assertNull(BeeKeepNfcPayload.hiveId("BEEKEEP:HIVE:not-a-number"))
        assertFalse(BeeKeepNfcPayload.isBeeKeepPayload("hello"))
    }
}
