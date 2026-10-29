package com.beekeep.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.SupabaseGateway
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Cloud sync actions and status belong with workspace controls, while sign-in stays in More. */
@Composable
fun WorkspaceCloudSyncSection(cloud: SupabaseGateway) {
    val account by cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Cloud sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(
                        when {
                            !account.configured -> "Cloud sync is not configured on this build."
                            !account.signedIn -> "Sign in from More to sync this device."
                            account.syncing -> "Syncing the active workspace…"
                            else -> "Changes sync for the active dataset."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    when {
                        !account.configured -> "OFF"
                        !account.signedIn -> "LOCAL"
                        account.syncing -> "SYNCING"
                        else -> "ONLINE"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        message = when (val result = cloud.syncNow()) {
                            CloudResult.Success -> "Synced just now."
                            CloudResult.NotSignedIn -> "Sign in required. Open More to sign in."
                            CloudResult.NotConfigured -> "Cloud is not configured."
                            is CloudResult.Failure -> result.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = account.configured && account.signedIn && !account.syncing
            ) { Text(if (account.syncing) "SYNCING…" else "SYNC NOW") }

            val displayMessage = message.ifBlank { account.error.orEmpty() }
            if (displayMessage.isNotBlank()) {
                Text(
                    displayMessage,
                    maxLines = 4,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (
                        displayMessage.contains("failed", true) ||
                        displayMessage.contains("blocked", true) ||
                        displayMessage.contains("couldn't", true) ||
                        displayMessage.contains("without its required", true) ||
                        displayMessage.contains("technical error", true)
                    ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            account.lastSyncAt?.let { timestamp ->
                Text(
                    "Last successful sync: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
