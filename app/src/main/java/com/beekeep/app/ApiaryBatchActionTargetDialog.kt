package com.beekeep.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beekeep.app.data.Hive

internal data class ApiaryBatchActionTarget(
    val apiaryId: Long,
    val apiaryName: String,
    val hiveIds: List<Long>
)

@Composable
internal fun ApiaryBatchActionTargetDialog(
    apiaryName: String,
    hives: List<Hive>,
    lockedHiveIds: Set<Long>,
    onDismiss: () -> Unit,
    onContinue: (HiveLogType, List<Long>) -> Unit
) {
    val eligibleHives = remember(hives, lockedHiveIds) {
        hives.filter { !it.isDead && it.id !in lockedHiveIds }
    }
    val availableIds = remember(eligibleHives) { eligibleHives.map { it.id } }
    var selectedHiveIds by remember(apiaryName, availableIds) {
        mutableStateOf(availableIds.toSet())
    }
    var actionType by remember(apiaryName) { mutableStateOf(HiveLogType.FEED) }
    val selectedCount = eligibleHives.count { it.id in selectedHiveIds }
    val lockedHiveCount = hives.count { !it.isDead && it.id in lockedHiveIds }
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp.dp * 0.88f).coerceAtMost(740.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = maxDialogHeight),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Apply to Apiary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(apiaryName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Choose the record type and active hives that should receive it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Record type", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = actionType == HiveLogType.FEED,
                        onClick = { actionType = HiveLogType.FEED },
                        label = { Text("Feed") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = actionType == HiveLogType.TREAT,
                        onClick = { actionType = HiveLogType.TREAT },
                        label = { Text("Treat") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = actionType == HiveLogType.HARVEST,
                        onClick = { actionType = HiveLogType.HARVEST },
                        label = { Text("Harvest") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hives", fontWeight = FontWeight.Bold)
                        Text(
                            "$selectedCount selected • ${eligibleHives.size} available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            selectedHiveIds = if (selectedCount == eligibleHives.size) {
                                emptySet()
                            } else {
                                availableIds.toSet()
                            }
                        },
                        enabled = eligibleHives.isNotEmpty()
                    ) {
                        Text(if (selectedCount == eligibleHives.size) "Clear all" else "Select all")
                    }
                }

                if (lockedHiveCount > 0) {
                    Text(
                        "$lockedHiveCount hives are locked by the current plan and cannot be selected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (eligibleHives.isEmpty()) {
                    Text(
                        "No active, unlocked hives are available in this apiary.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(min = 120.dp, max = maxDialogHeight * 0.36f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(eligibleHives, key = { it.id }) { hive ->
                            val checked = hive.id in selectedHiveIds
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        selectedHiveIds = if (checked) {
                                            selectedHiveIds - hive.id
                                        } else {
                                            selectedHiveIds + hive.id
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected ->
                                        selectedHiveIds = if (selected) {
                                            selectedHiveIds + hive.id
                                        } else {
                                            selectedHiveIds - hive.id
                                        }
                                    }
                                )
                                Text(
                                    "Hive ${hive.number}",
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Feed amounts are per hive. The harvest quantity setting controls whether harvest amounts are per hive or apiary totals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) { Text("CANCEL") }
                    Button(
                        onClick = {
                            val selected = eligibleHives.filter { it.id in selectedHiveIds }.map { it.id }
                            onContinue(actionType, selected)
                        },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1.2f)
                    ) { Text("CONTINUE") }
                }
            }
        }
    }
}
