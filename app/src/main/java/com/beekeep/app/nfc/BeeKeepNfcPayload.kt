package com.beekeep.app.nfc

/** Stable NDEF payload used by BeeKeep hive tags. */
object BeeKeepNfcPayload {
    const val MIME_TYPE = "application/vnd.beekeep.hive"
    private const val PREFIX = "BEEKEEP:HIVE:"

    fun forHive(hiveId: Long): String = PREFIX + hiveId

    fun hiveId(text: String?): Long? {
        val normalized = text?.trim() ?: return null
        if (!normalized.startsWith(PREFIX)) return null
        return normalized.removePrefix(PREFIX).toLongOrNull()?.takeIf { it > 0L }
    }

    fun isBeeKeepPayload(text: String?): Boolean = hiveId(text) != null
}
