package com.beekeep.app

import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beekeep.app.data.IdGenerator
import com.beekeep.app.data.InspectionNoteDraftUpdate
import com.beekeep.app.data.inspectionNoteDraftKey
import com.beekeep.app.data.inspectionNoteDraftRecordedAtKey
import com.beekeep.app.data.inspectionNoteDraftUpdatedAtKey
import com.beekeep.app.data.migrateLegacyInspectionNoteDraft
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.beekeep.app.data.ActivityEvent
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import com.beekeep.app.location.LocationController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

internal const val INSPECTION_NOTE_DRAFTS = "beekeep_inspection_note_drafts"

private fun normalizeBroodStoresGauge(value: Double): Double =
    (value.coerceIn(0.0, 10.0) * 2.0).roundToInt() / 2.0

private fun formatBroodStoresGauge(value: Double): String {
    val whole = value.roundToInt()
    return if (value == whole.toDouble()) whole.toString() else String.format(Locale.US, "%.1f", value)
}
private const val INSPECTION_FORM_DRAFTS = "beekeep_inspection_form_drafts"

/**
 * Discard unfinished field-entry state when a hive is permanently removed.
 * These values intentionally live outside Room so an inspection can survive
 * navigation or process death, so deletion paths must clean them explicitly.
 */
internal fun clearInspectionDraftsForHives(
    context: android.content.Context,
    hiveIds: Collection<Long>
) {
    val validHiveIds = hiveIds.asSequence().filter { it > 0L }.distinct().toList()
    if (validHiveIds.isEmpty()) return

    // Batch a whole apiary's cleanup into one edit per preference store, instead
    // of scheduling a full XML rewrite for every hive in a large yard.
    val workspaceId = context.getSharedPreferences("beekeep_prefs", android.content.Context.MODE_PRIVATE)
        .getString("active_team_workspace_id", "").orEmpty().trim().takeIf { it.isNotEmpty() }
    context.getSharedPreferences(INSPECTION_NOTE_DRAFTS, android.content.Context.MODE_PRIVATE).edit {
        validHiveIds.forEach { hiveId ->
            val hiveKey = inspectionNoteDraftKey(workspaceId, hiveId)
            remove(hiveKey)
            remove(inspectionNoteDraftRecordedAtKey(hiveKey))
            remove(inspectionNoteDraftUpdatedAtKey(hiveKey))
            if (workspaceId == null) {
                remove("hive_$hiveId")
                remove("hive_${hiveId}_recorded_at")
            }
        }
    }
    context.getSharedPreferences(INSPECTION_FORM_DRAFTS, android.content.Context.MODE_PRIVATE).edit {
        validHiveIds.forEach { hiveId ->
            remove("hive_$hiveId")
            remove("bee_count_history_start_$hiveId")
        }
    }
}

internal fun clearInspectionDraftsForHive(context: android.content.Context, hiveId: Long) {
    clearInspectionDraftsForHives(context, listOf(hiveId))
}

/** Clear all unfinished field-entry state after Reset Apiaries succeeds locally. */
internal fun clearAllInspectionDrafts(context: android.content.Context) {
    context.getSharedPreferences(INSPECTION_NOTE_DRAFTS, android.content.Context.MODE_PRIVATE).edit {
        clear()
    }
    context.getSharedPreferences(INSPECTION_FORM_DRAFTS, android.content.Context.MODE_PRIVATE).edit {
        clear()
    }
}

private data class InspectionFormDraft(
    val strength: Int,
    val queenStatus: String,
    val miteCount: Int,
    val sampleSize: Int,
    val eggs: Double,
    val openBrood: Double,
    val cappedBrood: Double,
    val honeyStores: Double,
    val pollen: Double,
    val emptyDrawnComb: Double,
    val emergencyCells: Int,
    val supercedureCells: Int,
    val swarmCells: Int,
    val diseaseFlags: String,
    val updatedAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val beeCountHalfSteps: Int? = null
)

private fun readInspectionFormDraft(
    prefs: android.content.SharedPreferences,
    hiveId: Long
): InspectionFormDraft? {
    val raw = prefs.getString("hive_$hiveId", null) ?: return null
    return runCatching {
        val json = JSONObject(raw)
        InspectionFormDraft(
            strength = json.optInt("strength", 5),
            queenStatus = json.optString("queenStatus", "Laying"),
            miteCount = json.optInt("miteCount", 0),
            sampleSize = json.optInt("sampleSize", 300).coerceAtLeast(1),
            eggs = json.optDouble("eggs", 0.0).coerceIn(0.0, 10.0),
            openBrood = json.optDouble("openBrood", 0.0).coerceIn(0.0, 10.0),
            cappedBrood = json.optDouble("cappedBrood", 0.0).coerceIn(0.0, 10.0),
            honeyStores = json.optDouble("honeyStores", 0.0).coerceIn(0.0, 10.0),
            pollen = json.optDouble("pollen", 0.0).coerceIn(0.0, 10.0),
            emptyDrawnComb = json.optDouble("emptyDrawnComb", 0.0).coerceIn(0.0, 10.0),
            emergencyCells = json.optInt("emergencyCells", 0),
            supercedureCells = json.optInt("supercedureCells", 0),
            swarmCells = json.optInt("swarmCells", 0),
            diseaseFlags = json.optString("diseaseFlags", ""),
            updatedAt = json.optLong("updatedAt", 0L),
            latitude = if (json.has("latitude") && !json.isNull("latitude")) json.optDouble("latitude") else null,
            longitude = if (json.has("longitude") && !json.isNull("longitude")) json.optDouble("longitude") else null,
            beeCountHalfSteps = if (json.has("beeCountHalfSteps") && !json.isNull("beeCountHalfSteps")) {
                json.optInt("beeCountHalfSteps", 1).coerceIn(1, 20)
            } else null
        )
    }.getOrNull()
}

