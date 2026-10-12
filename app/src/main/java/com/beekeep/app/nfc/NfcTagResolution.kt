package com.beekeep.app.nfc

/**
 * Hive identity comes only from the active UID assignment stored in BeeKeep's database.
 * Legacy payload and release-history inputs are retained for source compatibility but ignored.
 */
object NfcTagResolution {
    fun resolveHiveId(
        assignedHiveId: Long?,
        @Suppress("UNUSED_PARAMETER") legacyPayloadHiveId: Long? = null,
        @Suppress("UNUSED_PARAMETER") tagWasReleased: Boolean = false
    ): Long? = assignedHiveId
}
