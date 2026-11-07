package com.beekeep.app.cloud

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Cryptographic primitives shared by the Android Keystore adapter and JVM tests.
 *
 * Registration challenges are opaque, short-lived server values. Sign their exact UTF-8
 * representation; do not trim, normalize, or otherwise transform the challenge.
 */
internal object TrustedDeviceProof {
    fun signChallenge(privateKey: PrivateKey, challenge: String): String {
        require(challenge.isNotBlank() && challenge.length <= MAX_CHALLENGE_LENGTH) {
            "The trusted-device challenge is invalid."
        }
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(challenge.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    fun verifyChallenge(publicKey: PublicKey, challenge: String, signatureBase64: String): Boolean {
        if (challenge.isBlank() || challenge.length > MAX_CHALLENGE_LENGTH) return false
        return try {
            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(challenge.toByteArray(StandardCharsets.UTF_8))
            verifier.verify(signatureBytes)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    fun fingerprint(publicKeyEncoded: ByteArray): String {
        require(publicKeyEncoded.isNotEmpty()) { "The trusted-device public key is empty." }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val MAX_CHALLENGE_LENGTH = 256
}
