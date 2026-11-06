package com.beekeep.app.cloud

/**
 * Supabase Auth returns an obfuscated user with an empty identities list when
 * sign-up is attempted for an email address that already belongs to a confirmed
 * account while email confirmation is enabled. A null identities value is not
 * enough evidence to reject a registration.
 */
internal object SignupResponsePolicy {
    fun isExistingEmail(identitiesCount: Int?): Boolean = identitiesCount == 0
}
