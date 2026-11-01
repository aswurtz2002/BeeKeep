package com.beekeep.app.data

import com.beekeep.app.nfc.NfcUid

/**
 * Deterministic merge for NFC tag assignments.
 *
 * A physical tag can only serve one colony at a time, and a colony displays a
 * single current tag. When local and remote devices create competing
 * assignments while offline, the assignment with the larger (assigned_at, id)
 * wins on every device, so all replicas converge to the same state.
 */
object NfcAssignmentResolver {

    /**
     * Build the effective current tag owners from the assignment ledger and the
     * legacy hive cache. Once a hive or UID has appeared in the ledger, the ledger
     * is authoritative: an old hives.tag_uid value must not resurrect a released
     * tag or make a newly scanned tag look assigned to its former hive.
     *
     * The cache is only a fallback for truly legacy hives and UIDs with no ledger
     * history. This keeps old installations readable without letting stale cache
     * values override explicit assignment/release records.
     */
    fun currentOwners(
        assignmentHistory: List<NfcTagAssignmentEntity>,
        hiveCache: List<HiveEntity>
    ): List<Pair<String?, Long>> {
        val ledgerHiveIds = assignmentHistory.map { it.hiveId }.toSet()
        val ledgerUids = assignmentHistory.mapNotNull { NfcUid.normalize(it.tagUid) }.toSet()
        val activeLedgerOwners = assignmentHistory
            .filter { it.isActive }
            .map { it.tagUid to it.hiveId }
        val legacyCacheOwners = hiveCache.mapNotNull { hive ->
            if (hive.deleted || hive.id in ledgerHiveIds) return@mapNotNull null
            val uid = NfcUid.normalize(hive.tagUid) ?: return@mapNotNull null
            if (uid in ledgerUids) return@mapNotNull null
            uid to hive.id
        }
        return activeLedgerOwners + legacyCacheOwners
    }

    data class MergeResult(
        val incoming: NfcTagAssignmentEntity,
        val toClose: List<NfcTagAssignmentEntity>
    )

    private fun winsOver(candidate: NfcTagAssignmentEntity, incumbent: NfcTagAssignmentEntity): Boolean =
        candidate.assignedAt > incumbent.assignedAt ||
            (candidate.assignedAt == incumbent.assignedAt && candidate.id > incumbent.id)

    /**
     * Merge an incoming assignment (from a remote sync document) against the
     * locally active assignments for the same tag and for the same hive.
     *
     * Returns the incoming row to persist (possibly already closed when it loses)
     * plus any local rows that must be closed.
     */
    fun merge(
        tagConflict: NfcTagAssignmentEntity?,
        hiveConflict: NfcTagAssignmentEntity?,
        incoming: NfcTagAssignmentEntity
    ): MergeResult = merge(
        tagConflicts = listOfNotNull(tagConflict),
        hiveConflicts = listOfNotNull(hiveConflict),
        incoming = incoming
    )

    /**
     * Collection overload used by sync, because legacy/imported data can contain
     * multiple active rows for a single tag or hive. Resolve every conflict as one
     * set; never leave an extra active owner behind because a query selected LIMIT 1.
     */
    fun merge(
        tagConflicts: List<NfcTagAssignmentEntity>,
        hiveConflicts: List<NfcTagAssignmentEntity>,
        incoming: NfcTagAssignmentEntity,
        existingSameId: NfcTagAssignmentEntity? = null
    ): MergeResult {
        // Assignment IDs are immutable identities. In particular, an active copy arriving
        // from another device must never reopen a row that this device has already closed.
        val storedSameId = existingSameId
            ?: (tagConflicts + hiveConflicts).firstOrNull { it.id == incoming.id }
        val canonicalIncoming = storedSameId?.let { mergeSameId(it, incoming) } ?: incoming
        if (!canonicalIncoming.isActive) return MergeResult(canonicalIncoming, emptyList())

        val conflicts = (tagConflicts + hiveConflicts)
            .distinctBy { it.id }
            .filter { it.id != canonicalIncoming.id && it.isActive }

        val losingConflict = conflicts
            .filterNot { winsOver(canonicalIncoming, it) }
            .maxWithOrNull(compareBy<NfcTagAssignmentEntity> { it.assignedAt }.thenBy { it.id })

        if (losingConflict != null) {
            return MergeResult(
                canonicalIncoming.copy(unassignedAt = maxOf(canonicalIncoming.assignedAt, losingConflict.assignedAt)),
                emptyList()
            )
        }

        return MergeResult(
            canonicalIncoming,
            conflicts.map { it.copy(unassignedAt = maxOf(it.assignedAt, canonicalIncoming.assignedAt)) }
        )
    }

    /**
     * Merge two payloads for the same immutable assignment ID.
     *
     * A release is monotonic: once either replica has closed an assignment, an older
     * active copy cannot reopen it. If the same ID arrives with a different UID, hive,
     * or assignment time, treat it as an ID collision/corrupt payload and close the
     * stored identity rather than routing the tag to an ambiguous hive.
     */
    private fun mergeSameId(
        stored: NfcTagAssignmentEntity,
        incoming: NfcTagAssignmentEntity
    ): NfcTagAssignmentEntity {
        val sameIdentity =
            NfcUid.normalize(stored.tagUid) == NfcUid.normalize(incoming.tagUid) &&
                stored.hiveId == incoming.hiveId &&
                stored.assignedAt == incoming.assignedAt

        val storedClosedAt = stored.unassignedAt
        val incomingClosedAt = incoming.unassignedAt
        if (!sameIdentity) {
            val closedAt = maxOf(
                stored.assignedAt,
                incoming.assignedAt,
                storedClosedAt ?: 0L,
                incomingClosedAt ?: 0L
            )
            return stored.copy(unassignedAt = closedAt)
        }

        val isClosed = storedClosedAt != null || incomingClosedAt != null
        val mergedClosedAt = if (isClosed) {
            maxOf(
                stored.assignedAt,
                storedClosedAt ?: 0L,
                incomingClosedAt ?: 0L
            )
        } else {
            null
        }
        return incoming.copy(unassignedAt = mergedClosedAt)
    }
}
