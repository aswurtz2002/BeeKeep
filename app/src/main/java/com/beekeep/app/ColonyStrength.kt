package com.beekeep.app

/**
 * The database and existing cloud payloads keep the original 0..10 strength value.
 * UI grades use 1..5 while existing database and cloud records remain on the original 0..10 scale.
 */
internal object ColonyStrength {
    private val labels = listOf("Very Weak", "Weak", "Moderate", "Strong", "Very strong")

    fun gradeFromStored(storedStrength: Int): Int =
        ((storedStrength.coerceIn(0, 10) + 1) / 2).coerceIn(1, 5)

    fun storedFromGrade(grade: Int): Int = grade.coerceIn(1, 5) * 2

    fun labelForGrade(grade: Int): String = labels[grade.coerceIn(1, 5) - 1]

    fun labelFromStored(storedStrength: Int): String = labelForGrade(gradeFromStored(storedStrength))
}
