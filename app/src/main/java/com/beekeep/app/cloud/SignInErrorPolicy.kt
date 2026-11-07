package com.beekeep.app.cloud

import io.github.jan.supabase.auth.exception.AuthErrorCode

/**
 * User-facing copy for structured Supabase email/password sign-in failures.
 * Matching the SDK error code avoids relying on exception text that may be sanitized.
 */
internal object SignInErrorPolicy {
    const val INVALID_CREDENTIALS_MESSAGE =
        "The email or password you entered is incorrect."

    fun messageFor(errorCode: AuthErrorCode?): String? =
        if (errorCode == AuthErrorCode.InvalidCredentials) {
            INVALID_CREDENTIALS_MESSAGE
        } else {
            null
        }
}
