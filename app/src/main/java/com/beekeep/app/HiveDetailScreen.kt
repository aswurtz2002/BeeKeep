package com.beekeep.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import com.beekeep.app.data.ActivityEvent
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import com.beekeep.app.data.Treatment
import com.beekeep.app.nfc.BeeKeepNfcPayload
import com.beekeep.app.nfc.NfcController
import com.beekeep.app.nfc.NfcResult
import com.beekeep.app.nfc.playNfcAssignmentSuccessFeedback
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun CompactStatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
    }
}

@Composable
internal fun HiveDetailScreen(
    hive: Hive,
    inspections: List<Inspection>,
    events: List<ActivityEvent>,
    feedings: List<com.beekeep.app.data.Feeding>,
    treatments: List<Treatment>,
    harvests: List<Harvest>,
    nfc: NfcController,
    activity: ComponentActivity,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onInspect: () -> Unit,
    onFeed: () -> Unit,
    onTreat: () -> Unit,
    onHarvest: () -> Unit,
    onTag: (String, (Boolean, String?) -> Unit) -> Unit,
    onClearTag: (Long, (Boolean, String?) -> Unit) -> Unit,
    confirmBeforeTagRemoval: Boolean = true,
    tagAssignmentSuccessUid: String? = null,
    nextHiveNumber: String? = null,
    onNextHive: () -> Unit = {},
    fastTagAssignmentMode: Boolean = false,
    autoStartTagWriting: Boolean = false,
    autoStartTagWritingIgnoreUid: String? = null,
    onAutoTagWritingStarted: () -> Unit = {},
    onFastTagAssignmentSuccess: (String) -> Unit = {},
    onVerifyTag: () -> Unit,
    onMarkDead: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    var writeStatus by rememberSaveable(hive.id) { mutableStateOf("") }
    var writeStatusType by rememberSaveable(hive.id) { mutableStateOf("idle") }
    var removingTag by rememberSaveable(hive.id) { mutableStateOf(false) }
    var showRecentActivity by rememberSaveable(hive.id) { mutableStateOf(false) }
    var confirmDead by rememberSaveable(hive.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(hive.id) { mutableStateOf(false) }
    var confirmRemoveTag by rememberSaveable(hive.id) { mutableStateOf(false) }
    BackHandler { onBack() }

    fun removeHiveTag() {
        removingTag = true
        writeStatus = "Removing NFC tag…"
        writeStatusType = "removing"
        onClearTag(hive.id) { success, error ->
            removingTag = false
            writeStatusType = if (success) "success" else "error"
            writeStatus = if (success) "NFC tag removed • available for reuse" else (error ?: "Could not remove the NFC tag.")
        }
    }

    fun startTagWriting(ignoredUid: String? = null) {
        if (hive.isDead || writeStatusType == "saving" || writeStatusType == "removing") return
        writeStatusType = "waiting"
        writeStatus = "Ready to scan. Hold your NFC tag against the back of your phone and keep it still."
        nfc.startWrite(
            activity = activity,
            text = BeeKeepNfcPayload.TAG_PAYLOAD,
            allowOverwriteOtherHive = hive.tagUid != null,
            ignoredUid = ignoredUid,
            onResult = { result ->
                when (result) {
                    is NfcResult.Written -> {
                        writeStatusType = "saving"
                        writeStatus = "Tag written. Saving its assignment to this hive…"
                        onTag(result.uid) { success, error ->
                            writeStatusType = if (success) "success" else "error"
                            if (success) playNfcAssignmentSuccessFeedback(activity)
                            writeStatus = if (success) {
                                "NFC tag successfully assigned • ${result.uid}"
                            } else {
                                error ?: "The tag was written, but its hive assignment could not be saved."
                            }
                            if (success && fastTagAssignmentMode) onFastTagAssignmentSuccess(result.uid)
                        }
                    }
                    is NfcResult.Error -> {
                        writeStatusType = "error"
                        writeStatus = result.message
                    }
                    is NfcResult.Read -> {
                        writeStatusType = "error"
                        writeStatus = "A tag was detected, but BeeKeep did not confirm a successful write. Please try again."
                    }
                }
            }
        )
    }

    LaunchedEffect(hive.id, autoStartTagWriting) {
        if (!autoStartTagWriting) return@LaunchedEffect
        if (!fastTagAssignmentMode) {
            onAutoTagWritingStarted()
            return@LaunchedEffect
        }
        if (hive.isDead) {
            writeStatusType = "error"
            writeStatus = "Fast tag assignment skipped this colony because it is marked dead."
            onAutoTagWritingStarted()
        } else if (!hive.tagUid.isNullOrBlank()) {
            writeStatusType = "error"
            writeStatus = "Fast tag assignment stopped because this hive already has an NFC tag. No tag was overwritten."
            onAutoTagWritingStarted()
        } else {
            startTagWriting(autoStartTagWritingIgnoreUid)
            onAutoTagWritingStarted()
        }
    }

    if (confirmRemoveTag) {
        AlertDialog(
            onDismissRequest = { confirmRemoveTag = false },
            title = { Text("Remove NFC tag?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("Release the tag assignment from ${hiveReferenceName(hive.number)}? The physical tag is not erased; you can assign it to another hive later.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveTag = false; removeHiveTag() }) { Text("REMOVE TAG", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveTag = false }) { Text("CANCEL") }
            }
        )
    }

    if (confirmDead) {
        AlertDialog(
            onDismissRequest = { confirmDead = false },
            title = { Text("Mark colony dead?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("${hiveReferenceName(hive.number)} will move to colony history. All inspections, treatments, feedings, harvests and photos stay attached. Its NFC tag is released for reuse.") },
            confirmButton = { TextButton(onClick = { confirmDead = false; onMarkDead() }) { Text("MARK DEAD", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDead = false }) { Text("CANCEL") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${hiveReferenceName(hive.number)} permanently?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("This removes the hive and all of its associated history. This cannot be undone. To preserve history, mark the colony dead instead.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("DELETE PERMANENTLY", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") } }
        )
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InspectionHoneycombBackground(Modifier.matchParentSize())
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(hiveDisplayName(hive.number), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (hive.isDead || hive.queenStatus == "Queenless" || hive.mitePercent >= 3.0) {
                HiveStatusPill(hive)
            }
            IconButton(onClick = onHome) {
                Icon(Icons.Rounded.Home, contentDescription = "Home")
            }
        }

        if (hive.isDead) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Colony marked dead", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Died ${hive.deadAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "on an unknown date"}. History is preserved below and the NFC tag was released.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (!hive.isDead) {
            Button(
                onClick = onInspect,
                modifier = Modifier.fillMaxWidth().height(68.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFD97706), Color(0xFFF59E0B))
                        )
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color.White.copy(alpha = 0.72f)
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Rounded.TaskAlt, null, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(10.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("INSPECT HIVE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Open a new inspection", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onFeed,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color(0xFF78350F)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFB45309)),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text("FEED", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onTreat,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color(0xFF78350F)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFB45309)),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text("TREAT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onHarvest,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color(0xFF78350F)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFB45309)),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text("HARVEST", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            ColonyStrength.labelFromStored(hive.strength),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    CompactStatItem("Mites", "${String.format(Locale.US, "%.2f", hive.mitePercent)}%", Modifier.weight(1f))
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Queen ${hive.queenStatus}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Records", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text("Feeding, treatment and harvest history", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(feedings.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("FEEDINGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(treatments.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("TREATMENTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(harvests.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("HARVESTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                val lastRecordLines = buildList {
                    feedings.firstOrNull()?.let { add("Last feed  ·  ${it.amount} ${it.unit} • ${it.feedType}") }
                    treatments.firstOrNull()?.let { add("Last treatment  ·  ${it.product}") }
                    harvests.firstOrNull()?.let { add("Last harvest  ·  ${it.dryHoneyWeight} ${it.weightUnit}") }
                }
                if (lastRecordLines.isNotEmpty()) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                    lastRecordLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("No feeding, treatment or harvest records yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hive.isDead) {
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(16.dp)) { Text("RESTORE COLONY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
                } else {
                    OutlinedButton(
                        onClick = { confirmDead = true },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = Color(0xFFB91C1C)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFD9A7A0))
                    ) { Text("MARK COLONY DEAD", fontWeight = FontWeight.Bold, color = Color(0xFFB91C1C), style = MaterialTheme.typography.labelMedium) }
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color(0xFFB91C1C)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFD9A7A0))
                ) {
                    Text("DELETE HIVE PERMANENTLY", color = Color(0xFFB91C1C), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tagAssignmentSucceeded = writeStatusType == "success" || !tagAssignmentSuccessUid.isNullOrBlank()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "NFC hive tag",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (!fastTagAssignmentMode && tagAssignmentSucceeded && nextHiveNumber != null) {
                        OutlinedButton(
                            onClick = onNextHive,
                            modifier = Modifier.height(32.dp).widthIn(max = 170.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)
                        ) {
                            Text(
                                "NEXT ${hiveDisplayName(nextHiveNumber)}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = "Open next hive in this apiary",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else if (!fastTagAssignmentMode && tagAssignmentSucceeded && nextHiveNumber == null) {
                        Text(
                            "LAST HIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (hive.isDead) {
                    Text("Released", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("The physical tag was released when the colony died.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(hive.tagUid ?: "Not assigned", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onVerifyTag,
                            enabled = hive.tagUid != null,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.Nfc, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("VERIFY", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                if (writeStatusType == "waiting") {
                                    nfc.stop(activity)
                                    writeStatus = ""
                                    writeStatusType = "idle"
                                } else {
                                    startTagWriting()
                                }
                            },
                            enabled = writeStatusType != "saving" && writeStatusType != "removing",
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                when (writeStatusType) {
                                    "waiting" -> "CANCEL SCAN"
                                    "saving" -> "SAVING…"
                                    else -> if (hive.tagUid == null) "WRITE TAG" else "REPLACE"
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (hive.tagUid != null) {
                        TextButton(
                            onClick = {
                                if (confirmBeforeTagRemoval) confirmRemoveTag = true else removeHiveTag()
                            },
                            enabled = !removingTag,
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Text(if (removingTag) "REMOVING…" else "REMOVE TAG", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!tagAssignmentSuccessUid.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFDCFCE7))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.TaskAlt,
                                contentDescription = "NFC tag successfully assigned",
                                tint = Color(0xFF166534),
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                "NFC tag successfully assigned • $tagAssignmentSuccessUid",
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF166534),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    if (writeStatus.isNotBlank()) {
                        val statusTextColor = when (writeStatusType) {
                            "success" -> Color(0xFF166534)
                            "error" -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                        val statusBackground = when (writeStatusType) {
                            "success" -> Color(0xFFDCFCE7)
                            "error" -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusBackground)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (writeStatusType == "success") Icons.Rounded.TaskAlt else Icons.Rounded.Nfc,
                                contentDescription = if (writeStatusType == "success") "NFC tag successfully assigned" else "NFC status",
                                tint = statusTextColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                writeStatus,
                                modifier = Modifier.weight(1f),
                                color = statusTextColor,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (writeStatusType == "success") FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { showRecentActivity = !showRecentActivity }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recent Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text(
                            if (events.isEmpty() && inspections.isEmpty()) "No activity recorded yet" else "${events.size} events • ${inspections.size} inspections",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { showRecentActivity = !showRecentActivity },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if (showRecentActivity) "HIDE" else "SHOW", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = if (showRecentActivity) "Hide recent activity" else "Show recent activity",
                            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (showRecentActivity) 90f else 0f }
                        )
                    }
                }
                if (showRecentActivity) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                    if (events.isEmpty() && inspections.isEmpty()) {
                        Text("No events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    } else {
                        events.take(15).forEach { event -> EventCard(event) }
                        inspections.firstOrNull()?.let { InspectionSnapshot(it) }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
    }
}

@Composable
private fun HiveStatusPill(hive: Hive) {
    if (hive.isDead) {
        Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("DEAD", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        return
    }
    val flagged = hive.queenStatus == "Queenless" || hive.mitePercent >= 3.0
    if (!flagged) return
    Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text("CHECK", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

