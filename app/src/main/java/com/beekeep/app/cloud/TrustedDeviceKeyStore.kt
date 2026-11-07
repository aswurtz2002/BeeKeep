package com.beekeep.app.cloud

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

internal data class TrustedDeviceKeyMaterial(
    val publicKeyBase64: String,
    val fingerprint: String
)

/**
 * Stores one non-exportable P-256 installation key in Android Keystore.
 *
 * The private key never leaves Android Keystore. Only the X.509 SubjectPublicKeyInfo
 * encoding is sent to Supabase. A new key is created automatically if this app
 * installation has no existing trusted-device key (for example, on a replacement phone).
 */
internal class TrustedDeviceKeyStore {
    @Synchronized
    fun getOrCreateKeyMaterial(): TrustedDeviceKeyMaterial {
        val existingKeyStore = loadKeyStore()
        if (!existingKeyStore.containsAlias(KEY_ALIAS)) {
            generateKeyPair()
        }
        // Reload after generation so this KeyStore view includes the new certificate.
        val keyStore = loadKeyStore()
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: error("BeeKeep couldn't load this phone's security key. Try again.")
        val encoded = publicKey.encoded
            ?: error("BeeKeep couldn't export the phone's public security key.")
        return TrustedDeviceKeyMaterial(
            publicKeyBase64 = Base64.getEncoder().encodeToString(encoded),
            fingerprint = TrustedDeviceProof.fingerprint(encoded)
        )
    }

    @Synchronized
    fun signChallenge(challenge: String): String {
        val keyStore = loadKeyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("This phone's BeeKeep security key is unavailable. Register this phone again.")
        return TrustedDeviceProof.signChallenge(privateKey, challenge)
    }

    fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.trim()?.takeIf { it.isNotEmpty() }
        val model = Build.MODEL.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(manufacturer, model)
            .distinct()
            .joinToString(" ")
            .take(MAX_DEVICE_LABEL_LENGTH)
            .ifBlank { "Android phone" }
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val parameters = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(parameters)
        generator.generateKeyPair()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.beekeep.app.trusted-device.p256.v1"
        const val EC_CURVE = "secp256r1"
        const val MAX_DEVICE_LABEL_LENGTH = 80
    }
}
