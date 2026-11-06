package com.beekeep.app.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignupResponsePolicyTest {
    @Test
    fun emptyIdentityListMeansEmailAlreadyExists() {
        assertTrue(SignupResponsePolicy.isExistingEmail(0))
    }

    @Test
    fun signupWithAnIdentityIsNotTreatedAsDuplicate() {
        assertFalse(SignupResponsePolicy.isExistingEmail(1))
    }

    @Test
    fun missingIdentityMetadataIsNotTreatedAsDuplicate() {
        assertFalse(SignupResponsePolicy.isExistingEmail(null))
    }
}
