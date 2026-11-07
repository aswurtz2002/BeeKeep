package com.beekeep.app.cloud

import io.github.jan.supabase.auth.exception.AuthErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInErrorPolicyTest {
    @Test
    fun invalidCredentialsUsesRequestedMessage() {
        assertEquals(
            "The email or password you entered is incorrect.",
            SignInErrorPolicy.messageFor(AuthErrorCode.InvalidCredentials)
        )
    }

    @Test
    fun unverifiedEmailIsNotMislabelledAsIncorrectCredentials() {
        assertNull(SignInErrorPolicy.messageFor(AuthErrorCode.EmailNotConfirmed))
    }

    @Test
    fun unknownOrMissingAuthCodeKeepsExistingErrorHandling() {
        assertNull(SignInErrorPolicy.messageFor(null))
    }
}
