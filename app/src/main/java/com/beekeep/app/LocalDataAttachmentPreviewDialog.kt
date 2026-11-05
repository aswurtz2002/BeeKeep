package com.beekeep.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.LocalDataAttachmentPreview
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val attachmentRecordLabels = listOf(
    "apiary" to "Apiaries",
    "hive" to "Hives",
    "nfc_assignment" to "NFC tag history",
    "inspection" to "Inspections",
    "feeding" to "Feeding entries",
    "treatment" to "Treatments",
    "harvest" to "Harvest entries",
    "event" to "Activity events",
    "task" to "Tasks"
)

@Composable
internal fun LocalDataAttachmentPreviewDialog(
    preview: LocalDataAttachmentPreview?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review existing device data", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "This review compares the local personal dataset with the signed-in account's cloud records.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text("  Preparing a protected snapshot and reading cloud inventory…")
                    }
                }

                error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                preview?.let { result ->
                    Text(
                        if (result.accountEmail.isBlank()) "Signed-in account" else "Account: ${result.accountEmail}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Snapshot captured ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(result.localSnapshot.createdAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("LOCAL DEVICE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${result.localSnapshot.totalRecords} saved records across the personal database",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    InventoryRows(result.localSnapshot.recordCounts, emptyLabel = "No local records were found.")

                    Text("CLOUD ACCOUNT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                    if (result.cloudInventoryError != null) {
                        Text(
                            "Cloud inventory could not be verified: ${result.cloudInventoryError}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "The cloud is not being treated as empty. Refresh the preview when the connection is available.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "${result.cloudRecordsTotal} cloud documents, including ${result.cloudDeletedTotal} deletion records",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        InventoryRows(
                            result.cloudRecordCounts,
                            deletedCounts = result.cloudDeletedCounts,
                            emptyLabel = "No cloud documents were found for this account."
                        )
                    }

                    Text("LOCAL RECOVERY SNAPSHOT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Saved privately on this device. ${formatAttachmentBytes(result.localSnapshot.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "File: ${result.localSnapshot.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Integrity check: ${result.localSnapshot.sha256.take(16)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "Pending local changes: ${result.localSnapshot.pendingSyncWrites} sync writes, " +
                            "${result.localSnapshot.pendingActionBatches} action batches, " +
                            "${result.localSnapshot.pendingWorkspaceActions} workspace actions.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Unfinished drafts preserved: ${result.localSnapshot.noteDrafts} inspection note drafts and " +
                            "${result.localSnapshot.formDrafts} inspection form drafts.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Nothing was uploaded, imported, merged, modified, or deleted. Cloud sync remains paused.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !loading) { Text("REFRESH PREVIEW") }
        }
    )
}

@Composable
private fun InventoryRows(
    counts: Map<String, Int>,
    deletedCounts: Map<String, Int> = emptyMap(),
    emptyLabel: String
) {
    val rows = (attachmentRecordLabels.map { it.first to (it.second to (counts[it.first] ?: 0)) } +
        counts.keys.filter { key -> attachmentRecordLabels.none { it.first == key } }
            .sorted().map { it to (it to (counts[it] ?: 0)) })
        .filter { it.second.second > 0 }
    if (rows.isEmpty()) {
        Text(emptyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (key, labelAndCount) ->
                val (label, count) = labelAndCount
                val deleted = deletedCounts[key] ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (deleted > 0) "$count ($deleted deleted)" else count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun formatAttachmentBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
