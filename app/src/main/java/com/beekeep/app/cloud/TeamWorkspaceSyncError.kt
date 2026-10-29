package com.beekeep.app.cloud

/**
 * Keeps verbose database/HTTP implementation details out of the field UI while preserving
 * actionable messages. Failed outbox records remain pending until an upload succeeds.
 */
internal object TeamWorkspaceSyncError {
    const val RLS_DENIED_MESSAGE =
        "BeeKeep Cloud blocked this team sync. The workspace owner must have an active Ultimate " +
            "entitlement recognized by the server, and your workspace role must allow editing " +
            "(owner, admin, or member). Check the owner's plan sync and your role, then retry " +
            "Sync Now. Your edits remain saved locally and queued."

    const val MISSING_DELETED_FLAG_MESSAGE =
        "Sync failed because a record was sent without its required deletion flag. Your edits " +
            "remain saved locally and queued. Update BeeKeep to the newest build, then retry Sync Now."

    const val GENERIC_SYNC_ERROR_MESSAGE =
        "BeeKeep couldn't complete the sync because Cloud returned a technical error. Your edits " +
            "remain saved locally and queued. Retry Sync Now; if it continues, contact support."

    fun userFacing(rawMessage: String?): String {
        val message = rawMessage.orEmpty().trim()
        val lower = message.lowercase()
        val isSharedDocumentRlsDenial =
            lower.contains("row-level security policy") &&
                lower.contains("beekeep_workspace_documents")
        val isMissingDeletedFlag =
            lower.contains("null value in column") &&
                lower.contains("deleted") &&
                (lower.contains("not-null constraint") || lower.contains("not null constraint"))

        return when {
            isSharedDocumentRlsDenial -> RLS_DENIED_MESSAGE
            isMissingDeletedFlag -> MISSING_DELETED_FLAG_MESSAGE
            message.isBlank() -> "Sync failed. Your edits remain saved locally and queued. Retry Sync Now."
            message.length > 240 ||
                '\n' in message ||
                lower.contains("headers:") ||
                lower.contains("url:") ||
                lower.contains("response body") ||
                lower.contains("stacktrace") ||
                lower.contains("could not execute statement") ||
                lower.contains("exception:") -> GENERIC_SYNC_ERROR_MESSAGE
            else -> message
        }
    }
}
