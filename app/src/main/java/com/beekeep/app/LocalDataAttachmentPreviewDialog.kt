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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.LocalDataAttachmentPreview
import com.beekeep.app.cloud.LocalDataAttachmentMergePlan
import com.beekeep.app.cloud.LocalDataAttachmentDisposition
import com.beekeep.app.cloud.LocalDataAttachmentExecutionResult
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
    mergePlan: LocalDataAttachmentMergePlan?,
    mergePlanLoading: Boolean,
    mergePlanError: String?,
    executionLoading: Boolean,
    executionError: String?,
    executionResult: LocalDataAttachmentExecutionResult?,
    onBuildMergePlan: () -> Unit,
    onExecuteAttachment: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val showAttachmentConfirmation = rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
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
                    Text("COLLISION-SAFE MERGE PLAN", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Build a read-only plan to identify records that can be added, skipped as duplicates, remapped safely, or require review.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.material3.Button(
                        onClick = onBuildMergePlan,
                        enabled = !mergePlanLoading && result.cloudInventoryError == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (mergePlanLoading) "BUILDING MERGE PLAN…" else "BUILD MERGE PLAN")
                    }
                    if (mergePlanLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                            Text("  Comparing record identities and cloud conflicts…")
                        }
                    }
                    mergePlanError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    mergePlan?.let { plan ->
                        Text(
                            "Plan for this signed-in account",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        MergePlanCount("New records", plan.newRecordCount)
                        MergePlanCount("Already present (skip)", plan.duplicateCount)
                        MergePlanCount("ID collisions (remap)", plan.remapCount)
                        MergePlanCount("Needs manual review", plan.reviewRequiredCount)
                        MergePlanCount("Preserved locally only", plan.localOnlyCount)
                        Text(
                            when {
                                plan.isSafeToProceed ->
                                    "No identity conflicts requiring manual review were found. A future merge step will still revalidate the cloud and ask for confirmation."
                                else ->
                                    "Some records need a decision before any merge can proceed. Nothing will be overwritten or silently discarded."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (plan.isSafeToProceed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        plan.items.filter { it.disposition == LocalDataAttachmentDisposition.REVIEW_REQUIRED }
                            .take(8)
                            .forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "${item.entityType.replace('_', ' ')} #${item.sourceId}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(item.reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        if (plan.reviewRequiredCount > 8) {
                            Text(
                                "And ${plan.reviewRequiredCount - 8} more records requiring review.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Tasks: ${plan.taskCount} preserved locally. Drafts: ${plan.noteDraftCount} note and ${plan.formDraftCount} form entries retained in the recovery snapshot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Pending queues preserved: ${plan.pendingSyncWriteCount} sync writes, including ${plan.pendingDeleteCount} delete requests; " +
                                "${plan.pendingActionBatchCount} action batches and ${plan.pendingWorkspaceActionCount} workspace actions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (plan.isSafeToProceed && executionResult == null) {
                        Text(
                            "A safe plan is ready. The final action adds only new local records, skips exact duplicates, and keeps tasks, drafts and pending queues on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { showAttachmentConfirmation.value = true },
                            enabled = !executionLoading && !mergePlanLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (executionLoading) "ATTACHING LOCAL DATA…" else "ATTACH LOCAL DATA")
                        }
                    }
                    if (executionLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                            Text("  Uploading insert-only batches and verifying the merged account…")
                        }
                    }
                    executionError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "The attachment checkpoint is retained. Cloud sync remains paused; refresh the preview and review the plan before retrying.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    executionResult?.let { result ->
                        Text(
                            "ATTACHMENT VERIFIED",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${result.uploadedRecordCount} records uploaded, ${result.alreadyPresentRecordCount} already present, " +
                                "${result.remappedRecordCount} IDs remapped, and ${result.preservedLocalOnlyCount} records retained locally.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Cloud sync has been released after cloud and local verification.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (executionResult == null && executionError.isNullOrBlank()) {
                        Text(
                            "Nothing has been attached yet. Cloud sync remains paused.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !executionLoading) { Text("CLOSE") }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !loading && !executionLoading) { Text("REFRESH PREVIEW") }
        }
    )

    if (showAttachmentConfirmation.value) {
        val plan = mergePlan
        AlertDialog(
            onDismissRequest = { showAttachmentConfirmation.value = false },
            title = { Text("Attach local data to this account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "BeeKeep will insert ${plan?.newRecordCount?.plus(plan.remapCount) ?: 0} local records into this account and skip exact duplicates."
                    )
                    Text("No existing cloud record will be replaced or deleted.")
                    Text(
                        "Tasks, unfinished drafts and ${plan?.pendingSyncWriteCount ?: 0} pending sync writes will remain on this device. Pending delete requests and action queues will not be replayed."
                    )
                    Text(
                        "Cloud sync will resume only after the cloud rows and the local database are verified.",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAttachmentConfirmation.value = false
                        onExecuteAttachment()
                    },
                    enabled = !executionLoading && plan?.isSafeToProceed == true
                ) { Text("CONFIRM ATTACHMENT") }
            },
            dismissButton = {
                TextButton(onClick = { showAttachmentConfirmation.value = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun MergePlanCount(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(count.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
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
