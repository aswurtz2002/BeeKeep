package com.beekeep.app.data

/**
 * Personal preferences belong to the signed-in account, while team preferences belong
 * to the selected shared workspace. The "local" key is used only before sign-in.
 */
fun workspaceActionSettingsScopeKey(workspaceId: String?, ownerUserId: String?): String {
    val workspace = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
    if (workspace != null) return "workspace:$workspace"

    val owner = ownerUserId?.trim()?.takeIf { it.isNotEmpty() } ?: "local"
    return "personal:$owner"
}

/**
 * The UI uses the verified workspace role for affordances; Supabase RLS independently
 * enforces the same rule for cloud writes.
 */
internal fun canEditHarvestQuantitySetting(
    isPersonalScope: Boolean,
    role: String?,
    roleVerified: Boolean,
    workspaceReadOnly: Boolean,
    workspaceOwnerCommercialVerified: Boolean = true
): Boolean = if (isPersonalScope) {
    true
} else {
    roleVerified &&
        !workspaceReadOnly &&
        role in setOf("owner", "admin") &&
        workspaceOwnerCommercialVerified
}
