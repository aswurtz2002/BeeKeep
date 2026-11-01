package com.beekeep.app

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * Immutable, equality-comparable count snapshot used by the Apiaries screen.
 * The ViewModel replaces it only when the per-apiary totals actually change,
 * so unrelated hive profile updates do not invalidate apiary rows.
 */
@Immutable
data class ApiaryHiveCounts(
    private val countsByNormalizedName: Map<String, Int>
) {
    fun countFor(apiaryName: String): Int =
        countsByNormalizedName[apiaryName.lowercase(Locale.ROOT)] ?: 0
}
