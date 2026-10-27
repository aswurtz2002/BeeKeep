package com.beekeep.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from the server-authorized support-admin Edge Function.
 * Account fields are returned only after the backend verifies the trusted admin flag.
 */
@Serializable
data class SupportAdminHiveLimitResponse(
    val success: Boolean = false,
    val allowed: Boolean = false,
    val error: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("account_email") val accountEmail: String? = null,
    @SerialName("plan_code") val planCode: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("is_active_paid_plan") val isActivePaidPlan: Boolean = false,
    @SerialName("is_active_ultimate") val isActiveUltimate: Boolean = false,
    @SerialName("base_hive_limit") val baseHiveLimit: Int = 150,
    @SerialName("custom_hive_limit") val customHiveLimit: Int? = null,
    @SerialName("override_reason") val overrideReason: String? = null,
    @SerialName("override_updated_at") val overrideUpdatedAt: String? = null,
    @SerialName("override_updated_by") val overrideUpdatedBy: String? = null,
    @SerialName("audit_history") val auditHistory: List<SupportAdminHiveLimitAuditEntry> = emptyList()
)

@Serializable
data class SupportAdminHiveLimitAuditEntry(
    val id: Long = 0,
    val action: String = "",
    @SerialName("old_hive_limit") val oldHiveLimit: Int? = null,
    @SerialName("new_hive_limit") val newHiveLimit: Int? = null,
    val reason: String = "",
    @SerialName("actor_user_id") val actorUserId: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)
