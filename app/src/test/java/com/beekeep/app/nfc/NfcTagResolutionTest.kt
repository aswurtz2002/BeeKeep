package com.beekeep.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcTagResolutionTest {
    @Test fun usesAssignedHiveId() {
        assertEquals(19L, NfcTagResolution.resolveHiveId(19L, 42L, true))
    }

    @Test fun ignoresHiveIdsStoredInLegacyPayloads() {
        assertNull(NfcTagResolution.resolveHiveId(null, 42L, false))
        assertNull(NfcTagResolution.resolveHiveId(null, 42L, true))
    }

    @Test fun noAssignmentMeansNoHive() {
        assertNull(NfcTagResolution.resolveHiveId(null))
    }
}
