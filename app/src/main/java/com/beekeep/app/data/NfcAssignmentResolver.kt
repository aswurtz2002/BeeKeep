package com.beekeep.app.data

/**
 * Deterministic merge for NFC tag assignments.
 *
 * A physical tag can only serve one colony at a time, and a colony displays a
 * single current tag. When local and remote devices create competing
 * assignments while offline, the assignment with the larger (assigned_at, id)
 * wins on every device, so all replicas converge to the same state.
 */
object NfcAssignmentResolver {

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
    ): MergeResult {
        if (!incoming.isActive) return MergeResult(incoming, emptyList())

        val conflicts = listOfNotNull(tagConflict, hiveConflict)
            .distinctBy { it.id }
            .filter { it.id != incoming.id && it.isActive }

        val toClose = mutableListOf<NfcTagAssignmentEntity>()
        var resolved = incoming
        for (local in conflicts) {
            if (!resolved.isActive) break
            if (winsOver(resolved, local)) {
                toClose += local.copy(unassignedAt = resolved.assignedAt)
            } else {
                resolved = resolved.copy(unassignedAt = local.assignedAt)
            }
        }
        return MergeResult(resolved, toClose)
    }
}
