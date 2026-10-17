package com.beekeep.app.cloud

import kotlinx.serialization.Serializable

@Serializable
data class CloudDocumentRow(
    val user_id: String,
    val entity_type: String,
    val entity_id: Long,
    val payload: String,
    val updated_at: Long,
    val deleted: Boolean = false,
    val device_id: String = "android",
    // Stage 1 adds workspace scope while preserving private per-user sync behavior.
    // The database trigger fills this for older clients that do not supply it.
    val workspace_id: String? = null
)

data class CloudAccountState(
    val configured: Boolean,
    val signedIn: Boolean,
    val email: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null
)
