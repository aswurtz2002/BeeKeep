package com.beekeep.app.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDeviceErrorMessagesTest {
    @Test
    fun revokedSessionErrorsAreSanitized() {
        val error = IllegalStateException(
            """{"code":"UNAUTHORIZED_ASYMMETRIC_JWT","message":"Invalid JWT"} URL: https://example.invalid/functions/v1/beekeep-device-access Headers: Authorization=[Bearer hidden]"""
        )
        assertEquals(
            TrustedDeviceApiFailureKind.AUTH_REJECTED,
            TrustedDeviceErrorMessages.failureKind(error)
        )
        assertEquals(
            "Your sign-in session is no longer active. Please sign in again.",
            TrustedDeviceErrorMessages.from(error)
        )
    }

    @Test
    fun unexpectedServerPayloadDoesNotReachTheScreen() {
        assertEquals(
            "Something went wrong. Please try again.",
            TrustedDeviceErrorMessages.from(
                IllegalStateException("""{"success":false,"error":"Invalid public key."}""")
            )
        )
    }

    @Test
    fun wrongCodeHasOnePlainLanguageMessage() {
        assertEquals(
            "That code isn't right. Try again.",
            TrustedDeviceErrorMessages.from(IllegalStateException("Invalid OTP"))
        )
    }

    @Test
    fun expiredCodeHasOnePlainLanguageMessage() {
        assertEquals(
            "That code has expired. We can send a new one.",
            TrustedDeviceErrorMessages.from(IllegalStateException("Token has expired"))
        )
    }

    @Test
    fun rateLimitHasOnePlainLanguageMessage() {
        assertEquals(
            "Too many tries. Please wait a minute.",
            TrustedDeviceErrorMessages.from(IllegalStateException("Too many requests: 429"))
        )
    }

    @Test
    fun networkFailureIsNotTreatedAsRevocation() {
        val error = IOException("Unable to resolve host")
        assertEquals(
            TrustedDeviceApiFailureKind.NETWORK,
            TrustedDeviceErrorMessages.failureKind(error)
        )
        assertEquals(
            "No connection. Check your internet and try again.",
            TrustedDeviceErrorMessages.from(error)
        )
    }

    @Test
    fun headersAndUrlsAreNeverShown() {
        val message = TrustedDeviceErrorMessages.from(
            IllegalStateException(
                "Request failed URL: https://example.invalid Headers: Authorization=[Bearer hidden]"
            )
        )
        assertTrue(!message.contains("https://"))
        assertTrue(!message.contains("Authorization"))
        assertEquals("Something went wrong. Please try again.", message)
    }
}
