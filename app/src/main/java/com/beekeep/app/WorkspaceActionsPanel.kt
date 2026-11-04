package com.beekeep.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.BeeKeepWorkspaceActionLogEntry
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.WorkspaceActionFormatter
import com.beekeep.app.cloud.WorkspaceActionChangePresentation
import kotlinx.coroutines.launch

@Composable
fun WorkspaceActionsPanel(
    cloud: SupabaseGateway,
    workspaceId: String,
    isOwner: Boolean
) {
    if (!isOwner) return

    val scope = rememberCoroutineScope()
    val syncRefreshVersion by cloud.workspaceActionLogRefreshVersion.collectAsState()
    var entries by remember(workspaceId) {
        mutableStateOf<List<BeeKeepWorkspaceActionLogEntry>>(emptyList())
    }
    var retention by remember(workspaceId) { mutableStateOf("keep") }
    var loading by remember(workspaceId) { mutableStateOf(true) }
    var hasLoaded by remember(workspaceId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(workspaceId) { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<BeeKeepWorkspaceActionLogEntry?>(null) }

    suspend fun refresh() {
        if (!hasLoaded) loading = true
        val settings = cloud.getWorkspaceActionLogSettings(workspaceId)
        if (settings.isSuccess) retention = settings.value?.retention_mode ?: "keep"
        else error = settings.error ?: "Couldn't load activity settings."
        val result = cloud.listWorkspaceActionLog(workspaceId)
        if (result.isSuccess) {
            entries = result.value.orEmpty()
            error = null
        } else error = result.error ?: "Couldn't load workspace activity."
        loading = false
        hasLoaded = true
    }

    // Keep the existing live refresh: synced actions appear without restarting or re-entering the tab.
    LaunchedEffect(workspaceId, syncRefreshVersion) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspacePanel(title = "Workspace actions") {
            Text(
                "Recent changes made by members of this team.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !saving && retention != "keep",
                    onClick = {
                        scope.launch {
                            saving = true
                            val result = cloud.setWorkspaceActionLogRetention(workspaceId, "keep")
                            if (result.isSuccess) retention = "keep"
                            else error = result.error ?: "Couldn't update retention."
                            saving = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Keep") }
                Button(
                    enabled = !saving && retention != "expire_90_days",
                    onClick = {
                        scope.launch {
                            saving = true
                            val result = cloud.setWorkspaceActionLogRetention(workspaceId, "expire_90_days")
                            if (result.isSuccess) retention = "expire_90_days"
                            else error = result.error ?: "Couldn't update retention."
                            saving = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("90 days") }
            }
            Text(
                if (retention == "expire_90_days") {
                    "Entries older than 90 days are automatically removed."
                } else {
                    "Entries are kept until you delete them."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (error != null) {
            Text(
                error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                Text("RETRY")
            }
        }

        when {
            loading -> Text("Loading workspace activity…", style = MaterialTheme.typography.bodyMedium)
            entries.isEmpty() -> WorkspacePanel(title = "No activity yet") {
                Text(
                    "Changes made by your team will appear here.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> entries.forEach { entry ->
                WorkspaceActionEntryCard(
                    entry = entry,
                    saving = saving,
                    onDelete = { deleteTarget = entry }
                )
            }
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!saving) deleteTarget = null },
            title = { Text("Delete activity entry?") },
            text = {
                Text(
                    "This removes this entry from the workspace Actions log. " +
                        "Hive and apiary records will not be changed."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            val result = cloud.deleteWorkspaceActionLogEntry(entry.id)
                            if (result.isSuccess) entries = entries.filterNot { it.id == entry.id }
                            else error = result.error ?: "Couldn't delete this entry."
                            saving = false
                            deleteTarget = null
                        }
                    }
                ) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !saving) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
private fun WorkspaceActionEntryCard(
    entry: BeeKeepWorkspaceActionLogEntry,
    saving: Boolean,
    onDelete: () -> Unit
) {
    val presentation = remember(entry) { WorkspaceActionFormatter.format(entry) }
    var expandedChanges by remember(entry.id) { mutableStateOf<Set<String>>(emptySet()) }
    var hiveListExpanded by remember(entry.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = presentation.header,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = presentation.accessibilityDescription },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onDelete, enabled = !saving) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            presentation.location?.let { location ->
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            if (presentation.isLegacy) {
                presentation.legacyDescription?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                presentation.changes.forEach { change ->
                    val expanded = change.field in expandedChanges
                    WorkspaceActionChangeRow(
                        change = change,
                        expanded = expanded,
                        onToggleExpanded = {
                            expandedChanges = if (expanded) {
                                expandedChanges - change.field
                            } else {
                                expandedChanges + change.field
                            }
                        }
                    )
                }
            }

            if (presentation.affectedHiveNames.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { hiveListExpanded = !hiveListExpanded }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Affected hives: " +
                                presentation.affectedHiveNames.joinToString(", ") + "."
                            stateDescription = if (hiveListExpanded) "Expanded" else "Collapsed"
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (hiveListExpanded) "Hide hives" else "Show hives",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "(" + presentation.affectedHiveNames.size + ")",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hiveListExpanded) {
                    Text(
                        text = presentation.affectedHiveNames.joinToString(", "),
                        modifier = Modifier.semantics {
                            contentDescription = "Affected hives: " +
                                presentation.affectedHiveNames.joinToString(", ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceActionChangeRow(
    change: WorkspaceActionChangePresentation,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val oldText = if (expanded) change.oldValue else change.oldPreview
    val newText = if (expanded) change.newValue else change.newPreview
    val accessibleText = change.label + " changed from " + change.oldValue +
        " to " + change.newValue + "."

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = change.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        val valueModifier = if (change.isExpandable) {
            Modifier.clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse full value" else "Expand full value",
                onClick = onToggleExpanded
            )
        } else {
            Modifier
        }
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough
                    )
                ) {
                    append(oldText)
                }
                append("  →  ")
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(newText)
                }
            },
            modifier = valueModifier.semantics(mergeDescendants = true) {
                contentDescription = accessibleText
                if (change.isExpandable) {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis
        )
    }
}
