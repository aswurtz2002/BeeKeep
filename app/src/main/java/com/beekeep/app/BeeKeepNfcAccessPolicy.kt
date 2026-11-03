package com.beekeep.app

import java.util.Locale

internal data class BeeKeepNfcAccess(
    val canScan: Boolean,
    val canManageTags: Boolean
)

/**
 * Resolves NFC access from the selected data scope, not blindly from the signed-in
 * user's personal plan. Viewers can scan/verify, but only writable workspace roles
 * can assign, replace or remove shared NFC tags.
 */
internal object BeeKeepNfcAccessPolicy {
    private val workspaceMemberRoles = setOf("owner", "admin", "member", "viewer")
    private val workspaceWritableRoles = setOf("owner", "admin", "member")

    fun resolve(
        isPersonalDataset: Boolean,
        personalCommercialActive: Boolean,
        workspaceOwnerCommercialActive: Boolean?,
        workspaceMemberRole: String?,
        workspaceWritable: Boolean
    ): BeeKeepNfcAccess {
        if (isPersonalDataset) {
            return BeeKeepNfcAccess(
                canScan = personalCommercialActive,
                canManageTags = personalCommercialActive
            )
        }

        val role = workspaceMemberRole?.trim()?.lowercase(Locale.ROOT)
        val canScan = workspaceOwnerCommercialActive == true && role in workspaceMemberRoles
        val canManage = canScan && workspaceWritable && role in workspaceWritableRoles
        return BeeKeepNfcAccess(canScan = canScan, canManageTags = canManage)
    }
}