private fun saveInspectionFormDraft(
    prefs: android.content.SharedPreferences,
    hiveId: Long,
    draft: InspectionFormDraft
) {
    val json = JSONObject()
        .put("strength", ColonyStrength.storedFromGrade(draft.strength))
        .put("queenStatus", draft.queenStatus)
        .put("miteCount", draft.miteCount.coerceAtLeast(0))
        .put("sampleSize", draft.sampleSize.coerceAtLeast(1))
        .put("eggs", normalizeBroodStoresGauge(draft.eggs))
        .put("openBrood", normalizeBroodStoresGauge(draft.openBrood))
        .put("cappedBrood", normalizeBroodStoresGauge(draft.cappedBrood))
        .put("honeyStores", normalizeBroodStoresGauge(draft.honeyStores))
        .put("pollen", normalizeBroodStoresGauge(draft.pollen))
        .put("emptyDrawnComb", normalizeBroodStoresGauge(draft.emptyDrawnComb))
        .put("emergencyCells", draft.emergencyCells.coerceAtLeast(0))
        .put("supercedureCells", draft.supercedureCells.coerceAtLeast(0))
        .put("swarmCells", draft.swarmCells.coerceAtLeast(0))
        .put("diseaseFlags", draft.diseaseFlags)
        .put("updatedAt", System.currentTimeMillis())
        .put("latitude", draft.latitude ?: JSONObject.NULL)
        .put("longitude", draft.longitude ?: JSONObject.NULL)
        .put("beeCountHalfSteps", draft.beeCountHalfSteps ?: JSONObject.NULL)
    prefs.edit { putString("hive_$hiveId", json.toString()) }
}

private fun inspectionNoteDatePrefix(timestamp: Long): String =
    "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))} — "

private fun hasInlineInspectionNoteDate(notes: String): Boolean {
    val firstLine = notes.lineSequence().firstOrNull() ?: return false
    val separator = firstLine.indexOf(" — ")
    if (separator <= 0) return false
    val dateText = firstLine.substring(0, separator)
    val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val parsed = runCatching { formatter.parse(dateText) }.getOrNull() ?: return false
    return formatter.format(parsed) == dateText
}

/**
 * Adds a date directly to the first note and to each new line appended at the end.
 * Existing lines stay unchanged when they're edited; dates become part of the saved text.
 */
private fun addDateToNewInspectionNoteLines(previousNotes: String, updatedNotes: String): String {
    if (updatedNotes.isEmpty()) return updatedNotes
    val datePrefix = inspectionNoteDatePrefix(System.currentTimeMillis())

    // First text in an empty field gets a date. Pasted multi-line text receives a date
    // for each line, while Enter at the start preserves the empty first line.
    if (previousNotes.isBlank()) {
        return updatedNotes.split('\n').mapIndexed { index, line ->
            when {
                line.startsWith(datePrefix) -> line
                index == 0 && line.isBlank() -> line
                else -> datePrefix + line
            }
        }.joinToString("\n")
    }

    // Only auto-date lines when text is appended, so editing historical lines does not
    // rewrite their content or replace their original dates.
    if (!updatedNotes.startsWith(previousNotes)) return updatedNotes
    val appended = updatedNotes.substring(previousNotes.length)
    if (!appended.contains('\n')) return updatedNotes

    val appendedLines = appended.split('\n')
    return buildString {
        append(previousNotes)
        appendedLines.forEachIndexed { index, line ->
            if (index == 0) {
                append(line)
            } else {
                append('\n')
                append(if (line.startsWith(datePrefix)) line else datePrefix + line)
            }
        }
    }
}

