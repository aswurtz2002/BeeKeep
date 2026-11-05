package com.beekeep.app.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal enum class LocalDataAttachmentDisposition {
    UPLOAD_NEW,
    SKIP_DUPLICATE,
    REMAP_ID,
    REVIEW_REQUIRED,
    PRESERVE_LOCAL_ONLY
}

internal data class LocalDataAttachmentMergeItem(
    val entityType: String,
    val sourceId: Long,
    val targetId: Long,
    val disposition: LocalDataAttachmentDisposition,
    val reason: String,
    val targetHiveId: Long? = null,
    val targetApiaryId: Long? = null
)

internal data class LocalDataAttachmentMergePlan(
    val accountId: String,
    val items: List<LocalDataAttachmentMergeItem>,
    val taskCount: Int,
    val noteDraftCount: Int,
    val formDraftCount: Int,
    val pendingSyncWriteCount: Int,
    val pendingDeleteCount: Int,
    val pendingActionBatchCount: Int,
    val pendingWorkspaceActionCount: Int,
    val cloudInventorySha256: String = ""
) {
    fun count(disposition: LocalDataAttachmentDisposition): Int =
        items.count { it.disposition == disposition }

    val reviewRequiredCount: Int get() = count(LocalDataAttachmentDisposition.REVIEW_REQUIRED)
    val duplicateCount: Int get() = count(LocalDataAttachmentDisposition.SKIP_DUPLICATE)
    val remapCount: Int get() = count(LocalDataAttachmentDisposition.REMAP_ID)
    val newRecordCount: Int get() = count(LocalDataAttachmentDisposition.UPLOAD_NEW)
    val localOnlyCount: Int get() = count(LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY)
    val isSafeToProceed: Boolean get() = reviewRequiredCount == 0
}

/**
 * Plans a local-to-account merge without changing either side.
 *
 * Exact stable identities are deduplicated. Numeric ID collisions with different records are
 * remapped deterministically. Weak or conflicting identities are surfaced for human review;
 * tombstones are never silently resurrected. The resulting plan is suitable for display and
 * later revalidation, but is not itself an instruction to upload.
 */
internal object LocalDataAttachmentMergePlanner {
    private val supportedTypes = setOf(
        "apiary", "hive", "nfc_assignment", "inspection",
        "feeding", "treatment", "harvest", "event"
    )
    private val childTypes = setOf(
        "nfc_assignment", "inspection", "feeding", "treatment", "harvest", "event"
    )

