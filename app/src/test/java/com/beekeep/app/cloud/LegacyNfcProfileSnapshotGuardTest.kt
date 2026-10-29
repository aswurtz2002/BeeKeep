package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyNfcProfileSnapshotGuardTest {
    @Test
    fun flagsHiveSnapshotStampedAtNfcAssignment() {
        val mutations = listOf(NfcMutationSyncTimestamp(hiveId = 42L, changedAt = 1_000L))
        assertTrue(LegacyNfcProfileSnapshotGuard.isLegacyNfcOnlyHiveSnapshot(42L, 1_000L, mutations))
    }

    @Test
    fun flagsHiveSnapshotStampedAtNfcRelease() {
        val mutations = listOf(NfcMutationSyncTimestamp(hiveId = 42L, changedAt = 2_000L))
        assertTrue(LegacyNfcProfileSnapshotGuard.isLegacyNfcOnlyHiveSnapshot(42L, 2_000L, mutations))
    }

    @Test
    fun doesNotFlagRealProfileWriteWithDifferentTimestamp() {
        val mutations = listOf(NfcMutationSyncTimestamp(hiveId = 42L, changedAt = 1_000L))
        assertFalse(LegacyNfcProfileSnapshotGuard.isLegacyNfcOnlyHiveSnapshot(42L, 1_001L, mutations))
    }

    @Test
    fun mutationForAnotherHiveDoesNotFlagSnapshot() {
        val profiles = listOf(HiveProfileSyncTimestamp(hiveId = 42L, updatedAt = 1_000L))
        val mutations = listOf(NfcMutationSyncTimestamp(hiveId = 99L, changedAt = 1_000L))
        assertEquals(emptySet<Long>(), LegacyNfcProfileSnapshotGuard.legacyHiveIds(profiles, mutations))
    }

    @Test
    fun identifiesOnlySnapshotsThatMatchTheirHiveMutationTimestamp() {
        val profiles = listOf(
            HiveProfileSyncTimestamp(hiveId = 42L, updatedAt = 1_000L),
            HiveProfileSyncTimestamp(hiveId = 43L, updatedAt = 1_001L),
            HiveProfileSyncTimestamp(hiveId = 44L, updatedAt = 1_000L)
        )
        val mutations = listOf(
            NfcMutationSyncTimestamp(hiveId = 42L, changedAt = 1_000L),
            NfcMutationSyncTimestamp(hiveId = 43L, changedAt = 900L)
        )
        assertEquals(setOf(42L), LegacyNfcProfileSnapshotGuard.legacyHiveIds(profiles, mutations))
    }
}
