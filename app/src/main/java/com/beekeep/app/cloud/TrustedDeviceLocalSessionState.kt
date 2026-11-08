package com.beekeep.app.cloud

import android.content.Context

/**
 * Local-only marker for a phone whose Supabase session was revoked by an approved
 * replacement. This is a UI/offline-data marker only; server authorization always
 * comes from the current JWT session and the private trusted-session registry.
 */
internal object TrustedDeviceLocalSessionState {
    private const val PREFS_NAME = "beekeep_prefs"
    private const val REPLACED_USER_ID_KEY = "trusted_device_replaced_local_user_id"
    private const val REPLACED_USER_EMAIL_KEY = "trusted_device_replaced_local_user_email"
    private const val LAST_TRUSTED_USER_ID_KEY = "trusted_device_last_trusted_user_id"

    fun isReplaced(context: Context): Boolean = replacedUserId(context) != null

    fun replacedUserId(context: Context): String? =
        preferences(context).getString(REPLACED_USER_ID_KEY, null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    fun replacedUserEmail(context: Context): String =
        preferences(context).getString(REPLACED_USER_EMAIL_KEY, "").orEmpty()

    fun rememberTrustedUser(context: Context, userId: String) {
        val cleanUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return
        preferences(context).edit()
            .putString(LAST_TRUSTED_USER_ID_KEY, cleanUserId)
            .apply()
    }

    fun wasPreviouslyTrusted(context: Context, userId: String): Boolean =
        preferences(context).getString(LAST_TRUSTED_USER_ID_KEY, null)
            ?.trim() == userId.trim()

    fun lastTrustedUserId(context: Context): String? =
        preferences(context).getString(LAST_TRUSTED_USER_ID_KEY, null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    fun markReplaced(context: Context, userId: String, email: String): Boolean {
        val cleanUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return false
        return preferences(context).edit()
            .putString(REPLACED_USER_ID_KEY, cleanUserId)
            .putString(REPLACED_USER_EMAIL_KEY, email.trim())
            .remove("cloud_data_attachment_reviewed_user_id")
            .commit()
    }

    /** A deliberate successful sign-in starts a fresh verification attempt. */
    fun clearForAuthenticatedSession(context: Context, currentUserId: String) {
        require(currentUserId.isNotBlank())
        check(
            preferences(context).edit()
                .remove(REPLACED_USER_ID_KEY)
                .remove(REPLACED_USER_EMAIL_KEY)
                .remove(LAST_TRUSTED_USER_ID_KEY)
                .commit()
        ) { "BeeKeep couldn't restore this phone's device-verification state." }
    }

    /** Clear local markers for an intentional manual sign-out. */
    fun clear(context: Context) {
        check(
            preferences(context).edit()
                .remove(REPLACED_USER_ID_KEY)
                .remove(REPLACED_USER_EMAIL_KEY)
                .remove(LAST_TRUSTED_USER_ID_KEY)
                .commit()
        ) { "BeeKeep couldn't clear this phone's local sign-out state." }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
