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
    val device_id: String = "android"
)

data class CloudAccountState(
    val configured: Boolean,
    val signedIn: Boolean,
    val email: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null
)
