package com.beekeep.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Persists unfinished inspection fields separately from completed inspection history.
 * The key is scoped by workspace so personal drafts and drafts from different teams
 * can never overwrite each other on the same device.
 */
internal object InspectionFormDraftStore {
    const val PREFERENCES_NAME = "beekeep_inspection_form_drafts"
    private const val LEGACY_PREFIX = "hive_"
    private val metadataKeys = setOf("id", "hive_id", "updated_at", "updatedAt", "deleted")

    fun keyPrefix(workspaceId: String?): String = "${scope(workspaceId)}_hive_"

    fun key(workspaceId: String?, hiveId: Long): String = "${keyPrefix(workspaceId)}$hiveId"

    fun pendingKey(key: String): String = "${key}_pending"

    fun legacyKey(hiveId: Long): String = "$LEGACY_PREFIX$hiveId"

    fun readRaw(prefs: SharedPreferences, workspaceId: String?, hiveId: Long): String? {
        prefs.getString(key(workspaceId, hiveId), null)?.let { return it }
        return if (workspaceId.isNullOrBlank()) prefs.getString(legacyKey(hiveId), null) else null
    }

    fun updatedAt(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return 0L
        return json.optLong("updated_at", json.optLong("updatedAt", 0L))
    }

    fun isDeleted(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching { JSONObject(raw).optBoolean("deleted", false) }.getOrDefault(false)
    }

    fun isPending(prefs: SharedPreferences, workspaceId: String?, hiveId: Long): Boolean =
        prefs.getBoolean(pendingKey(key(workspaceId, hiveId)), false)

    /**
     * Writes a local edit and marks it for cloud upload only when the field values changed.
     * Ignoring timestamps in the equality check prevents an imported draft from bouncing
     * between devices just because Compose re-saved the same values.
     */
    fun writeLocal(
        prefs: SharedPreferences,
        workspaceId: String?,
        hiveId: Long,
        draft: JSONObject
    ): Boolean {
        require(hiveId > 0L) { "Inspection form draft requires a valid hive ID." }
        val key = key(workspaceId, hiveId)
        val currentRaw = prefs.getString(key, null)
        val legacyRaw = if (currentRaw == null && workspaceId.isNullOrBlank()) {
            prefs.getString(legacyKey(hiveId), null)
        } else null
        val existingRaw = currentRaw ?: legacyRaw
        val migratingLegacyDraft = currentRaw == null && legacyRaw != null
        val sameContent = existingRaw != null &&
            !isDeleted(existingRaw) &&
            contentMatches(existingRaw, draft)

        if (sameContent && !migratingLegacyDraft) return false

        val timestamp = maxOf(System.currentTimeMillis(), updatedAt(existingRaw) + 1L)
        val stored = if (sameContent && migratingLegacyDraft) {
            JSONObject(existingRaw!!)
        } else {
            JSONObject(draft.toString())
        }
        stored.put("id", hiveId)
            .put("hive_id", hiveId)
            .put("updated_at", timestamp)
            .put("updatedAt", timestamp)
            .remove("deleted")

        prefs.edit {
            putString(key, stored.toString())
            putBoolean(pendingKey(key), true)
            if (legacyRaw != null) remove(legacyKey(hiveId))
        }
        return true
    }

    /** Creates a cloud tombstone so another device discards its unfinished copy after save. */
    fun markDeleted(prefs: SharedPreferences, workspaceId: String?, hiveId: Long) {
        require(hiveId > 0L) { "Inspection form draft requires a valid hive ID." }
        val key = key(workspaceId, hiveId)
        val existingRaw = readRaw(prefs, workspaceId, hiveId)
        val timestamp = maxOf(System.currentTimeMillis(), updatedAt(existingRaw) + 1L)
        val tombstone = JSONObject()
            .put("id", hiveId)
            .put("hive_id", hiveId)
            .put("updated_at", timestamp)
            .put("updatedAt", timestamp)
            .put("deleted", true)
        prefs.edit {
            putString(key, tombstone.toString())
            putBoolean(pendingKey(key), true)
            if (workspaceId.isNullOrBlank()) remove(legacyKey(hiveId))
        }
    }

    /** Saves a remote snapshot without marking it as a local edit to upload again. */
    fun applyRemote(
        prefs: SharedPreferences,
        workspaceId: String?,
        hiveId: Long,
        payload: String
    ) {
        val key = key(workspaceId, hiveId)
        val json = JSONObject(payload)
        json.put("id", hiveId)
            .put("hive_id", hiveId)
            .put("updatedAt", updatedAt(payload))
        prefs.edit {
            putString(key, json.toString())
            putBoolean(pendingKey(key), false)
            if (workspaceId.isNullOrBlank()) remove(legacyKey(hiveId))
        }
    }

    /**
     * Applies a remote delete only when it is not racing a pending local edit or a
     * newer local version. Also used to discard an unfinished draft superseded by
     * a completed inspection.
     */
    fun clearRemote(
        prefs: SharedPreferences,
        workspaceId: String?,
        hiveId: Long,
        incomingUpdatedAt: Long
    ): Boolean {
        val key = key(workspaceId, hiveId)
        val existingRaw = readRaw(prefs, workspaceId, hiveId)
        if (isPending(prefs, workspaceId, hiveId)) return false
        if (updatedAt(existingRaw) > incomingUpdatedAt) return false

        prefs.edit {
            remove(key)
            remove(pendingKey(key))
            if (workspaceId.isNullOrBlank()) remove(legacyKey(hiveId))
        }
        return true
    }

    private fun scope(workspaceId: String?): String =
        workspaceId?.trim()?.takeIf { it.isNotEmpty() }?.let { "workspace_$it" } ?: "personal"

    private fun contentMatches(existingRaw: String, incoming: JSONObject): Boolean {
        val existing = runCatching { JSONObject(existingRaw) }.getOrNull() ?: return false
        if (existing.optBoolean("deleted", false)) return false
        val leftKeys = contentKeys(existing)
        val rightKeys = contentKeys(incoming)
        if (leftKeys != rightKeys) return false
        return leftKeys.all { key -> sameJsonValue(existing.opt(key), incoming.opt(key)) }
    }

    private fun sameJsonValue(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) {
            return left.toDouble() == right.toDouble()
        }
        return left?.toString() == right?.toString()
    }

    private fun contentKeys(json: JSONObject): List<String> =
        json.keys().asSequence().filterNot { it in metadataKeys }.sorted().toList()
}
