package com.beekeep.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beekeep.app.data.Hive
import com.beekeep.app.nfc.BeeKeepNfcPayload
import com.beekeep.app.nfc.NfcController
import com.beekeep.app.nfc.NfcResult
import com.beekeep.app.nfc.playNfcAssignmentSuccessFeedback
import kotlinx.coroutines.delay

/**
 * Dedicated hands-free assignment UI used after the first hive is tagged.
 * It writes and saves each remaining hive directly, without opening HiveDetailScreen.
 */
@Composable
internal fun FastTagAssignmentScreen(
    activity: ComponentActivity,
    hive: Hive,
    nfc: NfcController,
    ignoredUid: String?,
    isTagAssignedElsewhere: (Long, String) -> Boolean,
    checkHiveSkipReason: suspend (Long) -> String?,
    onHiveSkipped: (Long, String) -> Unit,
    onAssignTag: (Long, String, (Boolean, String?) -> Unit) -> Unit,
    onAssignmentSaved: (String) -> Unit,
    onCancel: () -> Unit
) {
    var status by remember(hive.id) { mutableStateOf("Preparing the NFC reader…") }
    var writeStage by remember(hive.id) { mutableStateOf("preparing") }
    var retryCount by remember(hive.id) { mutableIntStateOf(0) }
    var canRetry by remember(hive.id) { mutableStateOf(false) }
    var failureTitle by remember(hive.id) { mutableStateOf("⚠ TAG NOT ASSIGNED") }
    // Keep an error latched across NFC-triggered Activity pause/resume events.
    // Otherwise the reader's resume effect can reset a warning before the user sees it.
    var failureSticky by remember(hive.id) { mutableStateOf(false) }
    var showAssignedTagDialog by remember(hive.id) { mutableStateOf(false) }
    var resumeGeneration by remember(hive.id) { mutableIntStateOf(0) }
    var skipInProgress by remember(hive.id) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // NFC callbacks can outlive a recomposition. Keep the writer connected to the
    // latest hive list/assignment callbacks instead of an old captured snapshot.
    val latestIsTagAssignedElsewhere by rememberUpdatedState(isTagAssignedElsewhere)
    val latestCheckHiveSkipReason by rememberUpdatedState(checkHiveSkipReason)
    val latestOnHiveSkipped by rememberUpdatedState(onHiveSkipped)
    val latestOnAssignTag by rememberUpdatedState(onAssignTag)
    val latestOnAssignmentSaved by rememberUpdatedState(onAssignmentSaved)

    // NFC ReaderMode is cancelled when Android pauses the activity. If the tag's
    // NDEF launch briefly interrupts BeeKeep, re-arm the writer after we resume.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, hive.id) {
        var initialResumeSeen = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (initialResumeSeen) resumeGeneration++ else initialResumeSeen = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { onCancel() }

    LaunchedEffect(hive.id, ignoredUid, retryCount, resumeGeneration) {
        // On resume, preserve failures instead of silently re-arming and clearing them.
        // Only an explicit Retry / Scan Another Tag action may reset this state.
        if (skipInProgress || failureSticky) return@LaunchedEffect
        canRetry = false
        failureTitle = "⚠ TAG NOT ASSIGNED"
        writeStage = "preparing"
        status = "Checking that this hive still needs an NFC tag…"

        suspend fun verifyTargetHive(): Boolean {
            val check = runCatching { latestCheckHiveSkipReason(hive.id) }
            if (check.isFailure) {
                writeStage = "check_error"
                canRetry = true
                failureSticky = true
                status = "Could not verify this hive's current NFC assignment. No tag was written. Retry the check."
                return false
            }
            val skipReason = check.getOrNull()
            if (skipReason != null) {
                skipInProgress = true
                writeStage = "error"
                canRetry = false
                status = "Skipped ${hiveReferenceName(hive.number)}: $skipReason."
                latestOnHiveSkipped(hive.id, skipReason)
                return false
            }
            return true
        }

        if (!verifyTargetHive()) return@LaunchedEffect
        status = if (ignoredUid.isNullOrBlank()) {
            "Preparing the NFC reader…"
        } else {
            "Remove the previous tag, then present a new unassigned tag."
        }
        delay(900L)

        // A tag may have been assigned while the reader handoff delay was running.
        // Recheck immediately before starting the physical NFC writer.
        if (!verifyTargetHive()) return@LaunchedEffect

        writeStage = "waiting"
        status = "Ready. Hold an unassigned NFC tag against the back of your phone."
        nfc.beginFastAssignmentFlow(activity)
        nfc.startWrite(
            activity = activity,
            text = BeeKeepNfcPayload.TAG_PAYLOAD,
            targetHiveId = hive.id,
            ignoredUid = ignoredUid,
            isAssignedElsewhere = { candidateUid -> latestIsTagAssignedElsewhere(hive.id, candidateUid) },
            onResult = { result ->
                when (result) {
                    is NfcResult.Written -> {
                        writeStage = "saving"
                        status = "Tag written. Saving the hive assignment…"
                        latestOnAssignTag(hive.id, result.uid) { success, error ->
                            if (success) {
                                nfc.rememberHandledUid(result.uid)
                                nfc.suppressFastAssignmentUid(result.uid)
                                playNfcAssignmentSuccessFeedback(activity)
                                writeStage = "done"
                                status = "Saved. Moving to the next untagged hive…"
                                latestOnAssignmentSaved(result.uid)
                            } else {
                                writeStage = "error"
                                canRetry = true
                                failureSticky = true
                                failureTitle = "⚠ TAG NOT ASSIGNED"
                                status = "TAG NOT ASSIGNED to ${hiveReferenceName(hive.number)}. The physical tag may have been written, but BeeKeep did not save the hive assignment. ${error ?: "Retry the assignment or use another unassigned tag."}"
                            }
                        }
                    }
                    is NfcResult.Read -> {
                        writeStage = "error"
                        canRetry = true
                        failureSticky = true
                        val tagAlreadyAssigned = latestIsTagAssignedElsewhere(hive.id, result.uid)
                        showAssignedTagDialog = tagAlreadyAssigned
                        failureTitle = if (tagAlreadyAssigned) {
                            "⚠ TAG ALREADY ASSIGNED"
                        } else {
                            "⚠ TAG NOT ASSIGNED"
                        }
                        status = if (tagAlreadyAssigned) {
                            "This tag is already assigned to another hive. Nothing was written or changed. Remove it and present an unassigned tag."
                        } else {
                            "The tag was detected but not written. Try a writable, unassigned NFC tag."
                        }
                    }
                    is NfcResult.Error -> {
                        writeStage = "error"
                        canRetry = true
                        failureSticky = true
                        val tagAlreadyAssigned =
                            result.message.contains("assigned to another hive", ignoreCase = true) ||
                                result.message.contains("already assigned", ignoreCase = true) ||
                                result.message.contains("linked to multiple hive records", ignoreCase = true)
                        failureTitle = if (tagAlreadyAssigned) {
                            "⚠ TAG ALREADY ASSIGNED"
                        } else {
                            "⚠ TAG NOT ASSIGNED"
                        }
                        showAssignedTagDialog = tagAlreadyAssigned
                        status = if (!tagAlreadyAssigned) {
                            result.message
                        } else if (result.message.contains("multiple hive records", ignoreCase = true)) {
                            "This NFC tag has conflicting hive assignments. Nothing was written. Repair the assignment in NFC Tag Management before using it."
                        } else {
                            "This NFC tag is already assigned to another hive. Nothing was written. Remove it and present an unassigned tag."
                        }
                    }
                }
            }
        )
    }

    if (showAssignedTagDialog) {
        AlertDialog(
            onDismissRequest = { /* Keep the warning visible until the user chooses another tag. */ },
            title = {
                Text("⚠ TAG ALREADY ASSIGNED", fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Text(
                    "This physical NFC tag is already assigned to another hive. BeeKeep did not overwrite it or change any hive assignment. Remove it, then scan a different, unassigned tag for ${hiveReferenceName(hive.number)}."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAssignedTagDialog = false
                        failureSticky = false
                        retryCount++
                    }
                ) {
                    Text("SCAN ANOTHER TAG", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(onClick = onCancel) {
                IconBack()
                Text("Stop")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Fast Tag Assignment",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Hands-free hive-to-hive tagging",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "CURRENT HIVE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    hiveReferenceName(hive.number),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    hive.apiary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Present one unassigned tag. BeeKeep will save it and move to the next untagged hive automatically.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (writeStage == "preparing" || writeStage == "waiting" || writeStage == "saving") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(62.dp),
                        strokeWidth = 5.dp
                    )
                } else {
                    androidx.compose.material3.Icon(
                        imageVector = if (writeStage == "done") Icons.Rounded.TaskAlt else Icons.Rounded.Nfc,
                        contentDescription = null,
                        tint = if (writeStage == "error" || writeStage == "check_error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(62.dp)
                    )
                }
                if (writeStage == "error") {
                    Text(
                        failureTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else if (writeStage == "check_error") {
                    Text(
                        "⚠ HIVE CHECK FAILED",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    status,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = if (writeStage == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (canRetry) {
                    Button(onClick = {
                        showAssignedTagDialog = false
                        failureSticky = false
                        retryCount++
                    }) {
                        Text(
                            when {
                                writeStage == "check_error" -> "RETRY HIVE CHECK"
                                failureTitle.contains("TAG ALREADY ASSIGNED") -> "SCAN ANOTHER TAG"
                                else -> "RETRY TAG WRITE"
                            }
                        )
                    }
                }
            }
        }

        Text(
            "No hive details or Inspection page will open during this sequence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun IconBack() {
    androidx.compose.material3.Icon(
        imageVector = Icons.Rounded.ArrowBack,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}
