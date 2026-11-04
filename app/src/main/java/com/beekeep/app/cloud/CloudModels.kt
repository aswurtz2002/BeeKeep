package com.beekeep.app.cloud

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class CloudDocumentRow(
    val user_id: String,
    val entity_type: String,
    val entity_id: Long,
    val payload: String,
    val updated_at: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deleted: Boolean = false,
    val device_id: String = "android",
    // Supplied only by the incremental-sync RPC. The default keeps it out of ordinary upserts.
    val sync_version: Long = 0L
)

@Serializable
data class CloudWorkspaceDocumentRow(
    val workspace_id: String,
    val entity_type: String,
    val entity_id: Long,
    val payload: String,
    val updated_at: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deleted: Boolean = false,
    val device_id: String = "android",
    val updated_by: String?,
    // Supplied by automatic Workspace Actions logging and ignored by older sync semantics.
    val client_action_id: String? = null,
    val action_at: Long? = null,
    // Supplied only by the incremental-sync RPC.
    val sync_version: Long = 0L
)

/**
 * The cloud row is the tie-break authority for equal client timestamps. Server-side guards
 * reject older edits; explicit permanent deletions are advanced beyond the current row.
 * Clients apply equal-time cloud rows so replicas converge on the version committed last.
 */
internal object CloudConflictPolicy {
    fun shouldUploadLocal(
        localUpdatedAt: Long,
        remoteUpdatedAt: Long?,
        remoteDeleted: Boolean = false,
        localDeleted: Boolean = false,
        allowDraftRecreation: Boolean = false
    ): Boolean {
        if (remoteUpdatedAt == null) return true
        if (remoteDeleted) {
            // Only a newer active draft may reuse the intentionally hive-scoped draft ID.
            return !localDeleted && allowDraftRecreation && localUpdatedAt > remoteUpdatedAt
        }
        // Permanent entity deletes are explicit actions. Reusable draft clears retain
        // timestamp conflict checks so an old clear cannot erase a newer draft.
        if (localDeleted && !allowDraftRecreation) return true
        return localUpdatedAt > remoteUpdatedAt
    }

    fun effectiveUploadTimestamp(
        localUpdatedAt: Long,
        remoteUpdatedAt: Long?,
        localDeleted: Boolean,
        allowDraftRecreation: Boolean = false
    ): Long = if (localDeleted && !allowDraftRecreation && remoteUpdatedAt != null) {
        maxOf(localUpdatedAt, remoteUpdatedAt + 1L)
    } else {
        localUpdatedAt
    }

    fun shouldApplyRemote(incomingUpdatedAt: Long, localUpdatedAt: Long): Boolean =
        incomingUpdatedAt >= localUpdatedAt
}

data class CloudAccountState(
    val configured: Boolean,
    val signedIn: Boolean,
    val email: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    val userId: String? = null
)


@Serializable
data class CloudSyncHead(
    val head_version: Long
)

@Serializable
data class PersonalSyncChangesParams(
    val after_version: Long,
    val through_version: Long,
    val requested_page_size: Int
)

@Serializable
data class WorkspaceSyncChangesParams(
    val target_workspace_id: String,
    val after_version: Long,
    val through_version: Long,
    val requested_page_size: Int
)

@Serializable
data class WorkspaceSyncHeadParams(
    val target_workspace_id: String
)


@Serializable
data class ApplyActionBatchParams(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val target_workspace_id: String?,
    val target_apiary_id: Long,
    val target_apiary_name: String,
    val target_batch_id: String,
    val target_action_type: String,
    val target_documents: JsonArray
)

@Serializable
data class ApplyActionBatchResult(
    val saved_records: Int,
    val already_applied: Boolean
)

@Serializable
data class ReplayWorkspaceActionChangesParams(
    val target_workspace_id: String,
    val target_actions: JsonArray
)

@Serializable
data class ReplayWorkspaceActionChangesResult(
    val processed_actions: Int,
    val applied_actions: Int
)
