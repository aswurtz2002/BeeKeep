package com.beekeep.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.BeeKeepWorkspace
import com.beekeep.app.cloud.BeeKeepWorkspaceInvitation
import com.beekeep.app.cloud.BeeKeepWorkspaceInvitationCreated
import com.beekeep.app.cloud.BeeKeepWorkspaceMember
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.isSupportedWorkspaceInvitationCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun WorkspaceManagementSection(
    cloud: SupabaseGateway,
    activity: ComponentActivity,
    currentPlan: BeeKeepPlan,
    readOnlyWorkspaceIds: Set<String> = emptySet(),
    usernameRefreshKey: Int = 0,
    onUpgrade: () -> Unit,
    onWorkspaceMembershipChanged: (Boolean) -> Unit = {}
) {
    val account by cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var workspaces by remember { mutableStateOf<List<BeeKeepWorkspace>>(emptyList()) }
    var selectedWorkspaceId by rememberSaveable {
        mutableStateOf(
            activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                .getString("selected_team_workspace_id", "").orEmpty()
        )
    }
    // Do not infer an active team from shared device preferences before the current
    // account identity has been loaded and matched.
    var activeWorkspaceId by rememberSaveable { mutableStateOf("") }
    var members by remember { mutableStateOf<List<BeeKeepWorkspaceMember>>(emptyList()) }
    var membersLoadedWorkspaceId by remember { mutableStateOf<String?>(null) }
    var memberLoadError by remember { mutableStateOf<String?>(null) }
    var refreshingPeople by remember { mutableStateOf(false) }
    var showCopyPersonalData by rememberSaveable { mutableStateOf(false) }
    var showDangerZone by rememberSaveable { mutableStateOf(false) }
    var showJoinAnotherTeam by rememberSaveable { mutableStateOf(false) }
    var showPendingInvitations by rememberSaveable { mutableStateOf(false) }
    var showInvitePanel by rememberSaveable { mutableStateOf(false) }
    var selectedWorkspaceSection by rememberSaveable { mutableStateOf("overview") }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var invitations by remember { mutableStateOf<List<BeeKeepWorkspaceInvitation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var hasCommercialEntitlement by remember { mutableStateOf(false) }
    var entitlementCheckFailed by remember { mutableStateOf(false) }
    var loadedWorkspaceAccountId by remember { mutableStateOf<String?>(null) }
    var showCommercialRequiredDialog by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newWorkspaceName by rememberSaveable { mutableStateOf("") }
    var inviteEmail by rememberSaveable { mutableStateOf("") }
    var inviteRole by rememberSaveable { mutableStateOf("member") }
    var roleMenuExpanded by remember { mutableStateOf(false) }
    var acceptToken by rememberSaveable { mutableStateOf("") }
    var createdInvite by remember { mutableStateOf<BeeKeepWorkspaceInvitationCreated?>(null) }
    var copyPersonalTarget by remember { mutableStateOf<BeeKeepWorkspace?>(null) }
    var deleteWorkspaceTarget by remember { mutableStateOf<BeeKeepWorkspace?>(null) }
    var removeMemberTarget by remember { mutableStateOf<BeeKeepWorkspaceMember?>(null) }

    val accountWorkspaceDataReady = account.signedIn &&
        !account.userId.isNullOrBlank() &&
        loadedWorkspaceAccountId == account.userId

    val sharedWorkspaces = if (accountWorkspaceDataReady) {
        workspaces.filterNot { it.is_personal }
    } else {
        emptyList()
    }
    val selectedWorkspace = sharedWorkspaces.firstOrNull { it.id == selectedWorkspaceId }
    val selectedWorkspaceReadOnly = selectedWorkspace?.id?.let { it in readOnlyWorkspaceIds } == true

    LaunchedEffect(readOnlyWorkspaceIds, selectedWorkspace?.id) {
        if (selectedWorkspaceReadOnly) {
            selectedWorkspaceSection = "overview"
            showCreateDialog = false
            showCopyPersonalData = false
            showDangerZone = false
            showInvitePanel = false
            copyPersonalTarget = null
            deleteWorkspaceTarget = null
            removeMemberTarget = null
            createdInvite = null
        }
    }
    val canManage = !selectedWorkspaceReadOnly && (selectedWorkspace?.member_role == "owner" || selectedWorkspace?.member_role == "admin")
    val canAssignAdmin = !selectedWorkspaceReadOnly && selectedWorkspace?.member_role == "owner"

    suspend fun refreshWorkspacePeople(workspaceId: String) {
        val result = cloud.listWorkspaceMembers(workspaceId)
        if (result.isSuccess) {
            members = result.value.orEmpty()
            membersLoadedWorkspaceId = workspaceId
            memberLoadError = null
        } else {
            // Do not turn a failed roster request into a misleading "0 people" count.
            memberLoadError = result.error ?: "Couldn't load workspace members."
        }
    }

    suspend fun refreshWorkspaceInvitations(workspace: BeeKeepWorkspace) {
        if (workspace.member_role == "owner" || workspace.member_role == "admin") {
            val result = cloud.listWorkspaceInvitations(workspace.id)
            if (result.isSuccess) {
                // The server marks accepted invites as accepted. Keep only invitations
                // that are still actionable in the UI.
                invitations = result.value.orEmpty().filter {
                    it.status.equals("pending", ignoreCase = true)
                }
            } else {
                message = result.error ?: "Couldn't load invitations."
            }
        } else {
            invitations = emptyList()
        }
    }

    /**
     * Verify entitlement with the server. In debug builds, sync the selected Commercial
     * test plan to the isolated staging backend if the server has not recorded it yet.
     * A sync/verification failure is shown as an error, not a false upgrade prompt.
     */
    suspend fun refreshCommercialEntitlement(): Boolean? {
        val initial = cloud.myActiveCommercialEntitlement()
        if (initial.isSuccess && initial.value == true) {
            hasCommercialEntitlement = true
            entitlementCheckFailed = false
            message = ""
            return true
        }

        val debugCommercialSelected = BuildConfig.DEBUG &&
            (currentPlan == BeeKeepPlan.COMMERCIAL)
        if (debugCommercialSelected) {
            val synced = cloud.syncDebugPlanToStaging(currentPlan)
            if (!synced.isSuccess) {
                hasCommercialEntitlement = false
                entitlementCheckFailed = true
                message = "Commercial is selected on this device, but its BeeKeep-Staging entitlement sync failed: " +
                    (synced.error ?: "unknown error") + ". Re-select Commercial in Plans while online, then retry."
                return null
            }

            val verified = cloud.myActiveCommercialEntitlement()
            if (!verified.isSuccess) {
                hasCommercialEntitlement = false
                entitlementCheckFailed = true
                message = verified.error
                    ?: "The Commercial test plan synced, but BeeKeep could not verify it. Check your connection and retry."
                return null
            }

            hasCommercialEntitlement = verified.value == true
            entitlementCheckFailed = !hasCommercialEntitlement
            if (hasCommercialEntitlement) {
                message = ""
                return true
            }

            message = "The test-plan sync completed, but BeeKeep-Staging still reports no active Commercial entitlement. Re-select Commercial in Plans while online, then retry."
            return null
        }

        if (!initial.isSuccess) {
            hasCommercialEntitlement = false
            entitlementCheckFailed = true
            message = initial.error
                ?: "Couldn't verify your Commercial subscription. Check your connection and try again."
            return null
        }

        hasCommercialEntitlement = false
        entitlementCheckFailed = false
        message = ""
        return false
    }

    LaunchedEffect(account.signedIn, account.userId) {
        val accountIdentityChanged = loadedWorkspaceAccountId != account.userId
        if (accountIdentityChanged) {
            // Do not render the previous account's team roster, invitations, or selection
            // while the new account's memberships are loading.
            loadedWorkspaceAccountId = account.userId
            workspaces = emptyList()
            onWorkspaceMembershipChanged(false)
            selectedWorkspaceId = ""
            activeWorkspaceId = ""
            members = emptyList()
            membersLoadedWorkspaceId = null
            memberLoadError = null
            invitations = emptyList()
            createdInvite = null
            showCopyPersonalData = false
            showDangerZone = false
            showJoinAnotherTeam = false
            showPendingInvitations = false
            showInvitePanel = false
            showCommercialRequiredDialog = false
            showCreateDialog = false
            newWorkspaceName = ""
            inviteEmail = ""
            inviteRole = "member"
            acceptToken = ""
            copyPersonalTarget = null
            deleteWorkspaceTarget = null
            removeMemberTarget = null
            refreshingPeople = false
            loading = false
            hasCommercialEntitlement = false
            entitlementCheckFailed = false
            selectedWorkspaceSection = "overview"
            workspaceMenuExpanded = false
            roleMenuExpanded = false
            message = ""
        }
        if (!account.signedIn || account.userId.isNullOrBlank()) {
            activeWorkspaceId = ""
            hasCommercialEntitlement = false
            entitlementCheckFailed = false
            workspaces = emptyList()
            members = emptyList()
            membersLoadedWorkspaceId = null
            memberLoadError = null
            invitations = emptyList()
            createdInvite = null
            return@LaunchedEffect
        }
        val workspacePrefs = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        val savedWorkspaceOwnerId = workspacePrefs.getString("active_team_workspace_user_id", null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val savedActiveWorkspaceId = workspacePrefs.getString("active_team_workspace_id", "")
            .orEmpty().trim()
        activeWorkspaceId = if (savedWorkspaceOwnerId == account.userId?.trim()) {
            savedActiveWorkspaceId
        } else {
            ""
        }
        val entitlement = cloud.myActiveCommercialEntitlement()
        hasCommercialEntitlement = entitlement.value == true
        entitlementCheckFailed = !entitlement.isSuccess
        loading = true
        val result = cloud.listWorkspaces()
        if (result.isSuccess) {
            workspaces = result.value.orEmpty()
            val available = workspaces.filterNot { it.is_personal }
            onWorkspaceMembershipChanged(available.isNotEmpty())
            activeWorkspaceId = activeWorkspaceId.takeIf { id -> available.any { it.id == id } }.orEmpty()
            selectedWorkspaceId = selectedWorkspaceId.takeIf { id -> available.any { it.id == id } }
                ?: available.firstOrNull()?.id.orEmpty()
            activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                .putString("selected_team_workspace_id", selectedWorkspaceId).apply()
            message = ""
        } else {
            message = result.error ?: "Couldn't load workspaces."
        }
        loading = false
    }

    LaunchedEffect(selectedWorkspaceId, account.signedIn, selectedWorkspace?.member_role, usernameRefreshKey) {
        selectedWorkspaceSection = "overview"
        // Close anchored popups when their old workspace/member rows are replaced.
        roleMenuExpanded = false
        workspaceMenuExpanded = false
        if (!account.signedIn || selectedWorkspaceId.isBlank() || selectedWorkspace == null) {
            members = emptyList()
            membersLoadedWorkspaceId = null
            memberLoadError = null
            invitations = emptyList()
            loading = false
            return@LaunchedEffect
        }

        // Never show the previous workspace's roster under the newly selected workspace.
        members = emptyList()
        membersLoadedWorkspaceId = null
        memberLoadError = null
        loading = true
        var firstLoad = true

        // Membership changes can happen on another phone and do not trigger document sync.
        // Refresh the roster while this screen is open so the People count doesn't stay stale.
        while (true) {
            refreshWorkspacePeople(selectedWorkspaceId)
            // Invitations can be accepted from another phone, so refresh them alongside
            // the member list instead of leaving a stale "pending" entry on this screen.
            refreshWorkspaceInvitations(selectedWorkspace)
            if (firstLoad) {
                loading = false
                firstLoad = false
            }
            delay(15_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WorkspacePanel(
            title = "Your teams"
        ) {
            if (!account.signedIn) {
                Text(
                    "Sign in to BeeKeep Cloud to create or join a team workspace.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            if (sharedWorkspaces.size == 1) "1 team workspace" else "${sharedWorkspaces.size} team workspaces",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Text(
                            "Choose a team to open its shared records and settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                showJoinAnotherTeam = true
                                createdInvite = null
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("JOIN TEAM", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            enabled = !loading && !selectedWorkspaceReadOnly,
                            onClick = {
                                // Recheck server entitlement. Debug Commercial plans are synced only
                                // to BeeKeep-Staging; verification failures are not upgrade denials.
                                scope.launch {
                                    loading = true
                                    when (refreshCommercialEntitlement()) {
                                        true -> {
                                            showCommercialRequiredDialog = false
                                            showCreateDialog = true
                                        }
                                        false -> showCommercialRequiredDialog = true
                                        null -> Unit
                                    }
                                    loading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("CREATE TEAM", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                if (sharedWorkspaces.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "No team workspaces yet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                "Create one for your crew, or join an existing team with an invitation code below. Your personal apiaries stay private.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { workspaceMenuExpanded = true },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        selectedWorkspace?.name ?: "Choose a team workspace",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                    Text(
                                        selectedWorkspace?.let {
                                            "Your role: ${it.member_role.replaceFirstChar { role -> role.uppercase() }}"
                                        } ?: "Select a workspace to manage",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("CHANGE ▾", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        DropdownMenu(
                            expanded = workspaceMenuExpanded,
                            onDismissRequest = { workspaceMenuExpanded = false }
                        ) {
                            sharedWorkspaces.forEach { workspace ->
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                workspace.name,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                            )
                                            Text(
                                                "Role: ${workspace.member_role.replaceFirstChar { it.uppercase() }}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (activeWorkspaceId == workspace.id) {
                                            WorkspaceTag("ACTIVE", active = true)
                                        } else if (selectedWorkspaceId == workspace.id) {
                                            WorkspaceTag("SELECTED")
                                        }
                                    },
                                    onClick = {
                                        selectedWorkspaceId = workspace.id
                                        roleMenuExpanded = false
                                        activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                                            .putString("selected_team_workspace_id", workspace.id).apply()
                                        createdInvite = null
                                        message = ""
                                        workspaceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                if (loading) {
                    Text(
                        "Updating workspace details…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

            if (accountWorkspaceDataReady && selectedWorkspaceReadOnly) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Workspace read-only", fontWeight = FontWeight.Bold)
                        Text(
                            "Commercial is inactive for this workspace owner. Existing team records remain available to view and export; invitations, member changes, copying and deletion are paused until Commercial is active again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (accountWorkspaceDataReady && selectedWorkspace != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WorkspaceSectionTab(
                        label = "Overview",
                        selected = selectedWorkspaceSection == "overview",
                        modifier = Modifier.weight(1f)
                    ) { selectedWorkspaceSection = "overview" }
                    WorkspaceSectionTab(
                        label = "People",
                        selected = selectedWorkspaceSection == "people",
                        modifier = Modifier.weight(1f)
                    ) { selectedWorkspaceSection = "people" }
                    if (selectedWorkspace?.member_role == "owner") {
                        WorkspaceSectionTab(
                            label = "Actions",
                            selected = selectedWorkspaceSection == "actions",
                            modifier = Modifier.weight(1f)
                        ) { selectedWorkspaceSection = "actions" }
                    }
                    if (!selectedWorkspaceReadOnly && (selectedWorkspace?.member_role == "owner" || selectedWorkspace?.member_role == "admin")) {
                        WorkspaceSectionTab(
                            label = "Settings",
                            selected = selectedWorkspaceSection == "settings",
                            modifier = Modifier.weight(1f)
                        ) { selectedWorkspaceSection = "settings" }
                    }
                }
            }

            if (selectedWorkspace == null || selectedWorkspaceSection == "overview") {
            WorkspacePanel(
                title = if (sharedWorkspaces.isEmpty()) "Join a team" else "Join another team",
                description = if (sharedWorkspaces.isEmpty()) {
                    "Enter the invitation code you received."
                } else {
                    "Have an invitation code for another team?"
                },
                collapsible = sharedWorkspaces.isNotEmpty(),
                expanded = showJoinAnotherTeam || sharedWorkspaces.isEmpty(),
                onToggle = { showJoinAnotherTeam = !showJoinAnotherTeam }
            ) {
                OutlinedTextField(
                    value = acceptToken,
                    onValueChange = { value -> acceptToken = value.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Invitation code") },
                    placeholder = { Text("Bee123456") },
                    singleLine = true
                )
                Text(
                    "Use the BeeKeep account email the invitation was sent to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    enabled = account.signedIn && !loading && isSupportedWorkspaceInvitationCode(acceptToken),
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = cloud.acceptWorkspaceInvitation(acceptToken)
                            if (result.isSuccess && result.value != null) {
                                val joined = result.value!!
                                acceptToken = ""
                                createdInvite = null
                                val refreshed = cloud.listWorkspaces()
                                if (refreshed.isSuccess) workspaces = refreshed.value.orEmpty()
                                selectedWorkspaceId = joined.workspace_id
                                activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                                    .putString("selected_team_workspace_id", joined.workspace_id).apply()
                                val activation = cloud.activateWorkspace(joined.workspace_id)
                                activeWorkspaceId = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                                    .getString("active_team_workspace_id", "").orEmpty()
                                message = activation.error
                                    ?: "Joined ${joined.workspace_name} as ${joined.workspace_role}. Team data is now active."
                                onWorkspaceMembershipChanged(true)
                            } else {
                                message = result.error ?: "Couldn't accept this invitation."
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("JOIN WORKSPACE") }
            }
            }

        if (account.signedIn) {
            selectedWorkspace?.let { workspace ->
                if (selectedWorkspaceSection == "overview") {
                    val isActiveWorkspace = activeWorkspaceId == workspace.id
                    WorkspacePanel(
                    title = "Team status",
                    description = "${workspace.name} · ${workspace.member_role.replaceFirstChar { it.uppercase() }}"
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (isActiveWorkspace) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    if (isActiveWorkspace) "This team is active" else "This team is not active",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = if (isActiveWorkspace) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    if (isActiveWorkspace) {
                                        if (workspace.member_role == "viewer") {
                                            "You're viewing shared records in read-only mode."
                                        } else {
                                            "Apiary and hive changes sync with members of this team."
                                        }
                                    } else {
                                        "Activate this workspace to load the team's apiaries and hives on this device."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isActiveWorkspace) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            WorkspaceTag(if (isActiveWorkspace) "ACTIVE" else "INACTIVE", active = isActiveWorkspace)
                        }
                    }

                    if (!isActiveWorkspace) {
                        Button(
                            enabled = !loading,
                            onClick = {
                                scope.launch {
                                    loading = true
                                    val result = cloud.activateWorkspace(workspace.id)
                                    activeWorkspaceId = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                                        .getString("active_team_workspace_id", "").orEmpty()
                                    message = result.error ?: "Now using ${workspace.name}. Team data synced."
                                    loading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (workspace.member_role == "viewer") "OPEN TEAM" else "SWITCH TO THIS TEAM")
                        }
                    }

                    if (workspace.member_role == "viewer") {
                        Text(
                            "View-only access",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (activeWorkspaceId.isNotBlank()) {
                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                scope.launch {
                                    loading = true
                                    val result = cloud.activateWorkspace(null)
                                    activeWorkspaceId = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                                        .getString("active_team_workspace_id", "").orEmpty()
                                    message = result.error ?: "Switched back to your personal workspace."
                                    loading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("SWITCH BACK TO PERSONAL DATA") }
                    }
                }

                }
                
                if (!selectedWorkspaceReadOnly && selectedWorkspaceSection == "settings" && workspace.member_role in setOf("owner", "admin")) {
                    WorkspacePanel(
                        title = "Copy personal data",
                        description = "Optional · Import your private apiaries and hives",
                        collapsible = true,
                        expanded = showCopyPersonalData,
                        onToggle = { showCopyPersonalData = !showCopyPersonalData }
                    ) {
                        Text(
                            "Copies your latest cloud-synced apiaries, hives, and history into this team. Your original personal records stay untouched. If any record IDs conflict with team data, the copy stops rather than overwriting anything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            enabled = !loading,
                            onClick = { copyPersonalTarget = workspace },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("COPY PERSONAL DATA INTO TEAM") }
                        Text(
                            "After copying, this device switches to the team workspace.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selectedWorkspaceSection == "actions" && workspace.member_role == "owner") {
                    WorkspaceActionsPanel(
                        cloud = cloud,
                        workspaceId = workspace.id,
                        isOwner = true
                    )
                }

                if (selectedWorkspaceSection == "people") {
                    WorkspacePanel(
                        title = "Team members",
                        description = when {
                            membersLoadedWorkspaceId != workspace.id && memberLoadError != null -> "Couldn't load people"
                            membersLoadedWorkspaceId != workspace.id -> "Loading people…"
                            else -> "${members.size} ${if (members.size == 1) "person" else "people"} in this workspace"
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                enabled = !refreshingPeople && !loading,
                                onClick = {
                                    scope.launch {
                                        refreshingPeople = true
                                        try {
                                            refreshWorkspacePeople(workspace.id)
                                            refreshWorkspaceInvitations(workspace)
                                        } finally {
                                            refreshingPeople = false
                                        }
                                    }
                                }
                            ) {
                                Text(if (refreshingPeople) "REFRESHING…" else "REFRESH")
                            }
                        }
                        if (memberLoadError != null) {
                            Text(
                                if (membersLoadedWorkspaceId == workspace.id) {
                                    "Couldn't refresh the roster. Showing the last loaded list."
                                } else {
                                    "The member list couldn't be loaded."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (membersLoadedWorkspaceId != workspace.id && memberLoadError == null) {
                            Text(
                                "Loading members…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (membersLoadedWorkspaceId != workspace.id && memberLoadError != null) {
                            Text(
                                "Use REFRESH to try loading the member list again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (members.isEmpty()) {
                            Text(
                                if (memberLoadError != null) "The member list is empty, but the latest refresh failed."
                                else "No member details are available yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                members.forEach { member ->
                                    key(member.user_id) {
                                        WorkspaceMemberCard(
                                            member = member,
                                            canManage = canManage,
                                            canAssignAdmin = canAssignAdmin,
                                            enabled = !loading && !selectedWorkspaceReadOnly,
                                            onChangeRole = { newRole ->
                                                scope.launch {
                                                    val result = cloud.updateWorkspaceMemberRole(workspace.id, member.user_id, newRole)
                                                    if (result.isSuccess) {
                                                        message = "Member role updated."
                                                        refreshWorkspacePeople(workspace.id)
                                                    } else {
                                                        message = result.error ?: "Couldn't change the role."
                                                    }
                                                }
                                            },
                                            onRemove = { removeMemberTarget = member }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!selectedWorkspaceReadOnly && selectedWorkspaceSection == "settings" && workspace.member_role == "owner") {
                    WorkspacePanel(
                        title = "Delete team workspace",
                        description = "Permanent action · Owner only",
                        collapsible = true,
                        expanded = showDangerZone,
                        onToggle = { showDangerZone = !showDangerZone }
                    ) {
                        Text(
                            "This removes the team workspace, its shared hive and apiary records, invitations, and member access. Your personal workspace and personal records are not deleted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = !loading,
                            onClick = { deleteWorkspaceTarget = workspace },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("DELETE TEAM WORKSPACE") }
                    }
                }

                if (selectedWorkspaceSection == "people" && canManage) {
                    WorkspacePanel(
                        title = "Invite team members",
                        collapsible = true,
                        expanded = showInvitePanel,
                        onToggle = {
                            val opening = !showInvitePanel
                            showInvitePanel = opening
                            if (opening && workspace.member_role == "owner") {
                                scope.launch {
                                    loading = true
                                    refreshCommercialEntitlement()
                                    loading = false
                                }
                            }
                        }
                    ) {
                        if (workspace.member_role == "owner" && !hasCommercialEntitlement && loading) {
                            Text(
                                "Checking your Commercial subscription…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (workspace.member_role == "owner" && !hasCommercialEntitlement && entitlementCheckFailed) {
                            Text(
                                "BeeKeep couldn't verify Commercial access. Check your connection and retry instead of upgrading again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            OutlinedButton(
                                enabled = !loading,
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        refreshCommercialEntitlement()
                                        loading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("RETRY COMMERCIAL CHECK") }
                        } else if (workspace.member_role == "owner" && !hasCommercialEntitlement) {
                            Text(
                                "An active BeeKeep Commercial subscription is required for workspace owners to invite teammates.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onUpgrade,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("UPGRADE TO COMMERCIAL") }
                        } else {
                        OutlinedTextField(
                            value = inviteEmail,
                            onValueChange = { inviteEmail = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("BeeKeep account email") },
                            singleLine = true
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { roleMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Invitation role: ${inviteRole.replaceFirstChar { it.uppercase() }}") }
                            DropdownMenu(
                                expanded = roleMenuExpanded,
                                onDismissRequest = { roleMenuExpanded = false }
                            ) {
                                (if (canAssignAdmin) listOf("admin", "member", "viewer") else listOf("member", "viewer"))
                                    .forEach { role ->
                                        DropdownMenuItem(
                                            text = { Text(role.replaceFirstChar { it.uppercase() }) },
                                            onClick = { inviteRole = role; roleMenuExpanded = false }
                                        )
                                    }
                            }
                        }
                        Button(
                            enabled = inviteEmail.contains("@") && !loading,
                            onClick = {
                                scope.launch {
                                    val result = cloud.createWorkspaceInvitation(workspace.id, inviteEmail, inviteRole)
                                    val invite = result.value
                                    if (result.isSuccess && invite != null) {
                                        createdInvite = invite
                                        message = "Invitation created. Share the code with the invited person."
                                        inviteEmail = ""
                                        refreshWorkspaceInvitations(workspace)
                                        showPendingInvitations = invitations.isNotEmpty()
                                    } else {
                                        message = result.error ?: "Couldn't create the invitation."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("CREATE INVITATION") }

                        createdInvite?.let { invite ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Invitation ready",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    Text("For ${invite.email}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        invite.invite_token,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        "This code expires in seven days. Copy or share it now; it won't be shown again after leaving this screen.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("BeeKeep invitation", invite.invite_token))
                                                Toast.makeText(activity, "Invitation code copied", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("COPY CODE") }
                                        Button(
                                            onClick = {
                                                val shareText = "You've been invited to the BeeKeep workspace ${workspace.name}.\n\nSign in to BeeKeep Cloud using ${invite.email}, then open More → Team workspaces → Join a team and enter this code:\n${invite.invite_token}\n\nThis invitation expires in seven days."
                                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, "BeeKeep workspace invitation")
                                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                                }
                                                activity.startActivity(Intent.createChooser(sendIntent, "Share BeeKeep invitation"))
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("SHARE") }
                                    }
                                }
                            }
                        }
                        }
                    }

                    if (invitations.isNotEmpty()) WorkspacePanel(
                        title = "Pending invitations (${invitations.size})",
                        description = "Review or cancel outstanding invitations.",
                        collapsible = true,
                        expanded = showPendingInvitations,
                        onToggle = { showPendingInvitations = !showPendingInvitations }
                    ) {
                        if (invitations.isEmpty()) {
                            Text(
                                "No pending invitations.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            invitations.forEachIndexed { index, invitation ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(invitation.email, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${invitation.role.replaceFirstChar { it.uppercase() }} · expires ${invitation.expires_at.take(10)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!selectedWorkspaceReadOnly) {
                                        TextButton(
                                            enabled = !loading,
                                            onClick = {
                                                scope.launch {
                                                    val result = cloud.cancelWorkspaceInvitation(invitation.id)
                                                    if (result.isSuccess) {
                                                        refreshWorkspaceInvitations(workspace)
                                                        message = "Invitation cancelled."
                                                    } else {
                                                        message = result.error ?: "Couldn't cancel the invitation."
                                                    }
                                                }
                                            }
                                        ) { Text("CANCEL") }
                                    }
                                }
                                if (index < invitations.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }



            if (message.isNotBlank()) {
                val messageIsError = listOf(
                    "couldn't", "could not", "failed", "error", "denied", "not configured",
                    "sign in with", "expired", "invalid", "not active", "too many", "invitation code"
                ).any { message.contains(it, ignoreCase = true) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Text(
                "Personal records stay private unless you explicitly copy them into a team. Team workspaces have separate data and member permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (accountWorkspaceDataReady && !selectedWorkspaceReadOnly) copyPersonalTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!loading) copyPersonalTarget = null },
            title = { Text("Copy personal records to ${target.name}?") },
            text = {
                Text(
                    "This copies your latest cloud-synced apiaries, hives and related history into this team workspace so its members can see them. " +
                        "Your original personal records remain private. If any record IDs conflict with existing team records, the copy is stopped without overwriting anything."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = cloud.copyPersonalRecordsToWorkspace(target.id)
                            activeWorkspaceId = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                                .getString("active_team_workspace_id", "").orEmpty()
                            message = when {
                                result.value != null && result.error != null ->
                                    "Copied ${result.value} records. ${result.error}"
                                result.error != null -> result.error
                                else -> "Copied ${result.value ?: 0} personal records to ${target.name}. Team data is now active."
                            }
                            copyPersonalTarget = null
                            loading = false
                        }
                    }
                ) { Text("COPY RECORDS") }
            },
            dismissButton = {
                TextButton(
                    enabled = !loading,
                    onClick = { copyPersonalTarget = null }
                ) { Text("CANCEL") }
            }
        )
    }

    if (accountWorkspaceDataReady && !selectedWorkspaceReadOnly) removeMemberTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!loading) removeMemberTarget = null },
            title = { Text("Remove ${target.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: target.email}?") },
            text = {
                Text(
                    "This removes the person from this team workspace, revoking access to its shared apiaries and hives. " +
                        "Their account and personal workspace are not deleted."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = cloud.removeWorkspaceMember(
                                target.workspace_id,
                                target.user_id
                            )
                            if (result.isSuccess) {
                                message = "Member removed from the workspace."
                                refreshWorkspacePeople(target.workspace_id)
                            } else {
                                message = result.error ?: "Couldn't remove the member."
                            }
                            removeMemberTarget = null
                            loading = false
                        }
                    }
                ) { Text("REMOVE MEMBER", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !loading,
                    onClick = { removeMemberTarget = null }
                ) { Text("CANCEL") }
            }
        )
    }

    if (accountWorkspaceDataReady && !selectedWorkspaceReadOnly) deleteWorkspaceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!loading) deleteWorkspaceTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    "This permanently deletes this team workspace, its shared records, invitations, and member access. " +
                        "It does not delete your personal workspace or personal records. This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            val prefs = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                            var canDelete = true
                            if (activeWorkspaceId == target.id) {
                                val switchResult = cloud.activateWorkspace(null)
                                activeWorkspaceId = prefs.getString("active_team_workspace_id", "").orEmpty()
                                if (!switchResult.isSuccess) {
                                    message = switchResult.error ?: "Switch to personal data before deleting this workspace."
                                    canDelete = false
                                }
                            }

                            if (canDelete) {
                                val result = cloud.deleteWorkspace(target.id)
                                if (result.isSuccess) {
                                    val refreshed = cloud.listWorkspaces()
                                    val remainingWorkspaces = if (refreshed.isSuccess) {
                                        refreshed.value.orEmpty()
                                    } else {
                                        workspaces.filterNot { it.id == target.id }
                                    }
                                    workspaces = remainingWorkspaces
                                    val remainingTeams = remainingWorkspaces.filterNot { it.is_personal }
                                    selectedWorkspaceId = remainingTeams.firstOrNull()?.id.orEmpty()
                                    prefs.edit()
                                        .putString("selected_team_workspace_id", selectedWorkspaceId)
                                        .apply()
                                    invitations = emptyList()
                                    members = emptyList()
                                    createdInvite = null
                                    message = "Deleted ${target.name}. Your personal records are untouched."
                                } else {
                                    message = result.error ?: "Couldn't delete the workspace."
                                }
                            }
                            deleteWorkspaceTarget = null
                            loading = false
                        }
                    }
                ) { Text("DELETE PERMANENTLY") }
            },
            dismissButton = {
                TextButton(
                    enabled = !loading,
                    onClick = { deleteWorkspaceTarget = null }
                ) { Text("CANCEL") }
            }
        )
    }

    if (accountWorkspaceDataReady && showCommercialRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showCommercialRequiredDialog = false },
            title = { Text("Commercial required") },
            text = {
                Text(
                    "An active BeeKeep Commercial subscription is required to create and own a team workspace. " +
                        "People you invite can join and use your team workspace without buying Commercial themselves."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCommercialRequiredDialog = false
                        onUpgrade()
                    }
                ) { Text("UPGRADE TO COMMERCIAL") }
            },
            dismissButton = {
                TextButton(onClick = { showCommercialRequiredDialog = false }) {
                    Text("NOT NOW")
                }
            }
        )
    }

    if (accountWorkspaceDataReady && !selectedWorkspaceReadOnly && showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create team workspace") },
            text = {
                OutlinedTextField(
                    value = newWorkspaceName,
                    onValueChange = { newWorkspaceName = it.take(80) },
                    label = { Text("Workspace name") },
                    placeholder = { Text("e.g. North Apiaries Team") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newWorkspaceName.isNotBlank() && !selectedWorkspaceReadOnly,
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = cloud.createWorkspace(newWorkspaceName)
                            val workspace = result.value
                            if (result.isSuccess && workspace != null) {
                                newWorkspaceName = ""
                                showCreateDialog = false
                                val refreshed = cloud.listWorkspaces()
                                if (refreshed.isSuccess) workspaces = refreshed.value.orEmpty()
                                selectedWorkspaceId = workspace.id
                                activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                                    .putString("selected_team_workspace_id", workspace.id).apply()
                                onWorkspaceMembershipChanged(true)
                                message = "Workspace created. Invite team members when ready."
                            } else {
                                message = result.error ?: "Couldn't create workspace."
                            }
                            loading = false
                        }
                    }
                ) { Text("CREATE") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("CANCEL") }
            }
        )
    }
}


@Composable
private fun WorkspaceSectionTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.ExtraBold else androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WorkspaceMemberCard(
    member: BeeKeepWorkspaceMember,
    canManage: Boolean,
    canAssignAdmin: Boolean,
    enabled: Boolean,
    onChangeRole: (String) -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by rememberSaveable(member.user_id) { mutableStateOf(false) }
    val displayName = member.username.takeIf { it.isNotBlank() }
        ?.let { "@$it" }
        ?: member.email.ifBlank { member.user_id.take(8) + "…" }
    val initialsSource = member.username.takeIf { it.isNotBlank() }
        ?: member.email.substringBefore("@").ifBlank { "Bee" }
    val initials = remember(initialsSource) {
        initialsSource
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "B" }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (member.is_current_user) WorkspaceTag("YOU")
                }
                if (member.username.isNotBlank() && member.email.isNotBlank()) {
                    Text(
                        member.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                WorkspaceTag(member.role, active = member.role == "owner")
            }
            if (canManage && !member.is_current_user && member.role != "owner") {
                Box {
                    IconButton(
                        enabled = enabled,
                        onClick = { menuExpanded = true }
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Manage $displayName")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        val roles = if (canAssignAdmin) {
                            listOf("admin", "member", "viewer")
                        } else {
                            listOf("member", "viewer")
                        }
                        roles.filter { it != member.role }.forEach { newRole ->
                            DropdownMenuItem(
                                text = { Text("Make ${newRole.replaceFirstChar { it.uppercase() }}") },
                                onClick = {
                                    menuExpanded = false
                                    onChangeRole(newRole)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove member", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WorkspacePanel(
    title: String,
    description: String? = null,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (!collapsible || expanded) 12.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    if (!description.isNullOrBlank()) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (collapsible) {
                    TextButton(onClick = { onToggle?.invoke() }) {
                        Text(if (expanded) "CLOSE" else "OPEN")
                    }
                }
            }
            if (!collapsible || expanded) content()
        }
    }
}

@Composable
private fun WorkspaceTag(label: String, active: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            label.uppercase(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}
