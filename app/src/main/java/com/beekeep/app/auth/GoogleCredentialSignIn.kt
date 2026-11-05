package com.beekeep.app.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal data class GoogleSignInToken(
    val idToken: String,
    val nonce: String
)

/**
 * Retrieves a Google ID token through Android's native Credential Manager UI.
 *
 * Google receives the SHA-256 hash of a fresh nonce. The raw nonce is passed to Supabase,
 * whose Kotlin SDK hashes it and compares it with the nonce claim in the ID token.
 */
internal object GoogleCredentialSignIn {
    suspend fun request(context: Context, webClientId: String): GoogleSignInToken {
        require(webClientId.isNotBlank()) { "Google sign-in is not configured." }
        val activity = context.findActivity()
            ?: error("Google sign-in needs an active Android screen.")

        val nonce = newNonce()
        val option = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(hashNonceForGoogle(nonce))
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val response = CredentialManager.create(activity).getCredential(
            MutableContextWrapper(activity),
            request
        )
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            error("Google did not return a valid sign-in credential.")
        }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val idToken = googleCredential.idToken
        if (idToken.isBlank()) error("Google returned an empty ID token.")
        return GoogleSignInToken(idToken = idToken, nonce = nonce)
    }

    internal fun newNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal fun hashNonceForGoogle(nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(nonce.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
