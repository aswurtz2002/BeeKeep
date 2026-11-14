package com.beekeep.app.cloud

/**
 * Keeps transport diagnostics and request headers out of user-facing device-safety messages.
 * The server remains fail-closed; this only controls how failures are presented.
 */
internal object TrustedDeviceErrorMessages {
    private const val UNAVAILABLE =
        "BeeKeep couldn't reach the phone approval service. Check your connection and try again."

    fun from(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        if (raw.isBlank()) return UNAVAILABLE

        return when {
            raw.contains("UNAUTHORIZED_ASYMMETRIC_JWT", ignoreCase = true) ||
                raw.contains("Invalid JWT", ignoreCase = true) ->
                "BeeKeep couldn't verify this phone with the security service. Check your connection and try again."

            raw.contains("Invalid public key", ignoreCase = true) ->
                "BeeKeep couldn't validate this phone approval request. Try again."

            raw.contains("URL:", ignoreCase = true) ||
                raw.contains("Headers:", ignoreCase = true) ||
                raw.trimStart().startsWith("{") ->
                UNAVAILABLE

            else -> raw.take(220)
        }
    }
}
