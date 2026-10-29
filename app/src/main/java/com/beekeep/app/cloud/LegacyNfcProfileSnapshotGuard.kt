package com.beekeep.app.cloud

/**
 * Identifies hive snapshots produced by older app versions that bumped a hive's profile
 * timestamp solely to assign or remove an NFC tag. Those snapshots can contain otherwise
 * stale profile fields, so they must not win whole-record last-write-wins conflict resolution.
 *
 * The old NFC code used the exact same millisecond timestamp for its assignment/event and
 * the synthetic hive-profile update. Current profile edits have their own timestamp.
 */
internal data class HiveProfileSyncTimestamp(
    val hiveId: Long,
    val updatedAt: Long
)

internal data class NfcMutationSyncTimestamp(
    val hiveId: Long,
    val changedAt: Long
)

internal object LegacyNfcProfileSnapshotGuard {
    fun isLegacyNfcOnlyHiveSnapshot(
        hiveId: Long,
        updatedAt: Long,
        mutations: Collection<NfcMutationSyncTimestamp>
    ): Boolean = updatedAt > 0L && mutations.any {
        it.hiveId == hiveId && it.changedAt == updatedAt
    }

    fun legacyHiveIds(
        hiveProfiles: Collection<HiveProfileSyncTimestamp>,
        mutations: Collection<NfcMutationSyncTimestamp>
    ): Set<Long> = hiveProfiles.asSequence()
        .filter { isLegacyNfcOnlyHiveSnapshot(it.hiveId, it.updatedAt, mutations) }
        .map { it.hiveId }
        .toSet()
}
