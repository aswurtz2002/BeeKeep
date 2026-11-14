package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustedDeviceErrorMessagesTest {
    @Test
    fun asymmetricJwtErrorDoesNotExposeGatewayPayload() {
        assertEquals(
            "BeeKeep couldn't verify this phone with the security service. Check your connection and try again.",
            TrustedDeviceErrorMessages.from(
                IllegalStateException("""{"code":"UNAUTHORIZED_ASYMMETRIC_JWT","message":"Invalid JWT"} URL: https://example.invalid/functions/v1/beekeep-device-access Headers: Authorization=[Bearer hidden]""")
            )
        )
    }

    @Test
    fun oldPublicKeyProtocolErrorIsPresentedWithoutJson() {
        assertEquals(
            "BeeKeep couldn't validate this phone approval request. Try again.",
            TrustedDeviceErrorMessages.from(
                IllegalStateException("""{"success":false,"error":"Invalid public key."}""")
            )
        )
    }

    @Test
    fun requestHeaderDiagnosticsAreNeverShown() {
        assertEquals(
            "BeeKeep couldn't reach the phone approval service. Check your connection and try again.",
            TrustedDeviceErrorMessages.from(
                IllegalStateException("Request failed URL: https://example.invalid Headers: Authorization=[Bearer hidden]")
            )
        )
    }
}
