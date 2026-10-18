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
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.BeeKeepWorkspace
import com.beekeep.app.cloud.BeeKeepWorkspaceInvitation
import com.beekeep.app.cloud.BeeKeepWorkspaceInvitationCreated
import com.beekeep.app.cloud.BeeKeepWorkspaceMember
import com.beekeep.app.cloud.SupabaseGateway
import kotlinx.coroutines.launch

@Composable
fun WorkspaceManagementSection(
    cloud: SupabaseGateway,
    activity: ComponentActivity,
    usernameRefreshKey: Int = 0
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
    var activeWorkspaceId by rememberSaveable {
        mutableStateOf(
            activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                .getString("active_team_workspace_id", "").orEmpty()
        )
    }
    var members by remember { mutableStateOf<List<BeeKeepWorkspaceMember>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<BeeKeepWorkspaceInvitation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newWorkspaceName by rememberSaveable { mutableStateOf("") }
    var inviteEmail by rememberSaveable { mutableStateOf("") }
    var inviteRole by rememberSaveable { mutableStateOf("member") }
    var roleMenuExpanded by remember { mutableStateOf(false) }
    var acceptToken by rememberSaveable { mutableStateOf("") }
    var createdInvite by remember { mutableStateOf<BeeKeepWorkspaceInvitationCreated?>(null) }
    var memberMenuId by remember { mutableStateOf<String?>(null) }
    var copyPersonalTarget by remember { mutableStateOf<BeeKeepWorkspace?>(null) }
    var deleteWorkspaceTarget by remember { mutableStateOf<BeeKeepWorkspace?>(null) }
    var removeMemberTarget by remember { mutableStateOf<BeeKeepWorkspaceMember?>(null) }

    val sharedWorkspaces = workspaces.filterNot { it.is_personal }
    val selectedWorkspace = sharedWorkspaces.firstOrNull { it.id == selectedWorkspaceId }
    val canManage = selectedWorkspace?.member_role == "owner" || selectedWorkspace?.member_role == "admin"
    val canAssignAdmin = selectedWorkspace?.member_role == "owner"

    LaunchedEffect(account.signedIn) {
        if (!account.signedIn) {
            activeWorkspaceId = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                .getString("active_team_workspace_id", "").orEmpty()
            workspaces = emptyList()
            members = emptyList()
            invitations = emptyList()
            createdInvite = null
            return@LaunchedEffect
        }
        activeWorkspaceId = activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            .getString("active_team_workspace_id", "").orEmpty()
        loading = true
        val result = cloud.listWorkspaces()
        if (result.isSuccess) {
            workspaces = result.value.orEmpty()
            val available = workspaces.filterNot { it.is_personal }
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
        if (!account.signedIn || selectedWorkspaceId.isBlank() || selectedWorkspace == null) {
            members = emptyList()
            invitations = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val memberResult = cloud.listWorkspaceMembers(selectedWorkspaceId)
        members = memberResult.value.orEmpty()
        if (!memberResult.isSuccess) message = memberResult.error ?: "Couldn't load workspace members."
        if (selectedWorkspace.member_role == "owner" || selectedWorkspace.member_role == "admin") {
            val inviteResult = cloud.listWorkspaceInvitations(selectedWorkspaceId)
            invitations = inviteResult.value.orEmpty()
            if (!inviteResult.isSuccess) message = inviteResult.error ?: "Couldn't load invitations."
        } else {
            invitations = emptyList()
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WorkspacePanel(
            title = "Team workspaces",
            description = "Manage your shared apiaries, hive records, people, and invitations."
        ) {
            if (!account.signedIn) {
                Text(
                    "Sign in to BeeKeep Cloud to create or join a team workspace.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (sharedWorkspaces.size == 1) "1 team workspace" else "${sharedWorkspaces.size} team workspaces",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Text(
                            "Choose one below to manage it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        enabled = !loading,
                        onClick = { showCreateDialog = true }
                    ) { Text("CREATE") }
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
                    sharedWorkspaces.forEach { workspace ->
                        val selected = selectedWorkspaceId == workspace.id
                        val active = activeWorkspaceId == workspace.id
                        OutlinedButton(
                            onClick = {
                                selectedWorkspaceId = workspace.id
                                activity.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                                    .putString("selected_team_workspace_id", workspace.id).apply()
                                createdInvite = null
                                message = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading || selected
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        workspace.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                    Text(
                                        "Your role: ${workspace.member_role.replaceFirstChar { it.uppercase() }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                when {
                                    active -> WorkspaceTag("ACTIVE", active = true)
                                    selected -> WorkspaceTag("SELECTED")
                                }
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

        if (account.signedIn) {
            selectedWorkspace?.let { workspace ->
                val isActiveWorkspace = activeWorkspaceId == workspace.id
                WorkspacePanel(
                    title = workspace.name,
                    description = "Workspace overview"
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
                                    if (isActiveWorkspace) "Team data is active" else "Team data is not active",
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Your access", style = MaterialTheme.typography.bodyMedium)
                        WorkspaceTag(workspace.member_role.replaceFirstChar { it.uppercase() })
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
                            Text(if (workspace.member_role == "viewer") "OPEN TEAM DATA" else "USE THIS WORKSPACE")
                        }
                    }

                    if (workspace.member_role == "viewer") {
                        Text(
                            "Your role is view-only. You can see the team's records but can't upload edits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (workspace.member_role in setOf("owner", "admin")) {
                    WorkspacePanel(
                        title = "Bring your existing data into this team",
                        description = "Copy your personal apiaries and hives"
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

                WorkspacePanel(
                    title = "People",
                    description = "${members.size} ${if (members.size == 1) "person" else "people"} in this workspace"
                ) {
                    if (members.isEmpty()) {
                        Text(
                            if (loading) "Loading members…" else "No member details are available yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    members.forEachIndexed { index, member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    (member.username.takeIf { it.isNotBlank() }?.let { "@$it" }
                                        ?: member.email.ifBlank { member.user_id.take(8) + "…" }) +
                                        if (member.is_current_user) " (You)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                if (member.username.isNotBlank() && member.email.isNotBlank()) {
                                    Text(
                                        member.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "Role: ${member.role.replaceFirstChar { it.uppercase() }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (canManage && !member.is_current_user && member.role != "owner") {
                                Box {
                                    TextButton(onClick = { memberMenuId = member.user_id }) { Text("MANAGE") }
                                    DropdownMenu(
                                        expanded = memberMenuId == member.user_id,
                                        onDismissRequest = { memberMenuId = null }
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
                                                    memberMenuId = null
                                                    scope.launch {
                                                        val result = cloud.updateWorkspaceMemberRole(workspace.id, member.user_id, newRole)
                                                        if (result.isSuccess) {
                                                            message = "Member role updated."
                                                            members = cloud.listWorkspaceMembers(workspace.id).value.orEmpty()
                                                        } else {
                                                            message = result.error ?: "Couldn't change the role."
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Remove member", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                memberMenuId = null
                                                removeMemberTarget = member
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (index < members.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }

                if (workspace.member_role == "owner") {
                    WorkspacePanel(
                        title = "Delete team workspace",
                        description = "Permanent action • Owner only"
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

                if (canManage) {
                    WorkspacePanel(
                        title = "Invite a beekeeper",
                        description = "Send an invitation to someone with a BeeKeep account."
                    ) {
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
                                        invitations = cloud.listWorkspaceInvitations(workspace.id).value.orEmpty()
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

                    WorkspacePanel(
                        title = "Pending invitations",
                        description = "Invitations that haven't been accepted yet."
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
                                    TextButton(
                                        enabled = !loading,
                                        onClick = {
                                            scope.launch {
                                                val result = cloud.cancelWorkspaceInvitation(invitation.id)
                                                if (result.isSuccess) {
                                                    invitations = cloud.listWorkspaceInvitations(workspace.id).value.orEmpty()
                                                    message = "Invitation cancelled."
                                                } else {
                                                    message = result.error ?: "Couldn't cancel the invitation."
                                                }
                                            }
                                        }
                                    ) { Text("CANCEL") }
                                }
                                if (index < invitations.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }

            WorkspacePanel(
                title = "Join a team",
                description = "Have an invitation code? Enter it here to join a workspace."
            ) {
                Text(
                    "New invitation codes are 8 characters. Older invitation codes will still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = acceptToken,
                    onValueChange = { value -> acceptToken = value.filter { it.isLetterOrDigit() }.uppercase().take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Invitation code") },
                    placeholder = { Text("e.g. A1B2C3D4") },
                    singleLine = true
                )
                Button(
                    enabled = account.signedIn && !loading && (acceptToken.length == 8 || acceptToken.length in 32..128),
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
                            } else {
                                message = result.error ?: "Couldn't accept this invitation."
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("JOIN WORKSPACE") }
            }

            if (activeWorkspaceId.isNotBlank()) {
                WorkspacePanel(
                    title = "Your personal workspace",
                    description = "Switching back won't delete personal or team records."
                ) {
                    Text(
                        "Use this when you want to work on apiaries that are private to your account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    ) { Text("SWITCH TO PERSONAL DATA") }
                }
            }

            if (message.isNotBlank()) {
                val messageIsError = listOf("couldn't", "could not", "failed", "error", "denied", "not configured")
                    .any { message.contains(it, ignoreCase = true) }
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

    copyPersonalTarget?.let { target ->
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

    removeMemberTarget?.let { target ->
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
                                members = cloud.listWorkspaceMembers(target.workspace_id).value.orEmpty()
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

    deleteWorkspaceTarget?.let { target ->
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

    if (showCreateDialog) {
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
                    enabled = newWorkspaceName.isNotBlank(),
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
private fun WorkspacePanel(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
            content()
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
