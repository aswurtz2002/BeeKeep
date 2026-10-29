package com.beekeep.app

import com.beekeep.app.data.Hive

/**
 * Fast tagging is scoped to one apiary identity. Display names are only a fallback for
 * legacy hive rows without apiary_id; a different non-null apiary_id always wins.
 */
internal object FastTagTargetSelection {
    fun belongsToApiary(hive: Hive, apiaryId: Long?, apiaryName: String): Boolean {
        if (apiaryId != null && hive.apiaryId != null) return hive.apiaryId == apiaryId
        return hive.apiary.equals(apiaryName, ignoreCase = true)
    }

    fun orderedApiaryHives(
        hives: List<Hive>,
        apiaryId: Long?,
        apiaryName: String,
        startHiveId: Long?,
        includeStart: Boolean
    ): List<Hive> {
        val scoped = hives.filter { !it.isDead && belongsToApiary(it, apiaryId, apiaryName) }
        val startIndex = startHiveId?.let { id -> scoped.indexOfFirst { it.id == id } } ?: -1
        if (startIndex < 0) return scoped
        return if (includeStart) {
            scoped.drop(startIndex) + scoped.take(startIndex)
        } else {
            scoped.drop(startIndex + 1) + scoped.take(startIndex)
        }
    }

    fun nextUntaggedHive(
        hives: List<Hive>,
        apiaryId: Long?,
        apiaryName: String,
        startHiveId: Long?,
        includeStart: Boolean,
        excludedHiveIds: Set<Long> = emptySet(),
        lockedHiveIds: Set<Long> = emptySet()
    ): Hive? = orderedApiaryHives(
        hives = hives,
        apiaryId = apiaryId,
        apiaryName = apiaryName,
        startHiveId = startHiveId,
        includeStart = includeStart
    ).firstOrNull { hive ->
        hive.id !in excludedHiveIds &&
            hive.id !in lockedHiveIds &&
            hive.tagUid.isNullOrBlank()
    }
}
