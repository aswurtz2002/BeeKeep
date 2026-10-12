package com.beekeep.app.nfc

/**
 * Resolves NFC hive links safely when the NDEF payload can outlive a local UID assignment.
 * A released UID takes precedence over the old hive ID still stored on a physical tag.
 */
object NfcTagResolution {
    fun resolveHiveId(
        assignedHiveId: Long?,
        payloadHiveId: Long?,
        tagWasReleased: Boolean
    ): Long? = assignedHiveId ?: payloadHiveId.takeUnless { tagWasReleased }

    fun targetPayload(
        assignedHiveId: Long?,
        payloadHiveId: Long?,
        payloadText: String?,
        tagWasReleased: Boolean
    ): String = when {
        assignedHiveId != null -> BeeKeepNfcPayload.forHive(assignedHiveId)
        tagWasReleased -> BeeKeepNfcPayload.UNASSIGNED_PAYLOAD
        payloadHiveId != null -> payloadText?.trim()?.takeIf { it.isNotBlank() }
            ?: BeeKeepNfcPayload.forHive(payloadHiveId)
        else -> BeeKeepNfcPayload.UNASSIGNED_PAYLOAD
    }

    fun mayOverwriteStaleHivePayload(assignedHiveId: Long?, tagWasReleased: Boolean): Boolean =
        assignedHiveId == null && tagWasReleased
}
