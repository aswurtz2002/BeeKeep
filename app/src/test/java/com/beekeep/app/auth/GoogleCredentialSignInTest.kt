package com.beekeep.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class GoogleCredentialSignInTest {
    @Test
    fun generatesUrlSafeRandomNonce() {
        val first = GoogleCredentialSignIn.newNonce()
        val second = GoogleCredentialSignIn.newNonce()

        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertFalse(first == second)
    }

    @Test
    fun hashesRawNonceBeforeSendingItToGoogle() {
        val nonce = "beekeep-google-nonce-test"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

        assertEquals(expected, GoogleCredentialSignIn.hashNonceForGoogle(nonce))
    }
}
