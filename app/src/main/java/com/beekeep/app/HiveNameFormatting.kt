package com.beekeep.app

/**
 * Starter hive numbers store their full display name. Older/manual records can still
 * contain only digits, so context messages retain the familiar "Hive 12" wording.
 */
internal fun hiveDisplayName(number: String): String = number.trim().ifBlank { "Hive" }

internal fun hiveReferenceName(number: String): String {
    val cleanName = hiveDisplayName(number)
    return if (cleanName.isNotEmpty() && cleanName.all(Char::isDigit)) {
        "Hive $cleanName"
    } else {
        cleanName
    }
}
