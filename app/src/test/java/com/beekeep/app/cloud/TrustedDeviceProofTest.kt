package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class TrustedDeviceProofTest {
    @Test
    fun signsAndVerifiesTheExactServerChallenge() {
        val keyPair = generateKeyPair()
        val challenge = "beekeep-device-challenge-12345"

        val signed = TrustedDeviceProof.signChallenge(keyPair.private, challenge)

        assertTrue(TrustedDeviceProof.verifyChallenge(keyPair.public, challenge, signed))
        assertFalse(TrustedDeviceProof.verifyChallenge(keyPair.public, "$challenge-edited", signed))
    }

    @Test
    fun fingerprintIsDeterministicSha256OfEncodedPublicKey() {
        val keyPair = generateKeyPair()
        val encoded = keyPair.public.encoded

        val first = TrustedDeviceProof.fingerprint(encoded)
        val second = TrustedDeviceProof.fingerprint(encoded)

        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(first, second)
    }

    @Test
    fun signaturesAreBase64AndVerifyAfterTransportEncoding() {
        val keyPair = generateKeyPair()
        val signed = TrustedDeviceProof.signChallenge(keyPair.private, "device-proof-over-the-wire")
        val decoded = Base64.getDecoder().decode(signed)
        val transported = Base64.getEncoder().encodeToString(decoded)

        assertTrue(TrustedDeviceProof.verifyChallenge(keyPair.public, "device-proof-over-the-wire", transported))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankChallengeCannotBeSigned() {
        TrustedDeviceProof.signChallenge(generateKeyPair().private, " ")
    }

    private fun generateKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
}
