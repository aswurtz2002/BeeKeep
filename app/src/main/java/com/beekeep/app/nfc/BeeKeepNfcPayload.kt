package com.beekeep.app.nfc

/**
 * NFC constants for BeeKeep tags.
 *
 * New tags carry only this generic marker plus Android's Application Record.
 * Hive IDs in the old MIME format are parsed only to migrate legacy tags.
 */
object BeeKeepNfcPayload {
    const val MIME_TYPE = "application/vnd.beekeep.tag"
    const val LEGACY_MIME_TYPE = "application/vnd.beekeep.hive"
    const val TAG_PAYLOAD = "BEEKEEP:TAG"
    const val UNASSIGNED_PAYLOAD = "BEEKEEP:UNASSIGNED"
    private const val LEGACY_HIVE_PREFIX = "BEEKEEP:HIVE:"

    /** Legacy-format helper retained for diagnostics/tests; never write this to a tag. */
    fun forHive(hiveId: Long): String = LEGACY_HIVE_PREFIX + hiveId

    /** Reads old hive-specific payloads so BeeKeep can recognize and migrate older tags. */
    fun hiveId(text: String?): Long? {
        val normalized = text?.trim() ?: return null
        if (!normalized.startsWith(LEGACY_HIVE_PREFIX)) return null
        return normalized.removePrefix(LEGACY_HIVE_PREFIX).toLongOrNull()?.takeIf { it > 0L }
    }

    fun isTagPayload(text: String?): Boolean =
        text?.trim().equals(TAG_PAYLOAD, ignoreCase = true)

    fun isUnassignedPayload(text: String?): Boolean =
        text?.trim().equals(UNASSIGNED_PAYLOAD, ignoreCase = true)

    fun isBeeKeepPayload(text: String?): Boolean =
        isTagPayload(text) || hiveId(text) != null || isUnassignedPayload(text)
}
