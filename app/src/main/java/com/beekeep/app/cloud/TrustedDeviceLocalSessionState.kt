package com.beekeep.app.cloud

import android.content.Context

/**
 * Persists the local-only state after this installation has been replaced for cloud access.
 * The active Room database is intentionally left untouched; this marker only prevents this
 * installation from starting cloud sync or realtime work after its Auth session is cleared.
 */
internal object TrustedDeviceLocalSessionState {
    private const val PREFS_NAME = "beekeep_prefs"
    private const val REPLACED_USER_ID_KEY = "trusted_device_replaced_local_user_id"
    private const val REPLACED_USER_EMAIL_KEY = "trusted_device_replaced_local_user_email"

    fun isReplaced(context: Context): Boolean = replacedUserId(context) != null

    fun replacedUserId(context: Context): String? =
        preferences(context).getString(REPLACED_USER_ID_KEY, null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    fun replacedUserEmail(context: Context): String =
        preferences(context).getString(REPLACED_USER_EMAIL_KEY, "").orEmpty()

    fun markReplaced(context: Context, userId: String, email: String): Boolean {
        val cleanUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return false
        return preferences(context).edit()
            .putString(REPLACED_USER_ID_KEY, cleanUserId)
            .putString(REPLACED_USER_EMAIL_KEY, email.trim())
            .remove(trustedDeviceFingerprintKey(cleanUserId))
            // Offline edits made by the replaced phone must be reviewed again before a later
            // personal upload. The review gate compares them with the current cloud inventory.
            .remove("cloud_data_attachment_reviewed_user_id")
            .commit()
    }

    /**
     * A new authenticated session means the user is deliberately retrying sign-in on this
     * installation. Remove the old local-only marker, but force a fresh trusted-device check.
     */
    fun clearForAuthenticatedSession(context: Context, currentUserId: String) {
        val cleanCurrentUserId = currentUserId.trim()
        val oldUserId = replacedUserId(context)
        val editor = preferences(context).edit()
            .remove(REPLACED_USER_ID_KEY)
            .remove(REPLACED_USER_EMAIL_KEY)
            .remove(trustedDeviceFingerprintKey(cleanCurrentUserId))
        if (oldUserId != null) editor.remove(trustedDeviceFingerprintKey(oldUserId))
        check(editor.commit()) { "BeeKeep couldn't restore this phone's device-verification state." }
    }

    fun clear(context: Context) {
        check(
            preferences(context).edit()
                .remove(REPLACED_USER_ID_KEY)
                .remove(REPLACED_USER_EMAIL_KEY)
                .commit()
        ) { "BeeKeep couldn't clear the previous device's local-only state." }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun trustedDeviceFingerprintKey(userId: String) =
        "trusted_device_cached_fingerprint_$userId"
}
