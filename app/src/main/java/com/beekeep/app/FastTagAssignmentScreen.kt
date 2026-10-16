package com.beekeep.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
    var autoRearmCount by remember(hive.id) { mutableIntStateOf(0) }
    var rejectedUid by remember(hive.id) { mutableStateOf<String?>(null) }
    var failureTitle by remember(hive.id) { mutableStateOf("⚠ TAG NOT ASSIGNED") }
    // Keep an error latched across NFC-triggered Activity pause/resume events.
    // Otherwise the reader's resume effect can reset a warning before the user sees it.
    var failureSticky by remember(hive.id) { mutableStateOf(false) }
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
    val latestRejectedUid by rememberUpdatedState(rejectedUid)

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
        // On resume, preserve failures long enough for the beekeeper to see them.
        // Duplicate-tag warnings are cleared by the automatic re-arm effect below.
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
        delay(if (retryCount == 0) 900L else 350L)

        // A tag may have been assigned while the reader handoff delay was running.
        // Recheck immediately before starting the physical NFC writer.
        if (!verifyTargetHive()) return@LaunchedEffect

        writeStage = "waiting"
        // Keep the scanning instruction visible for the entire wait. It should not
        // disappear after the short NFC handoff delay; the next result replaces it.
        status = if (ignoredUid.isNullOrBlank()) {
            "Hold the back of your phone near an unassigned NFC tag."
        } else {
            "Remove the previous tag, then hold the back of your phone near a new, unassigned tag."
        }
        nfc.beginFastAssignmentFlow(activity)
        nfc.startWrite(
            activity = activity,
            text = BeeKeepNfcPayload.TAG_PAYLOAD,
            targetHiveId = hive.id,
            ignoredUid = latestRejectedUid ?: ignoredUid,
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
                        failureSticky = true
                        val tagAlreadyAssigned = latestIsTagAssignedElsewhere(hive.id, result.uid)
                        failureTitle = if (tagAlreadyAssigned) {
                            rejectedUid = result.uid
                            canRetry = false
                            autoRearmCount++
                            "⚠ TAG ALREADY ASSIGNED"
                        } else {
                            canRetry = true
                            "⚠ TAG NOT ASSIGNED"
                        }
                        status = if (tagAlreadyAssigned) {
                            "This tag is already assigned to another hive. Nothing was written or changed. Remove it and present a new, unassigned tag."
                        } else {
                            "The tag was detected but not written. Try a writable, unassigned NFC tag."
                        }
                    }
                    is NfcResult.Error -> {
                        writeStage = "error"
                        failureSticky = true
                        val tagAlreadyAssigned =
                            result.message.contains("assigned to another hive", ignoreCase = true) ||
                                result.message.contains("already assigned", ignoreCase = true) ||
                                result.message.contains("linked to multiple hive records", ignoreCase = true)
                        failureTitle = if (tagAlreadyAssigned) {
                            if (!result.uid.isNullOrBlank()) {
                                rejectedUid = result.uid
                                canRetry = false
                                autoRearmCount++
                            } else {
                                // If the reader could not identify the rejected UID, keep manual recovery
                                // available rather than risking an immediate repeat of the same tag.
                                canRetry = true
                            }
                            "⚠ TAG ALREADY ASSIGNED"
                        } else {
                            canRetry = true
                            "⚠ TAG NOT ASSIGNED"
                        }
                        status = if (!tagAlreadyAssigned) {
                            result.message
                        } else if (result.message.contains("multiple hive records", ignoreCase = true)) {
                            "This NFC tag has conflicting hive assignments. Nothing was written. Remove it and present a new tag; repair the conflict in NFC Tag Management before assigning it."
                        } else {
                            "This NFC tag is already assigned to another hive. Nothing was written. Remove it and present a new, unassigned tag."
                        }
                    }
                }
            }
        )
    }

    // A rejected tag should show its warning briefly, then return to scanning by itself.
    // The UID stays blocked, so leaving the same tag against the phone cannot loop the warning.
    LaunchedEffect(hive.id, autoRearmCount) {
        if (autoRearmCount == 0) return@LaunchedEffect
        delay(800L)
        if (!skipInProgress) {
            failureSticky = false
            canRetry = false
            retryCount++
        }
    }

    val isFailure = writeStage == "error" || writeStage == "check_error"
    val isActive = writeStage == "preparing" || writeStage == "waiting" || writeStage == "saving"
    val statusContainer = when {
        isFailure -> MaterialTheme.colorScheme.errorContainer
        writeStage == "done" -> MaterialTheme.colorScheme.primaryContainer
        writeStage == "waiting" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusContent = when {
        isFailure -> MaterialTheme.colorScheme.onErrorContainer
        writeStage == "done" -> MaterialTheme.colorScheme.onPrimaryContainer
        writeStage == "waiting" -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusHeading = when (writeStage) {
        "preparing" -> "CHECKING TARGET HIVE"
        "waiting" -> "READY FOR TAG"
        "saving" -> "SAVING ASSIGNMENT"
        "done" -> "ASSIGNMENT SAVED"
        "check_error" -> "⚠ HIVE CHECK FAILED"
        "error" -> failureTitle
        else -> "FAST TAG ASSIGNMENT"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                IconBack()
                Text("Stop")
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "HIVE TO TAG",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    hiveReferenceName(hive.number),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Rounded.Nfc,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        hive.apiary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = statusContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isActive) {
                    HoneycombNfcIndicator(
                        nfcEnabled = nfc.isEnabled(),
                        size = 112.dp
                    )
                } else {
                    androidx.compose.material3.Icon(
                        imageVector = if (writeStage == "done") {
                            Icons.Rounded.TaskAlt
                        } else {
                            Icons.Rounded.Nfc
                        },
                        contentDescription = null,
                        tint = statusContent,
                        modifier = Modifier.size(56.dp)
                    )
                }

                Text(
                    statusHeading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = statusContent,
                    textAlign = TextAlign.Center
                )

                Text(
                    status,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (writeStage == "waiting" || isFailure) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Medium
                    },
                    textAlign = TextAlign.Center,
                    color = statusContent
                )

                if (canRetry) {
                    Button(
                        onClick = {
                            failureSticky = false
                            retryCount++
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            when {
                                writeStage == "check_error" -> "RETRY HIVE CHECK"
                                failureTitle.contains("TAG ALREADY ASSIGNED") -> "SCAN ANOTHER TAG"
                                else -> "RETRY TAG WRITE"
                            },
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        Text(
            "Use a writable, unassigned tag. Keep it near the back of your phone until BeeKeep confirms the assignment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
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
