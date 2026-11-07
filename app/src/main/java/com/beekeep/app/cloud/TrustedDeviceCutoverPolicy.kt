package com.beekeep.app.cloud

/**
 * The server uses one request status for both the approving and replacement installations.
 * The installation's previous trusted binding determines which side of the cutover it is.
 */
internal object TrustedDeviceCutoverPolicy {
    fun finalizationStatus(
        requestStatus: String?,
        previouslyTrustedHere: Boolean
    ): TrustedDeviceAccessStatus? {
        if (requestStatus?.trim()?.lowercase() !in setOf(
                "approved_pending_finalize",
                "finalizing"
            )
        ) {
            return null
        }
        return if (previouslyTrustedHere) {
            TrustedDeviceAccessStatus.REPLACED_LOCAL
        } else {
            TrustedDeviceAccessStatus.PENDING_FINALIZATION
        }
    }
}
