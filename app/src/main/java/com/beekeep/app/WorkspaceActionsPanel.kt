package com.beekeep.app

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.BeeKeepWorkspaceActionLogEntry
import com.beekeep.app.cloud.SupabaseGateway
import kotlinx.coroutines.launch

@Composable
fun WorkspaceActionsPanel(
    cloud: SupabaseGateway,
    workspaceId: String,
    isOwner: Boolean
) {
    if (!isOwner) return

    val scope = rememberCoroutineScope()
    var entries by remember(workspaceId) { mutableStateOf<List<BeeKeepWorkspaceActionLogEntry>>(emptyList()) }
    var retention by remember(workspaceId) { mutableStateOf("keep") }
    var loading by remember(workspaceId) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(workspaceId) { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<BeeKeepWorkspaceActionLogEntry?>(null) }

    suspend fun refresh() {
        loading = true
        val settings = cloud.getWorkspaceActionLogSettings(workspaceId)
        if (settings.isSuccess) retention = settings.value?.retention_mode ?: "keep"
        else error = settings.error ?: "Couldn't load activity settings."
        val result = cloud.listWorkspaceActionLog(workspaceId)
        if (result.isSuccess) {
            entries = result.value.orEmpty()
            error = null
        } else error = result.error ?: "Couldn't load workspace activity."
        loading = false
    }

    LaunchedEffect(workspaceId) { refresh() }

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
                if (retention == "expire_90_days") "Entries older than 90 days are automatically removed." else "Entries are kept until you delete them.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !loading) { Text("RETRY") }
        }
        when {
            loading -> Text("Loading workspace activity…", style = MaterialTheme.typography.bodyMedium)
            entries.isEmpty() -> WorkspacePanel(title = "No activity yet") {
                Text("Changes made by your team will appear here.", style = MaterialTheme.typography.bodySmall)
            }
            else -> entries.forEach { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("@${entry.username}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                entry.action_at.replace("T", " ").take(16),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { deleteTarget = entry }, enabled = !saving) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!saving) deleteTarget = null },
            title = { Text("Delete activity entry?") },
            text = { Text("This removes this entry from the workspace Actions log. Hive and apiary records will not be changed.") },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    scope.launch {
                        saving = true
                        val result = cloud.deleteWorkspaceActionLogEntry(entry.id)
                        if (result.isSuccess) entries = entries.filterNot { it.id == entry.id }
                        else error = result.error ?: "Couldn't delete this entry."
                        saving = false
                        deleteTarget = null
                    }
                }) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !saving) { Text("CANCEL") } }
        )
    }
}
