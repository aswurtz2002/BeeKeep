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
}
