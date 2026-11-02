package com.beekeep.app.cloud

/**
 * A workspace ID is only a device preference. It is not proof that the current session
 * belongs to the account that selected the cached local workspace.
 */
internal object ActiveWorkspaceSessionPolicy {
    fun ownerMatches(sessionUserId: String?, storedOwnerUserId: String?): Boolean {
        val session = sessionUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val owner = storedOwnerUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return session == owner
    }

    /**
     * A cached team may stay selected while network membership checks are unavailable, but only
     * for the account that originally selected it. Callers must keep it read-only until verified.
     */
    fun canKeepCachedWorkspace(
        workspaceId: String?,
        sessionUserId: String?,
        storedOwnerUserId: String?
    ): Boolean =
        !workspaceId.isNullOrBlank() && ownerMatches(sessionUserId, storedOwnerUserId)
}
