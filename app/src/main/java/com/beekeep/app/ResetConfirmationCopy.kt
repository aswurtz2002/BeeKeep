package com.beekeep.app

/**
 * Copy shown before a destructive reset. A team reset affects every member's shared
 * workspace data, while a personal reset must leave all team workspaces untouched.
 */
internal data class ResetConfirmationCopy(
    val title: String,
    val recordScope: String,
    val cloudImpact: String,
    val localDataLabel: String,
    val cloudDataLabel: String
)

internal fun resetConfirmationCopy(isTeamWorkspace: Boolean): ResetConfirmationCopy =
    if (isTeamWorkspace) {
        ResetConfirmationCopy(
            title = "Reset this team workspace?",
            recordScope = "This permanently deletes every apiary and hive in this team workspace, along with inspections, notes, feedings, treatments, harvests, activity history, tasks and all NFC tag assignments.",
            cloudImpact = "This cannot be undone. Shared cloud records for this team workspace will also be cleared for all members when cloud access is available. Your personal BeeKeep data is separate and will not be changed.",
            localDataLabel = "team workspace data",
            cloudDataLabel = "shared cloud data for this team workspace"
        )
    } else {
        ResetConfirmationCopy(
            title = "Reset personal apiaries?",
            recordScope = "This permanently deletes every apiary and hive in your personal data, along with inspections, notes, feedings, treatments, harvests, activity history, tasks and all NFC tag assignments.",
            cloudImpact = "This cannot be undone. Personal BeeKeep Cloud data for your signed-in account will also be cleared when cloud access is available. Team workspaces are separate and will not be changed.",
            localDataLabel = "personal data",
            cloudDataLabel = "personal BeeKeep Cloud data for this account"
        )
    }
