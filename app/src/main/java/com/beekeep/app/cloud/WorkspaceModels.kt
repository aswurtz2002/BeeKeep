package com.beekeep.app.cloud

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

/** Result wrapper for workspace operations; errors are surfaced as plain, user-safe messages. */
data class WorkspaceOperationResult<T>(
    val value: T? = null,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}
