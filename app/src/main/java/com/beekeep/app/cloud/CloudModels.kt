package com.beekeep.app.cloud

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

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
    // Supplied only by the incremental-sync RPC. The default keeps it out of ordinary upserts.
    val sync_version: Long = 0L
)

data class CloudAccountState(
    val configured: Boolean,
    val signedIn: Boolean,
    val email: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null
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
