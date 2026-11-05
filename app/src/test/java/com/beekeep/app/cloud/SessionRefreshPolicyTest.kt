package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRefreshPolicyTest {
    @Test
    fun retryBudgetIsCappedWithExponentialBackoff() {
        assertEquals(3, SessionRefreshPolicy.MAX_ATTEMPTS)
        assertEquals(1_000L, SessionRefreshPolicy.retryDelayMillis(1))
        assertEquals(2_000L, SessionRefreshPolicy.retryDelayMillis(2))
        assertNull(SessionRefreshPolicy.retryDelayMillis(3))
        assertNull(SessionRefreshPolicy.retryDelayMillis(4))
    }

    @Test
    fun transientHttpResponsesDoNotInvalidateTheStoredRefreshCredential() {
        assertFalse(SessionRefreshPolicy.isRejectedRefreshCredential(408))
        assertFalse(SessionRefreshPolicy.isRejectedRefreshCredential(429))
        assertFalse(SessionRefreshPolicy.isRejectedRefreshCredential(500))
    }

    @Test
    fun authClientErrorsInvalidateTheStoredRefreshCredential() {
        assertTrue(SessionRefreshPolicy.isRejectedRefreshCredential(400))
        assertTrue(SessionRefreshPolicy.isRejectedRefreshCredential(401))
        assertTrue(SessionRefreshPolicy.isRejectedRefreshCredential(403))
    }
}
