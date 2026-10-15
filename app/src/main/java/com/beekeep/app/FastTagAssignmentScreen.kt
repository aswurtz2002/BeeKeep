package com.beekeep.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    onAssignTag: (Long, String, (Boolean, String?) -> Unit) -> Unit,
    onAssignmentSaved: (String) -> Unit,
    onCancel: () -> Unit
) {
    var status by remember(hive.id) { mutableStateOf("Preparing the NFC reader…") }
    var writeStage by remember(hive.id) { mutableStateOf("preparing") }
    var retryCount by remember(hive.id) { mutableIntStateOf(0) }
    var canRetry by remember(hive.id) { mutableStateOf(false) }

    BackHandler { onCancel() }

    LaunchedEffect(hive.id, ignoredUid, retryCount) {
        canRetry = false
        writeStage = "preparing"
        status = if (ignoredUid.isNullOrBlank()) {
            "Preparing the NFC reader…"
        } else {
            "Remove the previous tag, then present a new unassigned tag."
        }
        delay(900L)
        writeStage = "waiting"
        status = "Ready. Hold an unassigned NFC tag against the back of your phone."
        nfc.beginFastAssignmentFlow(activity)
        nfc.startWrite(
            activity = activity,
            text = BeeKeepNfcPayload.TAG_PAYLOAD,
            targetHiveId = hive.id,
            ignoredUid = ignoredUid,
            isAssignedElsewhere = { candidateUid -> isTagAssignedElsewhere(hive.id, candidateUid) },
            onResult = { result ->
                when (result) {
                    is NfcResult.Written -> {
                        writeStage = "saving"
                        status = "Tag written. Saving the hive assignment…"
                        onAssignTag(hive.id, result.uid) { success, error ->
                            if (success) {
                                nfc.rememberHandledUid(result.uid)
                                nfc.suppressFastAssignmentUid(result.uid)
                                playNfcAssignmentSuccessFeedback(activity)
                                writeStage = "done"
                                status = "Saved. Moving to the next untagged hive…"
                                onAssignmentSaved(result.uid)
                            } else {
                                writeStage = "error"
                                canRetry = true
                                status = "The tag was written, but BeeKeep could not save the assignment. ${error ?: "Retry with this tag or another unassigned tag."}"
                            }
                        }
                    }
                    is NfcResult.Read -> {
                        writeStage = "error"
                        canRetry = true
                        status = if (isTagAssignedElsewhere(hive.id, result.uid)) {
                            "This tag is already assigned to another hive. Nothing was changed. Remove it and present an unassigned tag."
                        } else {
                            "The tag was detected but not written. Try a writable, unassigned NFC tag."
                        }
                    }
                    is NfcResult.Error -> {
                        writeStage = "error"
                        canRetry = true
                        status = result.message
                    }
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(62.dp)
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
                    Button(onClick = { retryCount++ }) { Text("RETRY TAG WRITE") }
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
