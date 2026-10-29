package com.beekeep.app.cloud

/**
 * Converts the verbose PostgREST RLS denial for shared workspace documents into an actionable
 * message. The outbox item is only marked synced after the upsert succeeds, so failed edits remain
 * queued locally and can be retried after the workspace access issue is fixed.
 */
internal object TeamWorkspaceSyncError {
    const val RLS_DENIED_MESSAGE =
        "BeeKeep Cloud blocked this team sync. The workspace owner must have an active Ultimate " +
            "entitlement recognized by the server, and your workspace role must allow editing " +
            "(owner, admin, or member). Check the owner's plan sync and your role, then retry " +
            "Sync Now. Your edits remain saved locally and queued."

    fun userFacing(rawMessage: String?): String {
        val message = rawMessage.orEmpty()
        val isSharedDocumentRlsDenial =
            message.contains("row-level security policy", ignoreCase = true) &&
                message.contains("beekeep_workspace_documents", ignoreCase = true)

        return when {
            isSharedDocumentRlsDenial -> RLS_DENIED_MESSAGE
            message.isNotBlank() -> message
            else -> "Team workspace sync failed"
        }
    }
}
