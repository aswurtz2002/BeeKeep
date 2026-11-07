package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedDeviceCutoverPolicyTest {
    @Test
    fun approvedRequestSignsPreviouslyTrustedPhoneOut() {
        assertEquals(
            TrustedDeviceAccessStatus.REPLACED_LOCAL,
            TrustedDeviceCutoverPolicy.finalizationStatus(
                "approved_pending_finalize",
                previouslyTrustedHere = true
            )
        )
    }

    @Test
    fun finalizingRequestSignsPreviouslyTrustedPhoneOut() {
        assertEquals(
            TrustedDeviceAccessStatus.REPLACED_LOCAL,
            TrustedDeviceCutoverPolicy.finalizationStatus(
                "finalizing",
                previouslyTrustedHere = true
            )
        )
    }

    @Test
    fun approvedRequestWaitsForNewPhoneFinalization() {
        assertEquals(
            TrustedDeviceAccessStatus.PENDING_FINALIZATION,
            TrustedDeviceCutoverPolicy.finalizationStatus(
                "approved_pending_finalize",
                previouslyTrustedHere = false
            )
        )
    }

    @Test
    fun finalizingRequestWaitsForNewPhoneFinalization() {
        assertEquals(
            TrustedDeviceAccessStatus.PENDING_FINALIZATION,
            TrustedDeviceCutoverPolicy.finalizationStatus(
                "finalizing",
                previouslyTrustedHere = false
            )
        )
    }

    @Test
    fun unrelatedRequestStatesAreNotFinalizationStates() {
        assertNull(TrustedDeviceCutoverPolicy.finalizationStatus("pending", false))
        assertNull(TrustedDeviceCutoverPolicy.finalizationStatus("approved", false))
        assertNull(TrustedDeviceCutoverPolicy.finalizationStatus("denied", false))
        assertNull(TrustedDeviceCutoverPolicy.finalizationStatus(null, false))
    }
}
