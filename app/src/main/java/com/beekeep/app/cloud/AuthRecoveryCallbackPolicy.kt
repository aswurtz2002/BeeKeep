package com.beekeep.app.cloud

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Identifies password-recovery callbacks without treating ordinary email-confirmation callbacks
 * as password resets. PKCE callbacks carry a one-time code; implicit callbacks carry type=recovery.
 */
internal object AuthRecoveryCallbackPolicy {
    fun isPasswordRecoveryCallback(callbackUrl: String, recoveryRequestPending: Boolean): Boolean {
        val query = callbackUrl.substringAfter('?', "").substringBefore('#')
        val fragment = callbackUrl.substringAfter('#', "")
        val parameters = parseParameters(query) + parseParameters(fragment)
        val callbackType = parameters["type"]
        if (!callbackType.isNullOrBlank()) return callbackType.equals("recovery", ignoreCase = true)
        return recoveryRequestPending &&
            (parameters["code"].isNullOrBlank().not() || parameters["access_token"].isNullOrBlank().not())
    }

    private fun parseParameters(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            runCatching {
                URLDecoder.decode(name, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrNull()
        }.toMap()
    }
}
