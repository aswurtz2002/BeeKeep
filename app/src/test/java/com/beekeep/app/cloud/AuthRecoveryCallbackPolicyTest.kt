package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRecoveryCallbackPolicyTest {
    @Test
    fun recognizesImplicitRecoveryTypeInFragment() {
        assertTrue(
            AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
                "com.beekeep.app://auth-callback#access_token=token&type=recovery",
                recoveryRequestPending = false
            )
        )
    }

    @Test
    fun recognizesRecoveryTypeInQuery() {
        assertTrue(
            AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
                "com.beekeep.app://auth-callback?type=recovery&code=one-time",
                recoveryRequestPending = false
            )
        )
    }

    @Test
    fun recognizesPkceCodeOnlyWhenAResetWasRequested() {
        val callback = "com.beekeep.app://auth-callback?code=one-time"
        assertTrue(AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(callback, true))
        assertFalse(AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(callback, false))
    }

    @Test
    fun doesNotTreatEmailConfirmationAsPasswordRecovery() {
        assertFalse(
            AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
                "com.beekeep.app://auth-callback?type=signup&code=one-time",
                recoveryRequestPending = false
            )
        )
        assertFalse(
            AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
                "com.beekeep.app://auth-callback?type=signup&code=one-time",
                recoveryRequestPending = true
            )
        )
    }

    @Test
    fun ignoresUnrelatedCallbackParameters() {
        assertFalse(
            AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
                "com.beekeep.app://auth-callback?error=access_denied",
                recoveryRequestPending = true
            )
        )
    }
}
