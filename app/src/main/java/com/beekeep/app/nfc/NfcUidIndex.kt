package com.beekeep.app.nfc

/**
 * Snapshot of the local tag-to-hive cache. Duplicate physical UIDs are deliberately
 * excluded from the usable map so callers cannot silently choose one of two colonies.
 */
data class NfcUidIndex(
    val uniqueHiveIdByUid: Map<String, Long>,
    val ambiguousUids: Set<String>
) {
    companion object {
        fun fromAssignments(assignments: Iterable<Pair<String?, Long>>): NfcUidIndex {
            val grouped = assignments.mapNotNull { (rawUid, hiveId) ->
                NfcUid.normalize(rawUid)?.let { it to hiveId }
            }.groupBy({ it.first }, { it.second })

            val ambiguous = grouped
                .filterValues { hiveIds -> hiveIds.distinct().size > 1 }
                .keys
            val unique = grouped.mapNotNull { (uid, hiveIds) ->
                hiveIds.distinct().singleOrNull()?.let { uid to it }
            }.filterNot { it.first in ambiguous }.toMap()

            return NfcUidIndex(unique, ambiguous)
        }
    }
}
