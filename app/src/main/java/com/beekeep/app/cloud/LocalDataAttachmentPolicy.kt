package com.beekeep.app.cloud

/**
 * Decides whether this device must pause cloud sync until its local records are reviewed.
 *
 * An account switch is always held, even when an older pending marker belongs to another
 * user. A matching pending marker remains held across relaunches and sign-outs.
 */
internal object LocalDataAttachmentPolicy {
    fun requiresReview(
        previousUserId: String?,
        pendingUserId: String?,
        currentUserId: String
    ): Boolean {
        val current = currentUserId.trim().takeIf { it.isNotEmpty() } ?: return true
        val previous = previousUserId?.trim()?.takeIf { it.isNotEmpty() }
        val pending = pendingUserId?.trim()?.takeIf { it.isNotEmpty() }
        return previous != current || pending == current
    }
}
