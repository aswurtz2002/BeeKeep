package com.beekeep.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeKeepNfcPayloadTest {
    @Test fun newTagFormatHasNoHiveId() {
        assertEquals("BEEKEEP:TAG", BeeKeepNfcPayload.TAG_PAYLOAD)
        assertNull(BeeKeepNfcPayload.hiveId(BeeKeepNfcPayload.TAG_PAYLOAD))
        assertTrue(BeeKeepNfcPayload.isTagPayload(BeeKeepNfcPayload.TAG_PAYLOAD))
        assertEquals("application/vnd.beekeep.tag", BeeKeepNfcPayload.MIME_TYPE)
    }

    @Test fun legacyHivePayloadsAreStillRecognizedForMigration() {
        val oldPayload = BeeKeepNfcPayload.forHive(42)
        assertEquals(42L, BeeKeepNfcPayload.hiveId(oldPayload))
        assertFalse(BeeKeepNfcPayload.isTagPayload(oldPayload))
        assertTrue(BeeKeepNfcPayload.isBeeKeepPayload(oldPayload))
        assertEquals("application/vnd.beekeep.hive", BeeKeepNfcPayload.LEGACY_MIME_TYPE)
    }

    @Test fun invalidLegacyPayloadsDoNotParse() {
        assertNull(BeeKeepNfcPayload.hiveId(null))
        assertNull(BeeKeepNfcPayload.hiveId("hello"))
        assertNull(BeeKeepNfcPayload.hiveId("BEEKEEP:HIVE:not-a-number"))
        assertFalse(BeeKeepNfcPayload.isBeeKeepPayload("hello"))
    }
}
