package com.beekeep.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.isSupportedWorkspaceInvitationCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun WorkspaceScreen(
    padding: PaddingValues,
    activity: ComponentActivity,
    cloud: SupabaseGateway,
    currentPlan: BeeKeepPlan,
    hasUltimateAccess: Boolean,
    activeWorkspaceId: String?,
    hiveCount: Int,
    apiaryCount: Int,
    onUpgrade: () -> Unit,
    onWorkspaceMembershipChanged: (Boolean) -> Unit
) {
    val hasActiveTeam = !activeWorkspaceId.isNullOrBlank()
    val account by cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var hasOwnedTeamWorkspace by rememberSaveable { mutableStateOf(false) }
    var ownerUltimateEntitlementActive by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var ownerAccessCheckComplete by rememberSaveable { mutableStateOf(false) }
    var ownerAccessCheckMessage by rememberSaveable { mutableStateOf("") }
    var ownerAccessActionBusy by rememberSaveable { mutableStateOf(false) }

    // Only a team owner is gated by their own Ultimate entitlement. Teammates who
    // joined someone else's workspace do not need to subscribe to Ultimate themselves.
    suspend fun refreshOwnerWorkspaceAccess() {
        if (!account.signedIn) {
            hasOwnedTeamWorkspace = false
            ownerUltimateEntitlementActive = null
            ownerAccessCheckMessage = ""
            ownerAccessCheckComplete = true
            return
        }

        val workspaceResult = cloud.listWorkspaces()
        if (!workspaceResult.isSuccess) {
            ownerAccessCheckMessage = workspaceResult.error
                ?: "Couldn't verify workspace ownership. Check your connection and retry."
            ownerUltimateEntitlementActive = null
            ownerAccessCheckComplete = true
            return
        }

        hasOwnedTeamWorkspace = workspaceResult.value.orEmpty().any {
            !it.is_personal && it.member_role == "owner"
        }
        if (!hasOwnedTeamWorkspace) {
            ownerUltimateEntitlementActive = null
            ownerAccessCheckMessage = ""
            ownerAccessCheckComplete = true
            return
        }

        val entitlement = cloud.myActiveUltimateEntitlement()
        if (entitlement.isSuccess) {
            ownerUltimateEntitlementActive = entitlement.value == true
            ownerAccessCheckMessage = if (entitlement.value == true) "" else
                "The server reports that this account has no active Ultimate entitlement."
        } else {
            // A network error is not proof that a plan expired. Keep the page available
            // on verification errors; server-side row-level security still protects writes.
            ownerUltimateEntitlementActive = null
            ownerAccessCheckMessage = entitlement.error
                ?: "Couldn't verify Ultimate access. Check your connection and retry."
        }
        ownerAccessCheckComplete = true
    }

    androidx.compose.runtime.LaunchedEffect(account.signedIn, currentPlan) {
        ownerAccessCheckComplete = false
        refreshOwnerWorkspaceAccess()
    }
    androidx.compose.runtime.LaunchedEffect(account.signedIn, hasOwnedTeamWorkspace) {
        if (account.signedIn && hasOwnedTeamWorkspace) {
            while (true) {
                delay(60_000)
                val entitlement = cloud.myActiveUltimateEntitlement()
                // Refresh periodically while the app stays open so expiry takes effect
                // without deleting or resetting the selected team's local database.
                if (entitlement.isSuccess) {
                    ownerUltimateEntitlementActive = entitlement.value == true
                    ownerAccessCheckMessage = if (entitlement.value == true) "" else
                        "The server reports that this account has no active Ultimate entitlement."
                }
            }
        }
    }
    val workspaceAccessPaused = account.signedIn &&
        ownerAccessCheckComplete &&
        hasOwnedTeamWorkspace &&
        ownerUltimateEntitlementActive == false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                            )
                        )
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "BEEKEEP WORKSPACE",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            if (hasActiveTeam) "Your team, in sync" else "Bring your crew together",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            if (hasActiveTeam) {
                                "Shared apiary records are active on this device. Changes sync with your team."
                            } else {
                                "Create a shared workspace, invite your crew, and keep every member working from the same records."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Groups,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(40.dp)
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = if (hasActiveTeam) Icons.Rounded.Groups else Icons.Rounded.Yard,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            when {
                                hasActiveTeam -> "TEAM WORKSPACE ACTIVE"
                                hasUltimateAccess -> "ULTIMATE ACCESS"
                                else -> currentPlan.displayName.uppercase()
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (hasActiveTeam) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WorkspaceMetric(
                            label = "Hives",
                            value = hiveCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        WorkspaceMetric(
                            label = "Apiaries",
                            value = apiaryCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        if (hasUltimateAccess) {
                            "Ultimate includes team creation. You can also join another beekeeper’s workspace with an invitation code."
                        } else {
                            "Joining a team does not require Ultimate. Ultimate is required only to create a new team workspace."
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        WorkspaceCloudSyncSection(cloud = cloud)

        if (account.signedIn && !ownerAccessCheckComplete) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    "Checking workspace access…",
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (workspaceAccessPaused) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Workspace access paused", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text("An active Ultimate entitlement is required for the owner of a team workspace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "Your team's databases, hives, apiaries, inspection history, and queued local edits are retained. BeeKeep will not delete or reset records because a plan expired. Team cloud writes may remain blocked until Ultimate is active again.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onUpgrade,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !ownerAccessActionBusy
                    ) { Text("RENEW ULTIMATE") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                ownerAccessActionBusy = true
                                refreshOwnerWorkspaceAccess()
                                ownerAccessActionBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !ownerAccessActionBusy
                    ) { Text(if (ownerAccessActionBusy) "CHECKING…" else "CHECK ACCESS AGAIN") }
                    if (hasActiveTeam) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    ownerAccessActionBusy = true
                                    val result = cloud.activateWorkspace(null)
                                    ownerAccessCheckMessage = result.error
                                        ?: "Switched to your personal data. Your team data remains saved."
                                    ownerAccessActionBusy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !ownerAccessActionBusy
                        ) { Text("SWITCH TO PERSONAL DATA") }
                    }
                    if (ownerAccessCheckMessage.isNotBlank()) {
                        Text(ownerAccessCheckMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            if (hasOwnedTeamWorkspace && ownerUltimateEntitlementActive == null && ownerAccessCheckMessage.isNotBlank()) {
                Text(
                    ownerAccessCheckMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            WorkspaceManagementSection(
                cloud = cloud,
                activity = activity,
                currentPlan = currentPlan,
                onUpgrade = onUpgrade,
                onWorkspaceMembershipChanged = onWorkspaceMembershipChanged
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WorkspaceMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Compact invitation entry kept on More so people can join a team without Ultimate.
 * Account sign-in stays on More, alongside the rest of the existing account controls.
 */
@Composable
fun WorkspaceJoinEntry(
    cloud: SupabaseGateway,
    activity: ComponentActivity,
    onWorkspaceJoined: (String) -> Unit
) {
    val account by cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var invitationCode by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.padding(10.dp).size(25.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("Join a workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "An invitation code is all you need. Ultimate is not required to join.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = invitationCode,
                onValueChange = { value ->
                    invitationCode = value.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(128)
                    message = ""
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Invitation code") },
                placeholder = { Text("Bee123456") },
                singleLine = true,
                enabled = !loading
            )

            if (!account.signedIn) {
                Text(
                    if (!account.configured) {
                        "Cloud access is not configured on this build."
                    } else {
                        "Sign in to BeeKeep Cloud in the account section below, then return here to join."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = account.signedIn && !loading && isSupportedWorkspaceInvitationCode(invitationCode),
                onClick = {
                    scope.launch {
                        loading = true
                        message = ""
                        val result = cloud.acceptWorkspaceInvitation(invitationCode.trim())
                        if (result.isSuccess && result.value != null) {
                            val joined = result.value!!
                            invitationCode = ""
                            activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                                .putString("selected_team_workspace_id", joined.workspace_id).apply()
                            val activation = cloud.activateWorkspace(joined.workspace_id)
                            message = activation.error
                                ?: "Joined ${joined.workspace_name}. Opening your workspace…"
                            onWorkspaceJoined(message)
                        } else {
                            message = result.error ?: "Couldn't accept this invitation. Check the code and invited email."
                        }
                        loading = false
                    }
                }
            ) {
                Text(if (loading) "JOINING…" else "JOIN WORKSPACE")
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("Couldn't")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
