package com.beekeep.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BeeKeepWorkspace(
    val id: String,
    val name: String,
    val owner_user_id: String,
    val is_personal: Boolean,
    val member_role: String,
    val created_at: String = "",
    val updated_at: String = ""
)

@Serializable
data class BeeKeepWorkspaceMember(
    val workspace_id: String,
    val user_id: String,
    val email: String = "",
    val username: String = "",
    val role: String,
    val joined_at: String = "",
    val is_current_user: Boolean = false
)

@Serializable
data class BeeKeepWorkspaceInvitation(
    val id: String,
    val workspace_id: String,
    val email: String,
    val role: String,
    val status: String,
    val created_at: String = "",
    val expires_at: String = "",
    val created_by: String = ""
)

@Serializable
data class BeeKeepWorkspaceInvitationCreated(
    val invitation_id: String,
    val workspace_id: String,
    val email: String,
    val role: String,
    val invite_token: String,
    val expires_at: String
)

@Serializable
data class BeeKeepWorkspaceJoinResult(
    val workspace_id: String,
    val workspace_name: String,
    val workspace_role: String
)

@Serializable
data class WorkspacePersonalCopyResult(val copied_count: Int)

@Serializable
data class WorkspaceNameParams(val workspace_name: String)

@Serializable
data class WorkspaceIdParams(val target_workspace_id: String)

@Serializable
data class WorkspaceInviteParams(
    val target_workspace_id: String,
    val invite_email: String,
    val target_role: String
)

@Serializable
data class WorkspaceInvitationTokenParams(val invite_token: String)

@Serializable
data class WorkspaceInvitationIdParams(val target_invitation_id: String)

@Serializable
data class BeeKeepUsername(val username: String)

/**
 * Post-login profile/workspace gate. The personal workspace is verified server-side before
 * the app opens; usernames are required for every account, including Google-first accounts.
 */
sealed interface BeeKeepAccountSetupState {
    data object SignedOut : BeeKeepAccountSetupState
    data object Checking : BeeKeepAccountSetupState
    /** A previously saved session is available; cloud profile verification continues in background. */
    data object Offline : BeeKeepAccountSetupState
    data class UsernameRequired(
        val personalWorkspaceId: String,
        val personalWorkspaceName: String,
        val isCached: Boolean = false
    ) : BeeKeepAccountSetupState
    data class Ready(
        val username: String,
        val personalWorkspaceId: String,
        val personalWorkspaceName: String,
        val isCached: Boolean = false
    ) : BeeKeepAccountSetupState
    data class Unavailable(val message: String) : BeeKeepAccountSetupState
}

@Serializable
data class BeeKeepUsernameAvailability(val available: Boolean)

@Serializable
data class BeeKeepUltimateEntitlement(val active: Boolean)

@Serializable
data class BeeKeepActionSettingsRow(
    val scope_key: String,
    val owner_user_id: String? = null,
    val workspace_id: String? = null,
    val harvest_quantity_mode: String = "per_hive",
    val updated_at: Long = 0L,
    val updated_by: String? = null
)


@Serializable
data class BeeKeepWorkspaceOwnerQuotaEntitlement(
    val active: Boolean = false,
    @SerialName("effective_hive_limit") val effectiveHiveLimit: Int = 15,
    @SerialName("effective_apiary_limit") val effectiveApiaryLimit: Int = 3,
    @SerialName("member_role") val memberRole: String = "viewer"
)

@Serializable
data class BeeKeepHiveLimitOverride(
    val hive_limit_override: Int? = null
)

@Serializable
data class DebugEntitlementSyncResponse(
    val success: Boolean,
    val active: Boolean,
    val plan: String
)

@Serializable
data class WorkspaceUsernameParams(val target_username: String)

@Serializable
data class WorkspaceMemberRoleParams(
    val target_workspace_id: String,
    val target_user_id: String,
    val new_role: String
)

@Serializable
data class WorkspaceRemoveMemberParams(
    val target_workspace_id: String,
    val target_user_id: String
)


@Serializable
data class BeeKeepWorkspaceActionLogEntry(
    val id: String,
    val workspace_id: String,
    val client_action_id: String,
    val user_id: String? = null,
    val username: String,
    val action_type: String,
    val target_type: String,
    val target_id: String? = null,
    val description: String,
    val action_at: String,
    val created_at: String,
    val deleted_at: String? = null,
    val structured_version: Int = 0,
    val apiary_id: Long? = null,
    val apiary_name_snapshot: String? = null,
    val hive_id: Long? = null,
    val hive_name_snapshot: String? = null,
    val changes: List<BeeKeepWorkspaceActionChange> = emptyList(),
    val affected_hive_count: Int? = null,
    val affected_hive_ids: List<Long> = emptyList(),
    val affected_hives_snapshot: List<BeeKeepWorkspaceAffectedHiveSnapshot> = emptyList()
)

@Serializable
data class BeeKeepWorkspaceActionChange(
    val field: String,
    val old_value: kotlinx.serialization.json.JsonElement? = null,
    val new_value: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class BeeKeepWorkspaceAffectedHiveSnapshot(
    val hive_id: Long,
    val name: String
)

@Serializable
data class BeeKeepWorkspaceActionLogSettings(
    val workspace_id: String,
    val retention_mode: String = "keep",
    val updated_at: String = "",
    val updated_by: String? = null
)

@Serializable
data class WorkspaceActionRetentionParams(
    val target_workspace_id: String,
    val target_retention_mode: String
)

@Serializable
data class WorkspaceActionDeleteParams(val target_entry_id: String)

/**
 * Accept the current Bee + six-digit invitation format and older invitations that
 * were already issued before the format change.
 */
internal fun isSupportedWorkspaceInvitationCode(value: String): Boolean {
    val token = value.trim()
    val beeCode = token.length == 9 &&
        token.regionMatches(0, "Bee", 0, 3, ignoreCase = true) &&
        token.substring(3).all { it in '0'..'9' }
    val legacyShortCode = token.length == 8 &&
        token.all { it.uppercaseChar() in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }
    return beeCode || legacyShortCode || token.length in 32..128
}

/** Result wrapper for workspace operations; errors are surfaced as plain, user-safe messages. */
data class WorkspaceOperationResult<T>(
    val value: T? = null,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}
/** A team can be edited only while its owner has active Commercial access. */
internal object WorkspaceAccessPolicy {
    fun shouldLockWorkspacePage(
        hasActiveTeam: Boolean,
        activeTeamOwnerCommercialEntitlementActive: Boolean?,
        hasOwnedTeamWorkspace: Boolean,
        ownCommercialEntitlementActive: Boolean?
    ): Boolean = if (hasActiveTeam) {
        activeTeamOwnerCommercialEntitlementActive == false
    } else {
        hasOwnedTeamWorkspace && ownCommercialEntitlementActive == false
    }

    fun cannotVerifyWorkspacePageAccess(
        hasActiveTeam: Boolean,
        activeTeamOwnerCommercialEntitlementActive: Boolean?,
        hasOwnedTeamWorkspace: Boolean,
        ownCommercialEntitlementActive: Boolean?
    ): Boolean = if (hasActiveTeam) {
        activeTeamOwnerCommercialEntitlementActive == null
    } else {
        hasOwnedTeamWorkspace && ownCommercialEntitlementActive == null
    }

    fun isReadOnly(memberRole: String?, ownerCommercialEntitlementActive: Boolean): Boolean =
        when (memberRole) {
            "owner", "admin", "member" -> !ownerCommercialEntitlementActive
            "viewer" -> true
            else -> true
        }
}