    fun plan(
        snapshotJson: String,
        cloudDocuments: Collection<CloudDocumentRow>
    ): LocalDataAttachmentMergePlan {
        val root = JSONObject(snapshotJson)
        require(root.optString("format") == "beekeep-local-attachment-snapshot") {
            "The selected recovery file is not a BeeKeep attachment snapshot."
        }
        require(root.optInt("schemaVersion", -1) == 1) {
            "This BeeKeep attachment snapshot version is not supported."
        }
        val accountId = root.optString("targetAccountId").trim()
        require(accountId.isNotEmpty()) { "The recovery snapshot is not linked to a signed-in account." }

        val records = root.optJSONArray("records") ?: JSONArray()
        val localRows = (0 until records.length()).map { index ->
            val row = records.getJSONObject(index)
            val type = row.optString("type")
            require(type in supportedTypes) { "The recovery snapshot contains an unsupported record type: $type." }
            val sourceId = row.optLong("id", -1L)
            require(sourceId > 0L) { "A local $type record has an invalid ID." }
            val payload = row.optJSONObject("payload")
                ?: throw IllegalArgumentException("A local $type record has no payload.")
            require(payload.optLong("id", sourceId) == sourceId) {
                "A local $type record has mismatched IDs."
            }
            row to LocalRow(type, sourceId, JSONObject(payload.toString()))
        }

        val duplicateLocalKeys = localRows.groupingBy { "${it.second.type}:${it.second.sourceId}" }
            .eachCount().filterValues { it > 1 }
        require(duplicateLocalKeys.isEmpty()) {
            "The recovery snapshot contains duplicate local record IDs."
        }

        val cloudRows = cloudDocuments.map { row ->
            CloudRow(
                type = row.entity_type,
                id = row.entity_id,
                payload = runCatching { JSONObject(row.payload) }.getOrElse {
                    throw IllegalArgumentException("Cloud ${row.entity_type} record ${row.entity_id} has invalid data.")
                },
                deleted = row.deleted
            )
        }
        val cloudByType = cloudRows.groupBy { it.type }
        val cloudIds = cloudRows.groupBy { it.type }.mapValues { (_, rows) -> rows.map { it.id }.toMutableSet() }
        val localIds = localRows.groupBy { it.second.type }.mapValues { (_, rows) -> rows.map { it.second.sourceId }.toSet() }
        val reservedIds = mutableMapOf<String, MutableSet<Long>>()
        supportedTypes.forEach { type ->
            reservedIds[type] = (cloudIds[type].orEmpty() + localIds[type].orEmpty()).toMutableSet()
        }

        val items = mutableListOf<LocalDataAttachmentMergeItem>()
        val apiaryIdMap = mutableMapOf<Long, Long>()
        val hiveIdMap = mutableMapOf<Long, Long>()
        val hiveApiaryIdMap = mutableMapOf<Long, Long?>()
        val localRowsByType = localRows.map { it.second }.groupBy { it.type }

        fun allocateId(type: String, sourceId: Long): Long {
            val used = reservedIds.getOrPut(type) { mutableSetOf() }
            var attempt = 0
            while (attempt < 10_000) {
                val candidate = deterministicId(accountId, type, sourceId, attempt)
                if (candidate !in used && candidate > 0L) {
                    used += candidate
                    return candidate
                }
                attempt++
            }
            throw IllegalStateException("BeeKeep could not allocate a safe ID for a local $type record.")
        }

        fun dispositionForCollision(
            local: JSONObject,
            remote: CloudRow,
            excludedKeys: Set<String>
        ): Pair<LocalDataAttachmentDisposition, String> {
            if (remote.deleted) {
                return LocalDataAttachmentDisposition.REVIEW_REQUIRED to
                    "The cloud account has a deletion record with this ID. The local record will not resurrect it automatically."
            }
            return if (canonical(local, excludedKeys) == canonical(remote.payload, excludedKeys)) {
                LocalDataAttachmentDisposition.SKIP_DUPLICATE to
                    "The same record already exists in the cloud account."
            } else {
                LocalDataAttachmentDisposition.REMAP_ID to
                    "This ID belongs to a different cloud record. A new ID is reserved to prevent overwriting it."
            }
        }

        // Parent records are planned first so every child reference can use the final mapped IDs.
        val deletedLocalApiaryIds = localRowsByType["apiary"].orEmpty()
            .filter { it.payload.optBoolean("deleted", false) }
            .map { it.sourceId }
            .toSet()
        val apiariesRequiringReview = mutableSetOf<Long>()
        val localOnlyHiveIds = mutableSetOf<Long>()
        val hivesRequiringReview = mutableSetOf<Long>()

        localRowsByType["apiary"].orEmpty().sortedBy { it.sourceId }.forEach { local ->
            val payload = local.payload
            val name = normalized(payload.optString("name"))
            require(name.isNotEmpty()) { "A local apiary is missing its name." }
            val remoteSameNameCandidates = cloudByType["apiary"].orEmpty().filter {
                normalized(it.payload.optString("name")) == name
            }
            val remoteSameName = remoteSameNameCandidates.singleOrNull()
            val remoteById = cloudByType["apiary"].orEmpty().firstOrNull { it.id == local.sourceId }

            val localDeleted = payload.optBoolean("deleted", false)
            val item = when {
                remoteSameNameCandidates.size > 1 ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "Multiple cloud apiaries have the same name. Review them before attaching this local apiary."
                    )
                localDeleted && remoteSameName?.deleted == true ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, remoteSameName.id,
                        LocalDataAttachmentDisposition.SKIP_DUPLICATE,
                        "The same apiary deletion already exists in the cloud account."
                    )
                localDeleted && remoteSameName != null ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "The local apiary is deleted, but an active cloud apiary with the same name exists. The deletion will not be applied automatically."
                    )
                localDeleted -> {
                    // A local-only deleted record must not reuse an ID now owned by a different
                    // cloud row; the local copy is retained under a deterministic free ID.
                    val target = if (remoteById != null) allocateId("apiary", local.sourceId) else local.sourceId
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, target,
                        LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
                        "This apiary is already deleted locally. Its deletion marker is retained locally and is never uploaded."
                    )
                }
                remoteSameName?.deleted == true ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "A cloud deletion record exists for an apiary with this name."
                    )
                remoteSameName != null && remoteSameName.id != local.sourceId &&
                    remoteById != null && remoteById.id != remoteSameName.id ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "The apiary matches an existing cloud apiary by name, but its local ID is occupied by another cloud record."
                    )
                remoteSameName != null && canonical(payload, setOf("id", "updated_at", "deleted")) ==
                    canonical(remoteSameName.payload, setOf("id", "updated_at", "deleted")) ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, remoteSameName.id,
                        LocalDataAttachmentDisposition.SKIP_DUPLICATE,
                        "The same apiary already exists in the cloud account."
                    )
                remoteSameName != null ->
                    LocalDataAttachmentMergeItem(
                        "apiary", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "An apiary with the same name exists in the cloud but its details differ. Review before combining them."
                    )
                remoteById != null -> {
                    val (disposition, reason) = dispositionForCollision(
                        payload, remoteById, setOf("id", "updated_at", "deleted")
                    )
                    val target = if (disposition == LocalDataAttachmentDisposition.REMAP_ID) {
                        allocateId("apiary", local.sourceId)
                    } else local.sourceId
                    LocalDataAttachmentMergeItem("apiary", local.sourceId, target, disposition, reason)
                }
                else -> LocalDataAttachmentMergeItem(
                    "apiary", local.sourceId, local.sourceId,
                    LocalDataAttachmentDisposition.UPLOAD_NEW,
                    "No apiary with this ID or name exists in the cloud account."
                )
            }
            apiaryIdMap[local.sourceId] = item.targetId
            if (item.disposition == LocalDataAttachmentDisposition.REVIEW_REQUIRED) {
                apiariesRequiringReview += local.sourceId
            }
            items += item
        }

        localRowsByType["hive"].orEmpty().sortedBy { it.sourceId }.forEach { local ->
            val payload = JSONObject(local.payload.toString())
            val apiaryName = normalized(payload.optString("apiary"))
            val sourceApiaryId = payload.optLong("apiary_id", -1L).takeIf { it > 0L }
                ?: localRowsByType["apiary"].orEmpty().firstOrNull {
                    normalized(it.payload.optString("name")) == apiaryName
                }?.sourceId
            val targetApiaryId = sourceApiaryId?.let { apiaryIdMap[it] ?: it }
            if (targetApiaryId != null) payload.put("apiary_id", targetApiaryId)
            val number = normalized(payload.optString("number"))
            require(number.isNotEmpty()) { "A local hive is missing its hive number." }
            val remoteHives = cloudByType["hive"].orEmpty()
            val remoteLogicalCandidates = remoteHives.filter { remote ->
                normalized(remote.payload.optString("number")) == number &&
                    when {
                        targetApiaryId != null -> remote.payload.optLong("apiary_id", -1L) == targetApiaryId ||
                            normalized(remote.payload.optString("apiary")) == apiaryName
                        else -> normalized(remote.payload.optString("apiary")) == apiaryName
                    }
            }
            val remoteLogical = remoteLogicalCandidates.singleOrNull()
            val remoteById = remoteHives.firstOrNull { it.id == local.sourceId }
            val localDeleted = local.payload.optBoolean("deleted", false)
            val item = when {
                remoteLogicalCandidates.size > 1 ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "Multiple cloud hives match this apiary and hive number. Review them before attaching this local hive.",
                        targetApiaryId = targetApiaryId
                    )
                sourceApiaryId != null && sourceApiaryId in deletedLocalApiaryIds ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
                        "Its parent apiary is deleted locally. Keep this hive with the recovery snapshot until the parent relationship is reviewed.",
                        targetApiaryId = targetApiaryId
                    )
                sourceApiaryId != null && sourceApiaryId in apiariesRequiringReview ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "The parent apiary has an unresolved cloud identity conflict.",
                        targetApiaryId = targetApiaryId
                    )
                localDeleted && remoteLogical?.deleted == true ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, remoteLogical.id,
                        LocalDataAttachmentDisposition.SKIP_DUPLICATE,
                        "The same hive deletion already exists in the cloud account.",
                        targetApiaryId = targetApiaryId
                    )
                localDeleted && remoteLogical != null ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "The local hive is deleted, but an active cloud hive with the same apiary and number exists. The deletion will not be applied automatically.",
                        targetApiaryId = targetApiaryId
                    )
                localDeleted -> {
                    // Keep the locally deleted hive separate if its old numeric ID has been
                    // taken by a different cloud hive.
                    val target = if (remoteById != null) allocateId("hive", local.sourceId) else local.sourceId
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, target,
                        LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
                        "This hive is already deleted locally. Its deletion marker is retained locally and is never uploaded.",
                        targetApiaryId = targetApiaryId
                    )
                }
                remoteLogical?.deleted == true ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "The cloud account has a deleted hive with the same apiary and hive number.",
                        targetApiaryId = targetApiaryId
                    )
                remoteLogical != null && remoteLogical.id != local.sourceId &&
                    remoteById != null && remoteById.id != remoteLogical.id ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "This hive matches an existing cloud hive, but its local ID is occupied by another cloud record.",
                        targetApiaryId = targetApiaryId
                    )
                remoteLogical != null && canonical(payload, setOf("id", "apiary_id", "updated_at", "deleted")) ==
                    canonical(remoteLogical.payload, setOf("id", "apiary_id", "updated_at", "deleted")) ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, remoteLogical.id,
                        LocalDataAttachmentDisposition.SKIP_DUPLICATE,
                        "The same hive already exists in the cloud account.",
                        targetApiaryId = targetApiaryId
                    )
                remoteLogical != null ->
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                        "A hive with the same apiary and number exists in the cloud but its details differ.",
                        targetApiaryId = targetApiaryId
                    )
                remoteById != null -> {
                    val (disposition, reason) = dispositionForCollision(
                        payload, remoteById, setOf("id", "apiary_id", "updated_at", "deleted")
                    )
                    val target = if (disposition == LocalDataAttachmentDisposition.REMAP_ID) {
                        allocateId("hive", local.sourceId)
                    } else local.sourceId
                    LocalDataAttachmentMergeItem(
                        "hive", local.sourceId, target, disposition, reason,
                        targetApiaryId = targetApiaryId
                    )
                }
                else -> LocalDataAttachmentMergeItem(
                    "hive", local.sourceId, local.sourceId,
                    LocalDataAttachmentDisposition.UPLOAD_NEW,
                    "No hive with this ID or apiary/number combination exists in the cloud account.",
                    targetApiaryId = targetApiaryId
                )
            }
            hiveIdMap[local.sourceId] = item.targetId
            hiveApiaryIdMap[local.sourceId] = item.targetApiaryId
            if (item.disposition == LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY) {
                localOnlyHiveIds += local.sourceId
            }
            if (item.disposition == LocalDataAttachmentDisposition.REVIEW_REQUIRED) {
                hivesRequiringReview += local.sourceId
            }
            items += item
        }

        val activeCloudTagOwners = cloudByType["nfc_assignment"].orEmpty()
            .filter { !it.deleted && it.payload.isNull("unassigned_at") }
            .mapNotNull { row ->
                normalized(row.payload.optString("tag_uid")).takeIf { it.isNotEmpty() }?.let { it to row }
            }.groupBy({ it.first }, { it.second })

        localRows.map { it.second }
            .filter { it.type in childTypes }
            .sortedWith(compareBy<LocalRow> { typeOrder(it.type) }.thenBy { it.sourceId })
            .forEach { local ->
                val payload = JSONObject(local.payload.toString())
                val sourceHiveId = payload.optLong("hive_id", -1L)
                val targetHiveId = hiveIdMap[sourceHiveId] ?: sourceHiveId.takeIf { it > 0L }
                    ?: throw IllegalArgumentException("A local ${local.type} record refers to an invalid hive.")
                payload.put("hive_id", targetHiveId)
                val sourceApiaryId = payload.optLong("apiary_id", -1L).takeIf { it > 0L }
                    ?: hiveApiaryIdMap[sourceHiveId]
                val targetApiaryId = sourceApiaryId?.let { apiaryIdMap[it] ?: it }
                if (targetApiaryId != null) payload.put("apiary_id", targetApiaryId)

                val remote = cloudByType[local.type].orEmpty()
                val remoteById = remote.firstOrNull { it.id == local.sourceId }
                val remoteLogicalCandidates = findLogicalChild(local.type, payload, targetHiveId, remote)
                val remoteLogical = remoteLogicalCandidates.singleOrNull()
                val item = when {
                    sourceHiveId in hivesRequiringReview ->
                        collisionReview(local, "The parent hive has an unresolved cloud identity conflict.", targetHiveId, targetApiaryId)
                    sourceHiveId in localOnlyHiveIds -> {
                        // History attached to a local-only hive is local-only too. Do not let
                        // an ID collision overwrite a cloud history row during Room rebuild.
                        val target = if (remoteById != null) allocateId(local.type, local.sourceId) else local.sourceId
                        LocalDataAttachmentMergeItem(
                            local.type, local.sourceId, target,
                            LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
                            "The parent hive is being retained locally. Its history remains on this device and will not be uploaded.",
                            targetHiveId, targetApiaryId
                        )
                    }
                    remoteLogicalCandidates.size > 1 ->
                        collisionReview(local, "More than one cloud history entry matches this local identity. Review the cloud records before attaching it.", targetHiveId, targetApiaryId)
                    local.type == "nfc_assignment" &&
                        activeTagConflict(payload, targetHiveId, activeCloudTagOwners) ->
                        collisionReview(local, "This NFC tag is currently assigned to a different cloud hive. Review the tag history before attaching it.", targetHiveId, targetApiaryId)
                    remoteLogical?.deleted == true ->
                        collisionReview(local, "A cloud deletion record matches this local history entry. It will not be restored automatically.", targetHiveId, targetApiaryId)
                    remoteLogical != null &&
                        canonical(payload, setOf("id", "updated_at")) ==
                            canonical(remoteLogical.payload, setOf("id", "updated_at")) ->
                        LocalDataAttachmentMergeItem(
                            local.type, local.sourceId, remoteLogical.id,
                            LocalDataAttachmentDisposition.SKIP_DUPLICATE,
                            "The same history entry already exists in the cloud account.",
                            targetHiveId, targetApiaryId
                        )
                    remoteLogical != null ->
                        collisionReview(
                            local,
                            "A cloud history entry has the same identity but different content. Review instead of overwriting or silently dropping either copy.",
                            targetHiveId,
                            targetApiaryId
                        )
                    remoteById?.deleted == true ->
                        collisionReview(local, "The cloud has a deletion record with this ID. The local entry will not resurrect it automatically.", targetHiveId, targetApiaryId)
                    remoteById != null -> {
                        val excluded = setOf("id", "updated_at")
                        val exact = canonical(payload, excluded) == canonical(remoteById.payload, excluded)
                        if (exact) {
                            LocalDataAttachmentMergeItem(
                                local.type, local.sourceId, local.sourceId,
                                LocalDataAttachmentDisposition.SKIP_DUPLICATE,
                                "The same history entry already exists under this ID.",
                                targetHiveId, targetApiaryId
                            )
                        } else {
                            val target = allocateId(local.type, local.sourceId)
                            LocalDataAttachmentMergeItem(
                                local.type, local.sourceId, target,
                                LocalDataAttachmentDisposition.REMAP_ID,
                                "This ID belongs to a different cloud history entry. A new ID is reserved for the local copy.",
                                targetHiveId, targetApiaryId
                            )
                        }
                    }
                    else -> LocalDataAttachmentMergeItem(
                        local.type, local.sourceId, local.sourceId,
                        LocalDataAttachmentDisposition.UPLOAD_NEW,
                        "No matching cloud history entry or ID collision was found.",
                        targetHiveId, targetApiaryId
                    )
                }
                items += item
            }

        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        val taskItems = (0 until tasks.length()).map { index ->
            val task = tasks.getJSONObject(index)
            val id = task.optLong("id", -1L)
            require(id > 0L) { "A local task has an invalid ID." }
            LocalDataAttachmentMergeItem(
                "task", id, id,
                LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
                "Tasks are preserved in the recovery snapshot but are not part of the current cloud-document sync contract.",
                targetHiveId = task.optLong("hive_id", -1L).takeIf { it > 0L }?.let { hiveIdMap[it] ?: it }
            )
        }
        items += taskItems

        // Two different local rows must never converge on one target primary key. This can
        // happen with duplicated legacy hive identities that both match one cloud hive.
        // Surface the whole group for manual review rather than relying on apply order.
        val duplicateTargets = items
            .filter { it.entityType in supportedTypes }
            .groupBy { it.entityType to it.targetId }
            .values
            .filter { it.size > 1 }
        duplicateTargets.flatten().distinctBy { it.entityType to it.sourceId }.forEach { collision ->
            val index = items.indexOfFirst {
                it.entityType == collision.entityType && it.sourceId == collision.sourceId
            }
            if (index >= 0) {
                items[index] = items[index].copy(
                    disposition = LocalDataAttachmentDisposition.REVIEW_REQUIRED,
                    targetId = items[index].sourceId,
                    reason = "More than one local record resolves to the same cloud ID. Review these records before attaching."
                )
            }
        }

        val noteDraftCount = root.optJSONArray("inspectionNoteDraftPreferences")?.length() ?: 0
        val formDraftCount = root.optJSONArray("inspectionFormDraftPreferences")?.length() ?: 0
        val pendingSync = root.optJSONArray("syncOutbox") ?: JSONArray()
        val pendingActionBatches = root.optJSONArray("actionBatchOutbox") ?: JSONArray()
        val pendingWorkspaceActions = root.optJSONArray("workspaceActionOutbox") ?: JSONArray()
        val pendingSyncWriteCount = (0 until pendingSync.length()).count { index ->
            pendingSync.optJSONObject(index)?.optString("state") == "pending"
        }
        val pendingDeleteCount = (0 until pendingSync.length()).count { index ->
            val row = pendingSync.optJSONObject(index) ?: return@count false
            row.optString("state") == "pending" && row.optString("operation") == "delete"
        }
        val pendingActionBatchCount = (0 until pendingActionBatches.length()).count { index ->
            pendingActionBatches.optJSONObject(index)?.optString("state") == "pending"
        }
        val pendingWorkspaceActionCount = (0 until pendingWorkspaceActions.length()).count { index ->
            pendingWorkspaceActions.optJSONObject(index)?.optString("state") == "pending"
        }

        return LocalDataAttachmentMergePlan(
            accountId = accountId,
            items = items,
            taskCount = tasks.length(),
            noteDraftCount = noteDraftCount,
            formDraftCount = formDraftCount,
            pendingSyncWriteCount = pendingSyncWriteCount,
            pendingDeleteCount = pendingDeleteCount,
            pendingActionBatchCount = pendingActionBatchCount,
            pendingWorkspaceActionCount = pendingWorkspaceActionCount
        )
    }

    private data class LocalRow(val type: String, val sourceId: Long, val payload: JSONObject)
    private data class CloudRow(val type: String, val id: Long, val payload: JSONObject, val deleted: Boolean)

    private fun findLogicalChild(
        type: String,
        local: JSONObject,
        targetHiveId: Long,
        remoteRows: List<CloudRow>
    ): List<CloudRow> {
        return remoteRows.filter { row ->
            val payload = row.payload
            val sameHive = payload.optLong("hive_id", -1L) == targetHiveId
            when (type) {
                "feeding", "treatment", "harvest" -> {
                    // Stable UUIDs identify history globally within an account, not just under
                    // one hive. A UUID found under another hive is a conflict, not a new record.
                    val uuid = normalized(local.optString("record_uuid"))
                    if (uuid.isNotEmpty()) normalized(payload.optString("record_uuid")) == uuid
                    else sameHive && payload.optLong("created_at", -1L) == local.optLong("created_at", -2L)
                }
                "nfc_assignment" ->
                    sameHive &&
                        normalized(payload.optString("tag_uid")) == normalized(local.optString("tag_uid")) &&
                        payload.optLong("assigned_at", -1L) == local.optLong("assigned_at", -2L)
                "inspection" ->
                    sameHive && payload.optLong("created_at", -1L) == local.optLong("created_at", -2L)
                "event" ->
                    sameHive &&
                        payload.optLong("created_at", -1L) == local.optLong("created_at", -2L) &&
                        normalized(payload.optString("type")) == normalized(local.optString("type")) &&
                        normalized(payload.optString("title")) == normalized(local.optString("title"))
                else -> false
            }
        }
    }

    private fun activeTagConflict(
        local: JSONObject,
        targetHiveId: Long,
        activeOwners: Map<String, List<CloudRow>>
    ): Boolean {
        val uid = normalized(local.optString("tag_uid"))
        if (uid.isEmpty() || !local.isNull("unassigned_at")) return false
        val remoteOwners = activeOwners[uid].orEmpty()
        return remoteOwners.any { it.payload.optLong("hive_id", -1L) != targetHiveId }
    }

    private fun collisionReview(
        local: LocalRow,
        reason: String,
        targetHiveId: Long,
        targetApiaryId: Long?
    ) = LocalDataAttachmentMergeItem(
        local.type, local.sourceId, local.sourceId,
        LocalDataAttachmentDisposition.REVIEW_REQUIRED,
        reason,
        targetHiveId,
        targetApiaryId
    )

    private fun typeOrder(type: String): Int = when (type) {
        "nfc_assignment" -> 0
        "inspection" -> 1
        "feeding" -> 2
        "treatment" -> 3
        "harvest" -> 4
        "event" -> 5
        else -> 99
    }

    private fun normalized(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun canonical(payload: JSONObject, excluded: Set<String>): String =
        payload.keys().asSequence()
            .filterNot { it in excluded }
            .sorted()
            .joinToString(prefix = "{", postfix = "}") { key ->
                "$key:${canonicalValue(payload.opt(key))}"
            }

    private fun canonicalValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> canonical(value, emptySet())
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
            canonicalValue(value.opt(it))
        }
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }

    private fun deterministicId(accountId: String, type: String, sourceId: Long, attempt: Int): Long {
        val material = "$accountId|$type|$sourceId|$attempt".toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(material)
        return (ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).getLong() and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
