package com.beekeep.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-trusted entitlement for the currently signed-in user.
 * Values are derived from auth.users.raw_app_meta_data, never editable profile metadata.
 */
@Serializable
data class BeeKeepServerEntitlement(
    @SerialName("plan_code") val planCode: String = "free",
    @SerialName("base_hive_limit") val baseHiveLimit: Int = 150,
    @SerialName("custom_hive_limit") val customHiveLimit: Int? = null,
    @SerialName("effective_hive_limit") val effectiveHiveLimit: Int = 150,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("is_active_paid_plan") val isActivePaidPlan: Boolean = false,
    @SerialName("is_active_ultimate") val isActiveUltimate: Boolean = false
)
