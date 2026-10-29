package com.beekeep.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Inspection note drafts are keyed by workspace as well as hive. This prevents a locally
 * cached personal draft from appearing when a different team happens to use the same hive ID.
 */
internal const val INSPECTION_NOTE_DRAFTS = "beekeep_inspection_note_drafts"

internal data class InspectionNoteDraftUpdate(
    val workspaceId: String?,
    val hiveId: Long,
    val notes: String,
    val recordedAt: Long?,
    val updatedAt: Long,
    val deleted: Boolean = false
)

internal fun inspectionNoteDraftScope(workspaceId: String?): String =
    workspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: "personal"

internal fun inspectionNoteDraftKey(workspaceId: String?, hiveId: Long): String =
    "${inspectionNoteDraftScope(workspaceId)}_hive_$hiveId"

internal fun inspectionNoteDraftRecordedAtKey(draftKey: String): String = "${draftKey}_recorded_at"

/** Read the locally selected workspace without exposing its identifier in UI code. */
internal fun inspectionNoteDraftKeyForContext(context: Context, hiveId: Long): String {
    val workspaceId = context.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        .getString("active_team_workspace_id", "").orEmpty().trim().takeIf { it.isNotEmpty() }
    return inspectionNoteDraftKey(workspaceId, hiveId)
}

internal fun migrateLegacyInspectionNoteDraftForContext(
    prefs: SharedPreferences,
    context: Context,
    hiveId: Long
): Boolean {
    val workspaceId = context.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        .getString("active_team_workspace_id", "").orEmpty().trim().takeIf { it.isNotEmpty() }
    return migrateLegacyInspectionNoteDraft(prefs, workspaceId, hiveId)
}

internal fun inspectionNoteDraftUpdatedAtKey(draftKey: String): String = "${draftKey}_updated_at"

/**
 * Move the old device-only personal draft into the personal namespace on first use.
 * Team workspace screens never read or migrate the old unscoped key.
 *
 * @return true when a legacy draft was migrated and still needs to be put in the cloud outbox.
 */
internal fun migrateLegacyInspectionNoteDraft(
    prefs: SharedPreferences,
    workspaceId: String?,
    hiveId: Long
): Boolean {
    if (workspaceId?.trim()?.isNotEmpty() == true || hiveId <= 0L) return false

    val targetKey = inspectionNoteDraftKey(null, hiveId)
    val targetRecordedAtKey = inspectionNoteDraftRecordedAtKey(targetKey)
    val targetUpdatedAtKey = inspectionNoteDraftUpdatedAtKey(targetKey)

    if (prefs.contains(targetKey)) {
        if (prefs.contains(targetUpdatedAtKey)) return false
        val recordedAt = prefs.getLong(targetRecordedAtKey, 0L).takeIf { it > 0L }
        val updatedAt = maxOf(System.currentTimeMillis(), recordedAt ?: 0L)
        prefs.edit()
            .putLong(targetUpdatedAtKey, updatedAt)
            .apply()
        return true
    }

    val legacyKey = "hive_$hiveId"
    if (!prefs.contains(legacyKey)) return false

    val notes = prefs.getString(legacyKey, "").orEmpty()
    val legacyRecordedAt = prefs.getLong("${legacyKey}_recorded_at", 0L).takeIf { it > 0L }
    val recordedAt = legacyRecordedAt.takeUnless { notes.isBlank() }
    val updatedAt = maxOf(System.currentTimeMillis(), legacyRecordedAt ?: 0L)

    val editor = prefs.edit()
        .putString(targetKey, notes)
        .putLong(targetUpdatedAtKey, updatedAt)
        .remove(legacyKey)
        .remove("${legacyKey}_recorded_at")
    if (recordedAt != null) editor.putLong(targetRecordedAtKey, recordedAt)
    else editor.remove(targetRecordedAtKey)
    editor.apply()
    return true
}

internal fun inspectionNoteDraftPayload(
    hiveId: Long,
    notes: String,
    recordedAt: Long?,
    updatedAt: Long
): String = JSONObject()
    .put("id", hiveId)
    .put("hive_id", hiveId)
    .put("notes", notes)
    .put("recorded_at", recordedAt ?: JSONObject.NULL)
    .put("updated_at", updatedAt)
    .toString()

/** Accept equal timestamps idempotently, but never let an older cloud draft replace newer local text. */
internal fun shouldApplyInspectionNoteDraft(incomingUpdatedAt: Long, localUpdatedAt: Long): Boolean =
    incomingUpdatedAt >= localUpdatedAt