@Composable
internal fun InspectionSnapshot(i: Inspection) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${ColonyStrength.labelFromStored(i.strength)} • Queen ${i.queenStatus}")
            Text("Mites ${i.miteCount}/${i.sampleSize} = ${String.format(Locale.US, "%.2f", i.mitePercent)}%")
            Text("Brood: eggs ${formatBroodStoresGauge(i.eggs)}, open ${formatBroodStoresGauge(i.openBrood)}, capped ${formatBroodStoresGauge(i.cappedBrood)}")
            Text("Stores: honey ${formatBroodStoresGauge(i.honeyStores)}, pollen ${formatBroodStoresGauge(i.pollen)}")
            if (i.diseaseFlags.isNotBlank()) {
                Text("Flags: ${i.diseaseFlags}", color = MaterialTheme.colorScheme.error)
            }
            if (i.notes.isNotBlank()) {
                Text("FIELD NOTES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (hasInlineInspectionNoteDate(i.notes)) i.notes else "${inspectionNoteDatePrefix(i.createdAt)}${i.notes}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
internal fun InspectionHoneycombBackground(modifier: Modifier = Modifier) {
    val patternColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    Canvas(modifier) {
        val radius = 18.dp.toPx()
        val horizontalStep = radius * 1.5f
        val verticalStep = radius * kotlin.math.sqrt(3f)
        val honeycomb = Path()
        var row = 0
        var centerY = -radius
        while (centerY <= size.height + radius) {
            var centerX = (if (row % 2 == 0) 0f else horizontalStep / 2f) - radius
            while (centerX <= size.width + radius) {
                for (vertex in 0..5) {
                    val angle = Math.PI * vertex / 3.0
                    val x = centerX + radius * Math.cos(angle).toFloat()
                    val y = centerY + radius * Math.sin(angle).toFloat()
                    if (vertex == 0) honeycomb.moveTo(x, y) else honeycomb.lineTo(x, y)
                }
                honeycomb.close()
                centerX += horizontalStep
            }
            centerY += verticalStep
            row++
        }
        drawPath(honeycomb, patternColor, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
internal fun InspectionScreen(
    activity: ComponentActivity,
    locationController: LocationController,
    hive: Hive,
    workspaceId: String?,
    noteDraftUpdates: Flow<InspectionNoteDraftUpdate>,
    priorInspections: List<Inspection>,
    beeCountHistory: List<ActivityEvent>,
    defaultMiteSampleSize: Int,
    defaultStartingStrength: Int,
    rememberLastInspectionValues: Boolean,
    voiceReadyBeepEnabled: Boolean,
    autoStartVoice: Boolean,
    autoVoiceEnabled: Boolean,
    isReadOnlyWorkspace: Boolean,
    onViewOnlyAttempt: () -> Unit,
    onVoiceAutoStartConsumed: () -> Unit,
    onSaveHiveLocation: (Long, Double, Double, (String?) -> Unit) -> Unit,
    onRecordBeeCountChange: (Long, Double, Double) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSave: suspend (Inspection) -> Boolean,
    onSaveNoteDraft: suspend (Long, String, Long?, Long) -> Long,
    onSaved: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noteDraftPrefs = remember(context) {
        context.getSharedPreferences(INSPECTION_NOTE_DRAFTS, android.content.Context.MODE_PRIVATE)
    }
    val cleanDraftWorkspaceId = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
    val legacyDraftNeedsSync = remember(cleanDraftWorkspaceId, hive.id, noteDraftPrefs) {
        migrateLegacyInspectionNoteDraft(noteDraftPrefs, cleanDraftWorkspaceId, hive.id)
    }
    val formDraftPrefs = remember(context) {
        context.getSharedPreferences(INSPECTION_FORM_DRAFTS, android.content.Context.MODE_PRIVATE)
    }
    val noteDraftKey = remember(cleanDraftWorkspaceId, hive.id) {
        inspectionNoteDraftKey(cleanDraftWorkspaceId, hive.id)
    }
    val noteDraftTimestampKey = remember(noteDraftKey) { inspectionNoteDraftRecordedAtKey(noteDraftKey) }
    val noteDraftUpdatedAtKey = remember(noteDraftKey) { inspectionNoteDraftUpdatedAtKey(noteDraftKey) }
    // The inspection list may briefly still contain the previous hive while NFC
    // switches the selected hive. Never let another hive's latest inspection seed this form.
    val lastInspection = remember(hive.id, priorInspections) {
        priorInspections
            .asSequence()
            .filter { it.hiveId == hive.id }
            .maxByOrNull { it.createdAt }
    }
    // Load unfinished field values for this specific hive; if none exist, use the last saved inspection.
    val formDraft = remember(hive.id) { readInspectionFormDraft(formDraftPrefs, hive.id) }
    val formStateKey = formDraft?.updatedAt ?: 0L
    val hasSavedNoteDraft = remember(cleanDraftWorkspaceId, hive.id) { noteDraftPrefs.contains(noteDraftKey) }
    val savedNoteDraft = remember(cleanDraftWorkspaceId, hive.id) { noteDraftPrefs.getString(noteDraftKey, "").orEmpty() }
    // A stored empty string means the beekeeper deliberately cleared the notes; don't restore older text.
    val startingNotes = remember(cleanDraftWorkspaceId, hive.id, hasSavedNoteDraft, savedNoteDraft, lastInspection?.id, lastInspection?.notes) {
        val loadedNotes = if (hasSavedNoteDraft) savedNoteDraft else lastInspection?.notes.orEmpty()
        if (loadedNotes.isBlank() || hasInlineInspectionNoteDate(loadedNotes)) {
            loadedNotes
        } else {
            val originalTimestamp = if (hasSavedNoteDraft) {
                noteDraftPrefs.getLong(noteDraftTimestampKey, 0L)
            } else {
                lastInspection?.createdAt ?: 0L
            }
            if (originalTimestamp > 0L) "${inspectionNoteDatePrefix(originalTimestamp)}$loadedNotes" else loadedNotes
        }
    }

    var strength by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableIntStateOf(
            ColonyStrength.gradeFromStored(
                formDraft?.strength
                    ?: (if (rememberLastInspectionValues) lastInspection?.strength else null)
                    ?: ColonyStrength.storedFromGrade(defaultStartingStrength)
            )
        )
    }
    val initialBeeCountHalfSteps = run {
        val previousFrames = if (rememberLastInspectionValues) {
            lastInspection?.beeCountFrames ?: hive.beeCountFrames
        } else null
        (formDraft?.beeCountHalfSteps
            ?: previousFrames?.let { (it * 2.0).roundToInt() }
            ?: 1).coerceIn(1, 20)
    }
    val beeCountHistoryStartKey = remember(hive.id) { "bee_count_history_start_${hive.id}" }
    val savedBeeCountHistoryStart = formDraftPrefs.getInt(
        beeCountHistoryStartKey,
        initialBeeCountHalfSteps
    ).coerceIn(1, 20)
    var beeCountHalfSteps by rememberSaveable(hive.id, formStateKey) {
        mutableIntStateOf(initialBeeCountHalfSteps)
    }
    var beeCountHistoryStartHalfSteps by rememberSaveable(hive.id, formStateKey) {
        mutableIntStateOf(savedBeeCountHistoryStart)
    }
    // Hold the latest values in per-hive state objects so lifecycle/disposal callbacks
    // can finalize safely even if an NFC scan switches the displayed hive.
    val latestBeeCountForHistory = remember(hive.id, formStateKey) {
        mutableIntStateOf(beeCountHalfSteps)
    }
    val latestBeeCountStartForHistory = remember(hive.id, formStateKey) {
        mutableIntStateOf(beeCountHistoryStartHalfSteps)
    }
    val latestBeeCountRecorder = rememberUpdatedState(onRecordBeeCountChange)
    androidx.compose.runtime.SideEffect {
        latestBeeCountForHistory.intValue = beeCountHalfSteps
        latestBeeCountStartForHistory.intValue = beeCountHistoryStartHalfSteps
    }
    val finalizeBeeCountHistory = remember(hive.id, formStateKey) {
        {
            val startingValue = latestBeeCountStartForHistory.intValue
            val finalValue = latestBeeCountForHistory.intValue
            if (startingValue != finalValue) {
                // Move the baseline first so ON_STOP and onDispose cannot record it twice.
                latestBeeCountStartForHistory.intValue = finalValue
                beeCountHistoryStartHalfSteps = finalValue
                formDraftPrefs.edit {
                    putInt(beeCountHistoryStartKey, finalValue)
                }
                latestBeeCountRecorder.value(
                    hive.id,
                    startingValue / 2.0,
                    finalValue / 2.0
                )
            }
        }
    }
    var queen by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableStateOf(formDraft?.queenStatus ?: (if (rememberLastInspectionValues) lastInspection?.queenStatus else null) ?: hive.queenStatus)
    }
    var mites by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableIntStateOf(formDraft?.miteCount ?: (if (rememberLastInspectionValues) lastInspection?.miteCount else null) ?: 0)
    }
    var sample by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableIntStateOf(formDraft?.sampleSize ?: (if (rememberLastInspectionValues) lastInspection?.sampleSize else null) ?: defaultMiteSampleSize.coerceIn(1, 10000))
    }
    var notes by rememberSaveable(cleanDraftWorkspaceId, hive.id) { mutableStateOf(startingNotes) }
    var noteRevision by rememberSaveable(cleanDraftWorkspaceId, hive.id) {
        mutableLongStateOf(noteDraftPrefs.getLong(noteDraftUpdatedAtKey, 0L))
    }
    var needsCloudSave by rememberSaveable(cleanDraftWorkspaceId, hive.id) {
        mutableStateOf(legacyDraftNeedsSync)
    }
    var noteSaveStatus by rememberSaveable(cleanDraftWorkspaceId, hive.id) {
        mutableStateOf(
            when {
                legacyDraftNeedsSync -> "Restored local note - syncing to workspace"
                startingNotes.isBlank() -> ""
                hasSavedNoteDraft -> "Saved draft restored"
                else -> "Saved notes loaded"
            }
        )
    }
    var eggs by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableDoubleStateOf(normalizeBroodStoresGauge(formDraft?.eggs ?: (if (rememberLastInspectionValues) lastInspection?.eggs else null) ?: 0.0))
    }
    var openBrood by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableDoubleStateOf(normalizeBroodStoresGauge(formDraft?.openBrood ?: (if (rememberLastInspectionValues) lastInspection?.openBrood else null) ?: 0.0))
    }
    var cappedBrood by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableDoubleStateOf(normalizeBroodStoresGauge(formDraft?.cappedBrood ?: (if (rememberLastInspectionValues) lastInspection?.cappedBrood else null) ?: 0.0))
    }
    var honey by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableDoubleStateOf(normalizeBroodStoresGauge(formDraft?.honeyStores ?: (if (rememberLastInspectionValues) lastInspection?.honeyStores else null) ?: 0.0))
    }
    var pollen by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableDoubleStateOf(normalizeBroodStoresGauge(formDraft?.pollen ?: (if (rememberLastInspectionValues) lastInspection?.pollen else null) ?: 0.0))
    }
    var emptyComb by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableDoubleStateOf(normalizeBroodStoresGauge(formDraft?.emptyDrawnComb ?: (if (rememberLastInspectionValues) lastInspection?.emptyDrawnComb else null) ?: 0.0))
    }
    var emergency by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableIntStateOf(formDraft?.emergencyCells ?: (if (rememberLastInspectionValues) lastInspection?.emergencyCells else null) ?: 0)
    }
    var supercedure by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableIntStateOf(formDraft?.supercedureCells ?: (if (rememberLastInspectionValues) lastInspection?.supercedureCells else null) ?: 0)
    }
    var swarm by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableIntStateOf(formDraft?.swarmCells ?: (if (rememberLastInspectionValues) lastInspection?.swarmCells else null) ?: 0)
    }
    var diseasesCsv by rememberSaveable(hive.id, formStateKey, lastInspection?.id) {
        mutableStateOf(formDraft?.diseaseFlags ?: (if (rememberLastInspectionValues) lastInspection?.diseaseFlags else null).orEmpty())
    }
    var lat by rememberSaveable(hive.id, formStateKey) { mutableStateOf(formDraft?.latitude) }
    var lon by rememberSaveable(hive.id, formStateKey) { mutableStateOf(formDraft?.longitude) }
    var locationStatus by rememberSaveable(hive.id) { mutableStateOf("No GPS captured") }
    var pendingGpsHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var voiceStatus by rememberSaveable(hive.id) { mutableStateOf("") }
    var voiceListeningRequested by rememberSaveable(hive.id) { mutableStateOf(false) }
    var voicePartialText by rememberSaveable(hive.id) { mutableStateOf("") }
    var saving by rememberSaveable(hive.id) { mutableStateOf(false) }
    var saveError by rememberSaveable(hive.id) { mutableStateOf("") }
    // This mutable state object is captured directly by onDispose so a successful-save
    // transition is visible immediately, even if the parent removes this screen right away.
    val discardSavedFormDraftOnDispose = remember(hive.id) { mutableStateOf(false) }
    var showBeeCountHistory by rememberSaveable(hive.id) { mutableStateOf(false) }

    val currentFormDraft = InspectionFormDraft(
        strength = strength,
        queenStatus = queen,
        miteCount = mites,
        sampleSize = sample,
        eggs = eggs,
        openBrood = openBrood,
        cappedBrood = cappedBrood,
        honeyStores = honey,
        pollen = pollen,
        emptyDrawnComb = emptyComb,
        emergencyCells = emergency,
        supercedureCells = supercedure,
        swarmCells = swarm,
        diseaseFlags = diseasesCsv,
        updatedAt = formStateKey,
        latitude = lat,
        longitude = lon,
        beeCountHalfSteps = beeCountHalfSteps
    )
    // Keep a separate latest-draft state per hive. A single rememberUpdatedState
    // here was shared while the screen stayed open during NFC cycling, allowing the
    // next hive's values to be written under the previous hive's key on disposal.
    val latestFormDraft = remember(hive.id) { mutableStateOf(currentFormDraft) }
    androidx.compose.runtime.SideEffect {
        latestFormDraft.value = currentFormDraft
    }

    androidx.compose.runtime.LaunchedEffect(
        hive.id, strength, beeCountHalfSteps, queen, mites, sample, eggs, openBrood, cappedBrood,
        honey, pollen, emptyComb, emergency, supercedure, swarm, diseasesCsv, lat, lon
    ) {
        saveInspectionFormDraft(formDraftPrefs, hive.id, currentFormDraft)
    }
    androidx.compose.runtime.LaunchedEffect(hive.id, beeCountHistoryStartHalfSteps) {
        formDraftPrefs.edit {
            putInt(beeCountHistoryStartKey, beeCountHistoryStartHalfSteps)
        }
    }

    DisposableEffect(hive.id, formStateKey) {
        val hiveIdForDraft = hive.id
        val formDraftForThisHive = latestFormDraft
        val finalizeThisHiveBeeCount = finalizeBeeCountHistory
        onDispose {
            if (discardSavedFormDraftOnDispose.value) {
                formDraftPrefs.edit { remove("hive_${hiveIdForDraft}") }
            } else {
                saveInspectionFormDraft(formDraftPrefs, hiveIdForDraft, formDraftForThisHive.value)
            }
            finalizeThisHiveBeeCount()
        }
    }

    fun captureAndSaveHiveGps(targetHiveId: Long, latitude: Double, longitude: Double) {
        if (isReadOnlyWorkspace) {
            locationStatus = "View-only access"
            onViewOnlyAttempt()
            return
        }
        val coordinates = "${"%.5f".format(Locale.US, latitude)}, ${"%.5f".format(Locale.US, longitude)}"
        if (hive.id == targetHiveId) {
            lat = latitude
            lon = longitude
            locationStatus = "GPS captured • saving to hive…"
        }
        onSaveHiveLocation(targetHiveId, latitude, longitude) { error ->
            if (hive.id == targetHiveId) {
                locationStatus = if (error == null) {
                    "Hive GPS saved • $coordinates"
                } else {
                    "Inspection GPS captured; hive GPS save failed: $error"
                }
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val targetHiveId = pendingGpsHiveId ?: hive.id
        pendingGpsHiveId = null
        val granted = r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            if (hive.id == targetHiveId) locationStatus = "Location permission denied"
            return@rememberLauncherForActivityResult
        }
        locationController.current { loc ->
            if (loc != null) {
                captureAndSaveHiveGps(targetHiveId, loc.latitude, loc.longitude)
            } else if (hive.id == targetHiveId) {
                locationStatus = "Could not get a location"
            }
        }
    }
    val voiceHandler = remember { Handler(Looper.getMainLooper()) }
    // A short confirmation ping plays only once the recognizer is ready to listen.
    // This is shared by manual recording and NFC-triggered auto voice.
    val voiceStartTone = remember(context) {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()
    }
    DisposableEffect(voiceStartTone) {
        onDispose { runCatching { voiceStartTone?.release() } }
    }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else null
    }
    val speechIntent = remember(hive.id, hive.number) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak inspection notes for ${hiveReferenceName(hive.number)}")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }
    val latestNotes by rememberUpdatedState(notes)
    val keepVoiceListening by rememberUpdatedState(voiceListeningRequested)
    val voiceBlocked by rememberUpdatedState(saving)
    val appendRecognizedSpeech by rememberUpdatedState<(String) -> Unit>({ spokenText ->
        val cleanSpokenText = spokenText.trim()
        if (cleanSpokenText.isNotBlank()) {
            val recordedAt = System.currentTimeMillis()
            val datedSpokenText = "${inspectionNoteDatePrefix(recordedAt)}$cleanSpokenText"
            val updatedNotes = if (latestNotes.isBlank()) datedSpokenText else "${latestNotes.trimEnd()}\n$datedSpokenText"
            notes = updatedNotes
            val nextRevision = maxOf(
                System.currentTimeMillis(),
                noteRevision + 1L,
                noteDraftPrefs.getLong(noteDraftUpdatedAtKey, 0L) + 1L
            )
            noteRevision = nextRevision
            needsCloudSave = !isReadOnlyWorkspace
            noteDraftPrefs.edit {
                putString(noteDraftKey, updatedNotes)
                putLong(noteDraftUpdatedAtKey, nextRevision)
                putLong(noteDraftTimestampKey, recordedAt)
            }
            noteSaveStatus = if (isReadOnlyWorkspace) "View-only workspace" else "Saved locally"
            voicePartialText = ""
            voiceStatus = "Saved • continuing to listen"
        }
    })
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceStatus = "Starting hands-free dictation…"
            voiceListeningRequested = true
        } else {
            voiceListeningRequested = false
            voiceStatus = "Microphone permission denied • tap VOICE to try again"
        }
    }

    DisposableEffect(speechRecognizer) {
        val recognizer = speechRecognizer
        if (recognizer != null) {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (voiceReadyBeepEnabled) {
                        runCatching { voiceStartTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 140) }
                    }
                    voiceStatus = "Listening • speak your notes"
                }
                override fun onBeginningOfSpeech() {
                    voiceStatus = "Listening • capturing speech"
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    voiceStatus = "Processing speech…"
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        appendRecognizedSpeech(spoken)
                        voiceStatus = "Voice note saved • tap VOICE to record another"
                    } else {
                        voiceStatus = "No speech detected • tap VOICE to try again"
                    }
                    voicePartialText = ""
                    // One dictated phrase per activation. Leave the microphone stopped
                    // after the recognizer finishes and the note has been saved.
                    voiceListeningRequested = false
                    voiceHandler.removeCallbacksAndMessages(null)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    voicePartialText = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                }
                override fun onError(error: Int) {
                    // Do not automatically reopen the microphone after a timeout,
                    // silence, or recognition error. The beekeeper can explicitly retry.
                    voiceListeningRequested = false
                    voiceHandler.removeCallbacksAndMessages(null)
                    voicePartialText = ""
                    voiceStatus = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected • tap VOICE to try again"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service unavailable • tap VOICE to retry"
                        else -> "Voice dictation stopped • tap VOICE to try again"
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        onDispose {
            voiceHandler.removeCallbacksAndMessages(null)
            if (recognizer != null) {
                runCatching { recognizer.cancel() }
                recognizer.destroy()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(hive.id) {
        // Manual Inspect opens with the microphone off. Auto-start is reserved for
        // an explicit scan-to-open request and is consumed once for this hive.
        if (!autoStartVoice) return@LaunchedEffect
        onVoiceAutoStartConsumed()
        if (!autoVoiceEnabled) {
            voiceStatus = "Auto voice is off • tap VOICE to record manually"
            return@LaunchedEffect
        }
        // NFC entry announces the hive number with text-to-speech. Let that finish
        // before opening the microphone so the announcement is not dictated into notes.
        delay(1_800L)
        if (voiceListeningRequested) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceStatus = "Starting hands-free dictation…"
            voiceListeningRequested = true
        } else {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    androidx.compose.runtime.LaunchedEffect(voiceListeningRequested, saving, speechRecognizer) {
        val recognizer = speechRecognizer
        if (!voiceListeningRequested || saving) {
            voiceHandler.removeCallbacksAndMessages(null)
            runCatching { recognizer?.cancel() }
            if (!voiceListeningRequested && voiceStatus.startsWith("Listening")) voiceStatus = "Voice dictation paused"
            return@LaunchedEffect
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        if (recognizer == null) {
            voiceListeningRequested = false
            voiceStatus = "Speech recognition isn't available on this device"
            return@LaunchedEffect
        }
        runCatching { recognizer.startListening(speechIntent) }
            .onFailure {
                voiceListeningRequested = false
                voiceStatus = "Couldn't start voice dictation • tap VOICE to retry"
            }
    }

    // Do not leave the microphone active if the beekeeper backgrounds BeeKeep or locks the phone.
    val voiceLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(voiceLifecycleOwner, speechRecognizer, hive.id, formStateKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                finalizeBeeCountHistory()
            }
            if (event == Lifecycle.Event.ON_PAUSE && keepVoiceListening) {
                voiceListeningRequested = false
                voicePartialText = ""
                voiceHandler.removeCallbacksAndMessages(null)
                runCatching { speechRecognizer?.cancel() }
                voiceStatus = "Voice dictation paused while BeeKeep is in the background"
            }
        }
        voiceLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { voiceLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(
        hive.id, cleanDraftWorkspaceId, notes, noteRevision, needsCloudSave, isReadOnlyWorkspace
    ) {
        delay(400)
        val capturedRevision = noteRevision
        val capturedNotes = notes
        val recordedAt = noteDraftPrefs.getLong(noteDraftTimestampKey, 0L)
            .takeIf { it > 0L && capturedNotes.isNotBlank() }
        noteDraftPrefs.edit {
            putString(noteDraftKey, capturedNotes)
            putLong(noteDraftUpdatedAtKey, capturedRevision)
            if (recordedAt != null) putLong(noteDraftTimestampKey, recordedAt)
            else remove(noteDraftTimestampKey)
        }
        if (needsCloudSave && !isReadOnlyWorkspace) {
            try {
                val queuedRevision = onSaveNoteDraft(hive.id, capturedNotes, recordedAt, capturedRevision)
                if (noteRevision == capturedRevision) {
                    noteRevision = queuedRevision
                    needsCloudSave = false
                    noteSaveStatus = "Saved locally - cloud sync queued"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                noteSaveStatus = "Saved locally - sync pending"
            }
        } else if (capturedNotes.isNotBlank() && noteSaveStatus.isBlank()) {
            noteSaveStatus = if (isReadOnlyWorkspace) "View-only workspace" else "Saved on device"
        }
    }

    BackHandler { onBack() }

    val miteRate = if (sample > 0) mites * 100.0 / sample else 0.0
    // A new inspection has not been added to history yet; compare against the
    // latest saved inspection, not the second-latest one.
    val previousForComparison = remember(priorInspections, hive.id) {
        priorInspections.asSequence()
            .filter { it.hiveId == hive.id }
            .maxByOrNull { it.createdAt }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(hiveDisplayName(hive.number), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("FIELD INSPECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    IconButton(onClick = onHome) {
                        Icon(Icons.Rounded.Home, contentDescription = "Home")
                    }
                }
            }
        },

    ) { padding ->
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            InspectionHoneycombBackground(Modifier.matchParentSize())
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FieldActionButton(
                                Icons.Rounded.Mic,
                                if (voiceListeningRequested) "STOP VOICE" else "VOICE",
                                Modifier.weight(1f)
                            ) {
                                if (voiceListeningRequested) {
                                    voiceListeningRequested = false
                                    voicePartialText = ""
                                    voiceStatus = "Voice dictation paused"
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    voiceListeningRequested = true
                                    voiceStatus = "Starting hands-free dictation…"
                                } else {
                                    voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldActionButton(Icons.Rounded.GpsFixed, "GPS", Modifier.weight(1f)) {
                                val targetHiveId = hive.id
                                if (locationController.hasPermission()) {
                                    locationController.current { loc ->
                                        if (loc != null) {
                                            captureAndSaveHiveGps(targetHiveId, loc.latitude, loc.longitude)
                                        } else if (hive.id == targetHiveId) {
                                            locationStatus = "Could not get a location"
                                        }
                                    }
                                } else {
                                    pendingGpsHiveId = targetHiveId
                                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            }
                            Button(
                                onClick = {
                                    if (isReadOnlyWorkspace) {
                                        saveError = ""
                                        onViewOnlyAttempt()
                                    } else if (!saving) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        saving = true
                                        saveError = ""
                                        val now = System.currentTimeMillis()
                                        val inspection = Inspection(
                                            IdGenerator.nextLong(), hive.id, now, ColonyStrength.storedFromGrade(strength), queen, mites,
                                            sample.coerceAtLeast(1), notes.trim(), lat, lon,
                                            emergency, supercedure, swarm, eggs, openBrood, cappedBrood,
                                            honey, pollen, emptyComb, diseasesCsv,
                                            beeCountFrames = beeCountHalfSteps / 2.0
                                        )
                                        scope.launch {
                                            try {
                                                val saved = onSave(inspection)
                                                if (saved) {
                                                    // Clear only after durable save succeeds; keep the independent note draft.
                                                    discardSavedFormDraftOnDispose.value = true
                                                    formDraftPrefs.edit { remove("hive_${hive.id}") }
                                                    onSaved()
                                                } else if (isReadOnlyWorkspace) {
                                                    onViewOnlyAttempt()
                                                } else {
                                                    saveError = "Could not save inspection. Your field screen is still open; try again."
                                                }
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                if (isReadOnlyWorkspace || error.message?.contains("view-only", ignoreCase = true) == true) {
                                                    onViewOnlyAttempt()
                                                } else {
                                                    saveError = error.message ?: "Could not save the inspection. Please try again."
                                                }
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }
                                },
                                enabled = !saving,
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD97706),
                                    contentColor = Color.White,
                                    disabledContainerColor = Color(0xFFD97706).copy(alpha = 0.45f),
                                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Check, null)
                                Spacer(Modifier.width(5.dp))
                                Text(if (saving) "SAVING…" else "SAVE", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                        }
                    }
                }
            }

            if (saveError.isNotBlank()) {
                item {
                    Text(
                        saveError,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "FIELD NOTES",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = {
                                if (!isReadOnlyWorkspace) {
                                    val updatedNotes = addDateToNewInspectionNoteLines(notes, it)
                                    notes = updatedNotes
                                    val nextRevision = maxOf(
                                        System.currentTimeMillis(),
                                        noteRevision + 1L,
                                        noteDraftPrefs.getLong(noteDraftUpdatedAtKey, 0L) + 1L
                                    )
                                    noteRevision = nextRevision
                                    needsCloudSave = true
                                    noteDraftPrefs.edit {
                                        putString(noteDraftKey, updatedNotes)
                                        putLong(noteDraftUpdatedAtKey, nextRevision)
                                        if (updatedNotes.isBlank()) {
                                            remove(noteDraftTimestampKey)
                                        } else if (!noteDraftPrefs.contains(noteDraftTimestampKey)) {
                                            putLong(noteDraftTimestampKey, System.currentTimeMillis())
                                        }
                                    }
                                    noteSaveStatus = "Saving note..."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(124.dp),
                            enabled = !isReadOnlyWorkspace,
                            label = { Text("What did you see?") }
                        )
                        if (noteSaveStatus.isNotBlank()) {
                            Text(
                                noteSaveStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (voicePartialText.isNotBlank()) {
                            Text(
                                "Hearing: $voicePartialText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (voiceStatus.isNotBlank()) Text(voiceStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (locationStatus != "No GPS captured") Text(locationStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                ComparisonCounter(
                    "Colony strength",
                    strength,
                    previousForComparison?.strength?.let { ColonyStrength.gradeFromStored(it) },
                    1..5,
                    haptic,
                    valueLabel = ColonyStrength.labelForGrade(strength)
                ) { strength = it }
            }
            item {
                ComparisonCounter(
                    label = "Bee Count",
                    value = beeCountHalfSteps,
                    previous = previousForComparison?.beeCountFrames?.let { (it * 2.0).roundToInt().coerceIn(1, 20) },
                    range = 1..20,
                    haptic = haptic,
                    valueLabel = "${formatBeeCountFrames(beeCountHalfSteps / 2.0)} Frames",
                    previousValueLabel = previousForComparison?.beeCountFrames?.let { "${formatBeeCountFrames(it)} Frames" }
                ) { updatedValue ->
                    if (updatedValue != beeCountHalfSteps) {
                        beeCountHalfSteps = updatedValue
                        latestBeeCountForHistory.intValue = updatedValue
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { showBeeCountHistory = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("HISTORY", fontWeight = FontWeight.ExtraBold)
                    if (beeCountHistory.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text("(${beeCountHistory.size})", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("Mite wash", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${mites} / ${sample} bees", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(String.format(Locale.US, "%.2f%%", miteRate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumberField("Mites", mites, Modifier.weight(1f)) { mites = it.coerceIn(0, sample) }
                            NumberField("Sample", sample, Modifier.weight(1f)) {
                                sample = it.coerceAtLeast(1)
                                mites = mites.coerceAtMost(sample)
                            }
                        }
                        val priorMite = previousForComparison?.mitePercent
                        if (priorMite != null) {
                            Text(
                                "Previous ${String.format(Locale.US, "%.2f%%", priorMite)} • Change ${String.format(Locale.US, "%+.2f%%", miteRate - priorMite)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (miteRate > priorMite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("BROOD & STORES")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactHalfStepCounter("Eggs", eggs, 0.0..10.0, haptic, Modifier.weight(1f)) { eggs = it }
                    CompactHalfStepCounter("Open", openBrood, 0.0..10.0, haptic, Modifier.weight(1f)) { openBrood = it }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactHalfStepCounter("Capped", cappedBrood, 0.0..10.0, haptic, Modifier.weight(1f)) { cappedBrood = it }
                    CompactHalfStepCounter("Honey", honey, 0.0..10.0, haptic, Modifier.weight(1f)) { honey = it }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactHalfStepCounter("Pollen", pollen, 0.0..10.0, haptic, Modifier.weight(1f)) { pollen = it }
                    CompactHalfStepCounter("Empty comb", emptyComb, 0.0..10.0, haptic, Modifier.weight(1f)) { emptyComb = it }
                }
            }

            item {
                SectionHeader("QUEEN STATUS")
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Queen condition",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Choose the best match for this inspection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (option in listOf("Laying", "Queenless", "Virgin")) {
                                FilterChip(
                                    selected = queen == option,
                                    onClick = {
                                        queen = option
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(option, maxLines = 1, softWrap = false) },
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedContainerColor = Color(0xFFD97706),
                                        selectedLabelColor = Color.White
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (queen == option) Color(0xFFD97706) else MaterialTheme.colorScheme.outline
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier.fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Queen cells observed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Count each type seen. Leave a count at zero when none are present.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        CompactCounter(
                            "Emergency cells",
                            emergency,
                            0..20,
                            haptic,
                            Modifier.fillMaxWidth()
                        ) { emergency = it }
                        CompactCounter(
                            "Supersedure cells",
                            supercedure,
                            0..20,
                            haptic,
                            Modifier.fillMaxWidth()
                        ) { supercedure = it }
                        CompactCounter(
                            "Swarm cells",
                            swarm,
                            0..20,
                            haptic,
                            Modifier.fillMaxWidth()
                        ) { swarm = it }
                    }
                }
            }

            item {
                SectionHeader("HEALTH FLAGS")
                val activeDiseases = remember(diseasesCsv) { diseasesCsv.split(",").map(String::trim).filter(String::isNotBlank).toSet() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (flag in listOf("AFB", "EFB", "Chalkbrood", "Small Hive Beetle", "Wax Moth", "Nosema")) {
                        val active = activeDiseases.contains(flag)
                        FilterChip(
                            selected = active,
                            onClick = {
                                val current = activeDiseases.toMutableSet()
                                if (active) current.remove(flag) else current.add(flag)
                                diseasesCsv = current.joinToString(", ")
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            },
                            label = { Text(flag) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = Color(0xFFD97706),
                                selectedLabelColor = Color.White
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (active) Color(0xFFD97706) else MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }
            }





            }
            if (showBeeCountHistory) {
                AlertDialog(
                    onDismissRequest = { showBeeCountHistory = false },
                    title = { Text("Bee Count History", fontWeight = FontWeight.ExtraBold) },
                    text = {
                        if (beeCountHistory.isEmpty()) {
                            Text("No bee-count changes have been recorded for this hive yet.")
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Every change is recorded with its date and time.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                beeCountHistory
                                    .sortedWith(compareByDescending<ActivityEvent> { it.createdAt }.thenByDescending { it.id })
                                    .forEach { entry ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                                    .format(Date(entry.createdAt)),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(entry.detail, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBeeCountHistory = false }) {
                            Text("CLOSE", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FieldActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    ) {
        Icon(icon, contentDescription = label)
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

@Composable
private fun ComparisonCounter(
    label: String,
    value: Int,
    previous: Int?,
    range: IntRange,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    valueLabel: String? = null,
    previousValueLabel: String? = null,
    onChange: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                previous?.let {
                    Text(
                        "Prev: ${previousValueLabel ?: ColonyStrength.labelForGrade(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value - 1).coerceIn(range)) },
                    modifier = Modifier.size(36.dp)
                ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        valueLabel ?: value.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value + 1).coerceIn(range)) },
                    modifier = Modifier.size(36.dp)
                ) { Text("+", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun CompactCounter(
    label: String,
    value: Int,
    range: IntRange,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value - 1).coerceIn(range)) },
                modifier = Modifier.size(32.dp)
            ) { Text("−", style = MaterialTheme.typography.bodyMedium) }
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value + 1).coerceIn(range)) },
                modifier = Modifier.size(32.dp)
            ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CompactHalfStepCounter(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onChange(normalizeBroodStoresGauge(value - 0.5).coerceIn(range))
                },
                enabled = value > range.start,
                modifier = Modifier.size(32.dp)
            ) { Text("−", style = MaterialTheme.typography.bodyMedium) }
            Text(
                formatBroodStoresGauge(value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onChange(normalizeBroodStoresGauge(value + 0.5).coerceIn(range))
                },
                enabled = value < range.endInclusive,
                modifier = Modifier.size(32.dp)
            ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
internal fun Counter(
    label: String,
    value: Int,
    range: IntRange,
    valueLabel: String? = null,
    onChange: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ onChange((value - 1).coerceIn(range)) }) {
                Text("−", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueLabel ?: value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton({ onChange((value + 1).coerceIn(range)) }) {
                Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    mod: Modifier,
    onChange: (Int) -> Unit
) {
    // Preserve intermediate typing so the field can be cleared before entering
    // zero or a replacement number. An empty value is treated as zero rather
    // than leaving the previous number silently stored behind the blank field.
    var inputText by remember(label) { mutableStateOf(value.toString()) }
    var isFocused by remember(label) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(value, isFocused) {
        // Don't overwrite a blank editing buffer while the user is typing.
        // Once focus leaves, show the actual normalized value again.
        if (!isFocused && inputText != value.toString()) {
            inputText = value.toString()
        }
    }

    OutlinedTextField(
        value = inputText,
        onValueChange = { entered ->
            val digits = entered.filter(Char::isDigit)
            inputText = digits
            onChange(digits.toIntOrNull() ?: 0)
        },
        modifier = mod.onFocusChanged { isFocused = it.isFocused },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}