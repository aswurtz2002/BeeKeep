package com.beekeep.app.cloud

/**
 * Bounds auth refresh retries so a missing network cannot keep startup or background work alive forever.
 */
internal object SessionRefreshPolicy {
    const val MAX_ATTEMPTS = 3

    /** Delay after a failed attempt; null means the retry budget is exhausted. */
    fun retryDelayMillis(failedAttempt: Int): Long? = when (failedAttempt) {
        1 -> 1_000L
        2 -> 2_000L
        else -> null
    }

    /** Auth 4xx responses (except transient 408/429) mean the refresh credential is not usable. */
    fun isRejectedRefreshCredential(statusCode: Int): Boolean =
        statusCode in 400..499 && statusCode != 408 && statusCode != 429
}
