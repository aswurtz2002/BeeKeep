package com.beekeep.app

import kotlin.math.roundToInt

/**
 * Starter hive numbers store their full display name. Older/manual records can still
 * contain only digits, so context messages retain the familiar "Hive 12" wording.
 */
internal fun formatBeeCountFrames(frames: Double): String {
    val halfSteps = (frames * 2.0).roundToInt().coerceIn(1, 20)
    return if (halfSteps % 2 == 0) {
        (halfSteps / 2).toString()
    } else {
        "${halfSteps / 2}.5"
    }
}

internal fun hiveDisplayName(number: String): String = number.trim().ifBlank { "Hive" }

internal fun hiveReferenceName(number: String): String {
    val cleanName = hiveDisplayName(number)
    return if (cleanName.isNotEmpty() && cleanName.all(Char::isDigit)) {
        "Hive $cleanName"
    } else {
        cleanName
    }
}
