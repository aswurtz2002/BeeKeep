package com.beekeep.app.nfc

import java.util.Locale

/**
 * Canonical representation for a physical NFC UID.
 *
 * Android exposes tag IDs as bytes; older imports and diagnostics may store
 * them with separators or lowercase hex. Canonicalizing at boundaries keeps
 * equality checks consistent without accepting non-hex or partial identifiers.
 */
object NfcUid {
    fun normalize(value: String?): String? {
        val input = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val result = StringBuilder(input.length)
        for (character in input) {
            if (character.isWhitespace() || character == ':' || character == '-') continue
            val upper = character.uppercaseChar()
            if (upper !in '0'..'9' && upper !in 'A'..'F') return null
            result.append(upper)
        }
        if (result.isEmpty() || result.length % 2 != 0) return null
        return result.toString().uppercase(Locale.ROOT)
    }

    fun matches(first: String?, second: String?): Boolean {
        val normalizedFirst = normalize(first) ?: return false
        val normalizedSecond = normalize(second) ?: return false
        return normalizedFirst == normalizedSecond
    }
}
