package com.beekeep.app.cloud

internal object AccountSetupPolicy {
    private val usernamePattern = Regex("^[A-Za-z0-9_]{3,20}$")

    fun isValidUsername(username: String): Boolean =
        usernamePattern.matches(username.trim())

    fun personalWorkspaceForAccount(
        workspaces: List<BeeKeepWorkspace>,
        userId: String
    ): BeeKeepWorkspace? {
        val ownedPersonalWorkspaces = workspaces.filter {
            it.is_personal &&
                it.owner_user_id == userId &&
                it.member_role == "owner"
        }
        return ownedPersonalWorkspaces.singleOrNull()
    }
}
