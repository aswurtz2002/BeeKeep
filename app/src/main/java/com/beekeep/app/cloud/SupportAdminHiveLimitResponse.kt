package com.beekeep.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from the server-authorized support-admin Edge Function.
 * All account fields are returned only after the backend verifies the trusted admin flag.
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
    @SerialName("is_active_ultimate") val isActiveUltimate: Boolean = false,
    @SerialName("custom_hive_limit") val customHiveLimit: Int? = null,
    @SerialName("override_reason") val overrideReason: String? = null,
    @SerialName("override_updated_at") val overrideUpdatedAt: String? = null
)
