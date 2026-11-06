package com.beekeep.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Manual Commercial grant details returned only after the protected support-admin check.
 * A grant is distinct from the account's billing subscription metadata.
 */
@Serializable
data class SupportAdminCommercialAccessResponse(
    val success: Boolean = false,
    val allowed: Boolean = false,
    val error: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("account_email") val accountEmail: String? = null,
    @SerialName("plan_code") val planCode: String? = null,
    @SerialName("is_active_commercial") val isActiveCommercial: Boolean = false,
    @SerialName("has_admin_grant") val hasAdminGrant: Boolean = false,
    @SerialName("admin_grant_active") val adminGrantActive: Boolean = false,
    @SerialName("admin_grant_expires_at") val adminGrantExpiresAt: String? = null,
    @SerialName("admin_grant_reason") val adminGrantReason: String? = null,
    @SerialName("admin_grant_updated_at") val adminGrantUpdatedAt: String? = null,
    @SerialName("admin_granted_by") val adminGrantedBy: String? = null,
    @SerialName("audit_history") val auditHistory: List<SupportAdminCommercialGrantAuditEntry> = emptyList()
)

@Serializable
data class SupportAdminCommercialGrantAuditEntry(
    val id: Long = 0,
    val action: String = "",
    @SerialName("old_expires_at") val oldExpiresAt: String? = null,
    @SerialName("new_expires_at") val newExpiresAt: String? = null,
    val reason: String = "",
    @SerialName("actor_user_id") val actorUserId: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)
