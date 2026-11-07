package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.LocalDataAttachmentDisposition
import com.beekeep.app.cloud.LocalDataAttachmentMergePlan
import com.beekeep.app.cloud.LocalDataAttachmentPreview
import com.beekeep.app.cloud.SupabaseGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BeeKeepLocalDataAttachmentReviewScreen(
    cloud: SupabaseGateway,
    onContinueLocally: () -> Unit
) {
    BackHandler(enabled = true, onBack = onContinueLocally)
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<LocalDataAttachmentPreview?>(null) }
    var plan by remember { mutableStateOf<LocalDataAttachmentMergePlan?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attaching by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val loadPreview: () -> Unit = {
        scope.launch {
            loading = true
            preview = null
            plan = null
            confirmed = false
            message = null
            isError = false
            try {
                val previewResult = cloud.previewLocalDataAttachment()
                val captured = previewResult.value
                if (!previewResult.isSuccess || captured == null) {
                    message = previewResult.error ?: "BeeKeep couldn't prepare the local-data review."
                    isError = true
                } else {
                    preview = captured
                    if (!captured.cloudInventoryError.isNullOrBlank()) {
                        message = captured.cloudInventoryError
                        isError = true
                    } else {
                        val planResult = cloud.buildLocalDataAttachmentMergePlan(captured.localSnapshot)
                        val builtPlan = planResult.value
                        if (!planResult.isSuccess || builtPlan == null) {
                            message = planResult.error ?: "BeeKeep couldn't build a safe merge plan."
                            isError = true
                        } else {
                            plan = builtPlan
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                message = error.message ?: "BeeKeep couldn't review the local records. Nothing has been uploaded."
                isError = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadPreview()
    }

    val currentPreview = preview
    val currentPlan = plan
    val canAttach = currentPlan?.isSafeToProceed == true && confirmed && !loading && !attaching

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Review local data",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                "BeeKeep found records on this phone that are not yet confirmed in this cloud account. Your local records are safe. Personal cloud sync is paused until you review them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Local and cloud inventory", fontWeight = FontWeight.Bold)
                    if (currentPreview == null) {
                        if (loading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Comparing this phone with your cloud account…")
                            }
                        } else {
                            Text(
                                "The inventory is not available yet. Retry the review; BeeKeep will not upload anything while the comparison is incomplete.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        val localCounts = currentPreview.localSnapshot.recordCounts
                        val cloudActiveCounts = currentPreview.cloudRecordCounts.mapValues { (type, count) ->
                            (count - (currentPreview.cloudDeletedCounts[type] ?: 0)).coerceAtLeast(0)
                        }
                        InventoryRow("Apiaries", localCounts["apiary"] ?: 0, cloudActiveCounts["apiary"] ?: 0)
                        InventoryRow("Hives", localCounts["hive"] ?: 0, cloudActiveCounts["hive"] ?: 0)
                        InventoryRow("Inspections", localCounts["inspection"] ?: 0, cloudActiveCounts["inspection"] ?: 0)
                        InventoryRow("Feedings", localCounts["feeding"] ?: 0, cloudActiveCounts["feeding"] ?: 0)
                        InventoryRow("Treatments", localCounts["treatment"] ?: 0, cloudActiveCounts["treatment"] ?: 0)
                        InventoryRow("Harvest records", localCounts["harvest"] ?: 0, cloudActiveCounts["harvest"] ?: 0)
                        InventoryRow("Events", localCounts["event"] ?: 0, cloudActiveCounts["event"] ?: 0)
                        val snapshot = currentPreview.localSnapshot
                        Text(
                            "Also found: ${snapshot.pendingSyncWrites} queued writes, ${snapshot.pendingActionBatches} apiary action batches, ${snapshot.noteDrafts} note drafts, and ${snapshot.formDrafts} inspection drafts.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            currentPlan?.let { mergePlan ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("What the review would do", fontWeight = FontWeight.Bold)
                        InventorySummaryRow("New records to add", mergePlan.newRecordCount)
                        InventorySummaryRow("ID collisions to remap safely", mergePlan.remapCount)
                        InventorySummaryRow("Records already present", mergePlan.duplicateCount)
                        InventorySummaryRow("Records kept local-only", mergePlan.localOnlyCount)
                        InventorySummaryRow("Items needing manual review", mergePlan.reviewRequiredCount)
                        if (mergePlan.reviewRequiredCount > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "These items will not be uploaded automatically:",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            mergePlan.items
                                .filter { it.disposition == LocalDataAttachmentDisposition.REVIEW_REQUIRED }
                                .take(5)
                                .forEach { item ->
                                    Text(
                                        "• ${item.entityType} ${item.sourceId}: ${item.reason}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = confirmed,
                                    onCheckedChange = { confirmed = it },
                                    enabled = !loading && !attaching
                                )
                                Text(
                                    "I reviewed these counts and approve attaching the listed records to this account.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (mergePlan.reviewRequiredCount > 0) {
                            Text(
                                "BeeKeep cannot safely resolve every item yet. Nothing will be attached; you can continue using this phone locally while personal cloud sync stays paused.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (!message.isNullOrBlank()) {
                Text(
                    message.orEmpty(),
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    if (canAttach && currentPreview != null && currentPlan != null) {
                        scope.launch {
                            attaching = true
                            message = null
                            isError = false
                            try {
                                val result = cloud.executeLocalDataAttachment(
                                    currentPreview.localSnapshot,
                                    currentPlan
                                )
                                if (!result.isSuccess || result.value == null) {
                                    message = result.error ?: "BeeKeep couldn't finish attaching the reviewed records."
                                    isError = true
                                } else {
                                    message = "Local data was attached successfully."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                message = error.message ?: "BeeKeep couldn't finish attaching the reviewed records."
                                isError = true
                            } finally {
                                attaching = false
                            }
                        }
                    }
                },
                enabled = canAttach,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (attaching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (attaching) "ATTACHING…" else "ATTACH REVIEWED DATA")
            }

            TextButton(
                onClick = loadPreview,
                enabled = !loading && !attaching,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh comparison")
            }
            TextButton(
                onClick = onContinueLocally,
                enabled = !attaching,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue using this phone locally")
            }
            Text(
                "Continuing locally does not approve an upload. BeeKeep will keep personal cloud sync paused until this review is completed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InventoryRow(label: String, local: Int, cloud: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "This phone: $local  •  Cloud: $cloud",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InventorySummaryRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun BeeKeepLocalDataAttachmentPendingBanner(onReview: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Personal cloud sync paused", fontWeight = FontWeight.Bold)
                Text(
                    "Local records need review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onReview) {
                Text("Review")
            }
        }
    }
}
