package com.beekeep.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.WorkspaceAccessPolicy
import com.beekeep.app.cloud.isSupportedWorkspaceInvitationCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun WorkspaceScreen(
    padding: PaddingValues,
    activity: ComponentActivity,
    cloud: SupabaseGateway,
    currentPlan: BeeKeepPlan,
    hasCommercialAccess: Boolean,
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
    var ownedWorkspaceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeWorkspaceIsOwnedByUser by rememberSaveable { mutableStateOf(false) }
    var ownerAccessAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var ownerCommercialEntitlementActive by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var activeWorkspaceOwnerCommercialEntitlementActive by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var ownerAccessCheckComplete by rememberSaveable { mutableStateOf(false) }
    var ownerAccessCheckMessage by rememberSaveable { mutableStateOf("") }
    var ownerAccessActionBusy by rememberSaveable { mutableStateOf(false) }

    // A team stays available only while its owner has active Commercial. Invited
    // members do not need their own Commercial plan, so check the owner through a
    // membership-scoped server RPC for every selected team.
    suspend fun refreshOwnerWorkspaceAccess() {
        val previousActiveWorkspaceOwnerEntitlement = activeWorkspaceOwnerCommercialEntitlementActive
        if (!account.signedIn) {
            hasOwnedTeamWorkspace = false
            ownedWorkspaceIds = emptySet()
            activeWorkspaceIsOwnedByUser = false
            ownerCommercialEntitlementActive = null
            activeWorkspaceOwnerCommercialEntitlementActive = null
            ownerAccessCheckMessage = ""
            ownerAccessCheckComplete = true
            return
        }

        val workspaceResult = cloud.listWorkspaces()
        if (!workspaceResult.isSuccess) {
            ownerAccessCheckMessage = workspaceResult.error
                ?: "Couldn't verify workspace access. Check your connection and retry."
            // Reuse the account-scoped ownership list only for an already known team owner.
            // Never reuse a previous workspace owner's entitlement for an invited member.
            activeWorkspaceIsOwnedByUser = activeWorkspaceId?.let { it in ownedWorkspaceIds } == true
            activeWorkspaceOwnerCommercialEntitlementActive =
                if (hasActiveTeam && activeWorkspaceIsOwnedByUser) {
                    ownerCommercialEntitlementActive ?: (currentPlan == BeeKeepPlan.COMMERCIAL)
                } else {
                    null
                }
            ownerAccessCheckComplete = true
            return
        }

        val workspaces = workspaceResult.value.orEmpty()
        ownedWorkspaceIds = workspaces.filter { !it.is_personal && it.member_role == "owner" }
            .map { it.id }.toSet()
        hasOwnedTeamWorkspace = ownedWorkspaceIds.isNotEmpty()
        activeWorkspaceIsOwnedByUser = workspaces.any {
            it.id == activeWorkspaceId && !it.is_personal && it.member_role == "owner"
        }

        if (hasOwnedTeamWorkspace) {
            val ownEntitlement = cloud.myActiveCommercialEntitlement()
            if (ownEntitlement.isSuccess) {
                ownerCommercialEntitlementActive = ownEntitlement.value == true
            } else if (ownerCommercialEntitlementActive == null) {
                ownerCommercialEntitlementActive = currentPlan == BeeKeepPlan.COMMERCIAL
            }
        } else {
            ownerCommercialEntitlementActive = null
        }

        val activeTeam = workspaces.firstOrNull {
            it.id == activeWorkspaceId && !it.is_personal
        }
        if (activeTeam == null) {
            activeWorkspaceOwnerCommercialEntitlementActive = null
            ownerAccessCheckMessage = ""
        } else if (activeTeam.member_role == "owner") {
            activeWorkspaceOwnerCommercialEntitlementActive = ownerCommercialEntitlementActive
            ownerAccessCheckMessage = if (ownerCommercialEntitlementActive == false) {
                "Your Commercial subscription is inactive."
            } else ""
        } else {
            val ownerEntitlement = cloud.workspaceOwnerCommercialEntitlement(activeTeam.id)
            if (ownerEntitlement.isSuccess) {
                activeWorkspaceOwnerCommercialEntitlementActive = ownerEntitlement.value == true
                ownerAccessCheckMessage = if (ownerEntitlement.value == true) "" else
                    "The workspace owner's Commercial subscription is inactive."
            } else {
                activeWorkspaceOwnerCommercialEntitlementActive = null
                ownerAccessCheckMessage = ownerEntitlement.error
                    ?: "Couldn't verify the workspace owner's Commercial subscription. Check your connection and retry."
            }
        }

        // When the selected team's owner status changes (or cannot be verified),
        // re-activate the same workspace so SupabaseGateway immediately updates the
        // local Room write gate for owners and invited members alike.
        if (activeTeam != null &&
            (previousActiveWorkspaceOwnerEntitlement != activeWorkspaceOwnerCommercialEntitlementActive ||
                activeWorkspaceOwnerCommercialEntitlementActive == null)
        ) {
            val activation = cloud.activateWorkspace(activeTeam.id)
            if (!activation.isSuccess && ownerAccessCheckMessage.isBlank()) {
                ownerAccessCheckMessage = activation.error
                    ?: "The workspace access state could not be refreshed. Try again."
            }
        }
        ownerAccessCheckComplete = true
    }

    androidx.compose.runtime.LaunchedEffect(account.signedIn, account.userId, currentPlan, activeWorkspaceId) {
        if (ownerAccessAccountId != account.userId) {
            // Do not carry a previous account's owner/entitlement decision across sign-in.
            ownerAccessAccountId = account.userId
            hasOwnedTeamWorkspace = false
            ownedWorkspaceIds = emptySet()
            activeWorkspaceIsOwnedByUser = false
            ownerCommercialEntitlementActive = null
            activeWorkspaceOwnerCommercialEntitlementActive = null
            ownerAccessCheckMessage = ""
        }
        ownerAccessCheckComplete = false
        refreshOwnerWorkspaceAccess()
    }
    androidx.compose.runtime.LaunchedEffect(account.signedIn, hasOwnedTeamWorkspace, activeWorkspaceId) {
        if (account.signedIn && (hasOwnedTeamWorkspace || hasActiveTeam)) {
            while (true) {
                delay(60_000)
                // Keep the selected team's lock state current while the app remains open.
                refreshOwnerWorkspaceAccess()
            }
        }
    }
    val readOnlyOwnedWorkspaceIds: Set<String> = if (ownerCommercialEntitlementActive == false) ownedWorkspaceIds.toSet() else emptySet()

    // If the owner has no active team selected, their own expired workspace still
    // locks the Workspace page. An invited member's personal plan never overrides the
    // entitlement of a different workspace owner.
    val workspaceAccessLocked = account.signedIn &&
        ownerAccessAccountId == account.userId &&
        ownerAccessCheckComplete &&
        WorkspaceAccessPolicy.shouldLockWorkspacePage(
            hasActiveTeam = hasActiveTeam,
            activeTeamOwnerCommercialEntitlementActive = activeWorkspaceOwnerCommercialEntitlementActive,
            hasOwnedTeamWorkspace = hasOwnedTeamWorkspace,
            ownCommercialEntitlementActive = ownerCommercialEntitlementActive
        )

    val workspaceAccessCouldNotBeVerified = account.signedIn &&
        ownerAccessAccountId == account.userId &&
        ownerAccessCheckComplete &&
        WorkspaceAccessPolicy.cannotVerifyWorkspacePageAccess(
            hasActiveTeam = hasActiveTeam,
            activeTeamOwnerCommercialEntitlementActive = activeWorkspaceOwnerCommercialEntitlementActive,
            hasOwnedTeamWorkspace = hasOwnedTeamWorkspace,
            ownCommercialEntitlementActive = ownerCommercialEntitlementActive
        )

    val currentUserOwnsLockedWorkspace =
        activeWorkspaceIsOwnedByUser || (!hasActiveTeam && hasOwnedTeamWorkspace)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        if (account.signedIn && !ownerAccessCheckComplete) {
            Text(
                "Loading Workspace",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (workspaceAccessLocked || workspaceAccessCouldNotBeVerified) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                if (workspaceAccessLocked) "Workspace locked" else "Workspace access unavailable",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                if (workspaceAccessLocked && currentUserOwnsLockedWorkspace) {
                                    "Your Commercial subscription has expired."
                                } else if (workspaceAccessLocked) {
                                    "The workspace owner's Commercial subscription is inactive."
                                } else {
                                    "BeeKeep couldn't verify the workspace owner's Commercial subscription."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        when {
                            workspaceAccessCouldNotBeVerified ->
                                "This workspace stays locked until BeeKeep can verify its access. Check your connection and try again. Your team records have not been deleted."
                            currentUserOwnsLockedWorkspace ->
                                "Renew Commercial to unlock this workspace. Your hives, apiaries and history are preserved, and queued edits stay on this device until access is restored."
                            else ->
                                "Ask the workspace owner to renew Commercial. Team access will return when the owner's subscription is active again. Your team records remain saved."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (workspaceAccessLocked && activeWorkspaceIsOwnedByUser) {
                        Button(
                            onClick = onUpgrade,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !ownerAccessActionBusy
                        ) { Text("UPGRADE TO COMMERCIAL") }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                ownerAccessActionBusy = true
                                ownerAccessCheckComplete = false
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
                    if (ownerAccessCheckMessage.isNotBlank() && workspaceAccessCouldNotBeVerified) {
                        Text(
                            ownerAccessCheckMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
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
                        .padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                if (hasActiveTeam) {
                                    "Shared apiary records are active on this device. Changes sync with your team."
                                } else {
                                    "Create a shared workspace, invite your crew, and keep every member working from the same records."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                                maxLines = 2
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
                                modifier = Modifier.padding(9.dp).size(32.dp)
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
                                    hasCommercialAccess -> "COMMERCIAL ACCESS"
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
                            if (hasCommercialAccess) {
                                "Commercial includes team creation. You can also join another beekeeper’s workspace with an invitation code."
                            } else {
                                "Joining a team does not require Commercial. Commercial is required only to create a new team workspace."
                            },
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            WorkspaceCloudSyncSection(cloud = cloud)

            if (hasOwnedTeamWorkspace && ownerCommercialEntitlementActive == null && ownerAccessCheckMessage.isNotBlank()) {
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
                readOnlyWorkspaceIds = readOnlyOwnedWorkspaceIds,
                onUpgrade = onUpgrade,
                onWorkspaceMembershipChanged = onWorkspaceMembershipChanged
            )
        }
                Spacer(Modifier.height(8.dp))
            }
        }
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
 * Compact invitation entry kept on More so people can join a team without Commercial.
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
                        "An invitation code is all you need. Commercial is not required to join.",
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
