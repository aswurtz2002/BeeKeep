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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
fun WorkspaceManagementSection(cloud: SupabaseGateway, activity: ComponentActivity) {
    val account by cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var workspaces by remember { mutableStateOf<List<BeeKeepWorkspace>>(emptyList()) }
    var selectedWorkspaceId by rememberSaveable { mutableStateOf("") }
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

    val sharedWorkspaces = workspaces.filterNot { it.is_personal }
    val selectedWorkspace = sharedWorkspaces.firstOrNull { it.id == selectedWorkspaceId }
    val canManage = selectedWorkspace?.member_role == "owner" || selectedWorkspace?.member_role == "admin"
    val canAssignAdmin = selectedWorkspace?.member_role == "owner"

    LaunchedEffect(account.signedIn) {
        if (!account.signedIn) {
            workspaces = emptyList()
            selectedWorkspaceId = ""
            members = emptyList()
            invitations = emptyList()
            createdInvite = null
            return@LaunchedEffect
        }
        loading = true
        val result = cloud.listWorkspaces()
        if (result.isSuccess) {
            workspaces = result.value.orEmpty()
            val available = workspaces.filterNot { it.is_personal }
            selectedWorkspaceId = selectedWorkspaceId.takeIf { id -> available.any { it.id == id } }
                ?: available.firstOrNull()?.id.orEmpty()
            message = ""
        } else {
            message = result.error ?: "Couldn't load workspaces."
        }
        loading = false
    }

    LaunchedEffect(selectedWorkspaceId, account.signedIn, selectedWorkspace?.member_role) {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Team workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(
                "Create a workspace for your beekeeping team, invite people by email, and control their roles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!account.signedIn) {
                Text("Sign in under BeeKeep Cloud to manage team workspaces.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showCreateDialog = true }) { Text("CREATE WORKSPACE") }
                    if (loading) Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (sharedWorkspaces.isEmpty()) {
                    Text(
                        "No team workspaces yet. Your existing personal workspace remains private.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("YOUR TEAM WORKSPACES", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    sharedWorkspaces.forEach { workspace ->
                        OutlinedButton(
                            onClick = {
                                selectedWorkspaceId = workspace.id
                                createdInvite = null
                                message = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                (if (selectedWorkspaceId == workspace.id) "✓ " else "") +
                                    workspace.name + "  ·  " + workspace.member_role.uppercase()
                            )
                        }
                    }
                }

                selectedWorkspace?.let { workspace ->
                    Spacer(Modifier.width(2.dp))
                    Text(workspace.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("MEMBERS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (members.isEmpty()) {
                        Text("No members loaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    members.forEach { member ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        member.email.ifBlank { member.user_id.take(8) + "…" } +
                                            if (member.is_current_user) " (You)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                    Text(member.role.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (canManage && !member.is_current_user && member.role != "owner") {
                                    Box {
                                        TextButton(onClick = { memberMenuId = member.user_id }) { Text("MANAGE") }
                                        DropdownMenu(
                                            expanded = memberMenuId == member.user_id,
                                            onDismissRequest = { memberMenuId = null }
                                        ) {
                                            val roles = if (canAssignAdmin) listOf("admin", "member", "viewer") else listOf("member", "viewer")
                                            roles.filter { it != member.role }.forEach { newRole ->
                                                DropdownMenuItem(
                                                    text = { Text("Make ${newRole.replaceFirstChar { it.uppercase() }}") },
                                                    onClick = {
                                                        memberMenuId = null
                                                        scope.launch {
                                                            val result = cloud.updateWorkspaceMemberRole(workspace.id, member.user_id, newRole)
                                                            if (result.isSuccess) {
                                                                message = "Member role updated."
                                                                val reloaded = cloud.listWorkspaceMembers(workspace.id)
                                                                members = reloaded.value.orEmpty()
                                                            } else message = result.error ?: "Couldn't change the role."
                                                        }
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Remove member", color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    memberMenuId = null
                                                    scope.launch {
                                                        val result = cloud.removeWorkspaceMember(workspace.id, member.user_id)
                                                        if (result.isSuccess) {
                                                            message = "Member removed from the workspace."
                                                            members = cloud.listWorkspaceMembers(workspace.id).value.orEmpty()
                                                        } else message = result.error ?: "Couldn't remove the member."
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (canManage) {
                        Text("INVITE A BEEKEEPER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = inviteEmail,
                            onValueChange = { inviteEmail = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Their BeeKeep account email") },
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                OutlinedButton(onClick = { roleMenuExpanded = true }) {
                                    Text("Role: ${inviteRole.replaceFirstChar { it.uppercase() }}")
                                }
                                DropdownMenu(
                                    expanded = roleMenuExpanded,
                                    onDismissRequest = { roleMenuExpanded = false }
                                ) {
                                    (if (canAssignAdmin) listOf("admin", "member", "viewer") else listOf("member", "viewer")).forEach { role ->
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
                                            val list = cloud.listWorkspaceInvitations(workspace.id)
                                            invitations = list.value.orEmpty()
                                        } else message = result.error ?: "Couldn't create the invitation."
                                    }
                                }
                            ) { Text("CREATE INVITE") }
                        }
                        createdInvite?.let { invite ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Invite for ${invite.email}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text("One-time code. Expires in seven days. Share it privately; it will not be shown again after leaving this screen.")
                                    Text(invite.invite_token, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("BeeKeep invitation", invite.invite_token))
                                            Toast.makeText(activity, "Invitation code copied", Toast.LENGTH_SHORT).show()
                                        }) { Text("COPY CODE") }
                                        Button(onClick = {
                                            val shareText = "You've been invited to the BeeKeep workspace ${workspace.name}.\n\nSign in to BeeKeep Cloud using ${invite.email}, then open More → Team workspaces → Accept invitation and enter this code:\n${invite.invite_token}\n\nThis invitation expires in seven days."
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "BeeKeep workspace invitation")
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            }
                                            activity.startActivity(Intent.createChooser(sendIntent, "Share BeeKeep invitation"))
                                        }) { Text("SHARE INVITE") }
                                    }
                                }
                            }
                        }

                        Text("PENDING INVITATIONS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (invitations.isEmpty()) {
                            Text("No pending invitations.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        invitations.forEach { invitation ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(invitation.email, style = MaterialTheme.typography.bodyMedium)
                                    Text("${invitation.role.uppercase()} · expires ${invitation.expires_at.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val result = cloud.cancelWorkspaceInvitation(invitation.id)
                                        if (result.isSuccess) {
                                            invitations = cloud.listWorkspaceInvitations(workspace.id).value.orEmpty()
                                            message = "Invitation cancelled."
                                        } else message = result.error ?: "Couldn't cancel invitation."
                                    }
                                }) { Text("CANCEL") }
                            }
                        }
                    }
                }

                Text("ACCEPT AN INVITATION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Use the same email address the invitation was created for. Codes expire after seven days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = acceptToken,
                    onValueChange = { acceptToken = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Invitation code (8 characters)") },
                    singleLine = true
                )
                Button(
                    enabled = acceptToken.length >= 32 && !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = cloud.acceptWorkspaceInvitation(acceptToken)
                            if (result.isSuccess && result.value != null) {
                                val joined = result.value!!
                                acceptToken = ""
                                createdInvite = null
                                message = "Joined ${joined.workspace_name} as ${joined.workspace_role}."
                                val refreshed = cloud.listWorkspaces()
                                if (refreshed.isSuccess) {
                                    workspaces = refreshed.value.orEmpty()
                                    selectedWorkspaceId = joined.workspace_id
                                }
                            } else message = result.error ?: "Couldn't accept this invitation."
                            loading = false
                        }
                    }
                ) { Text("ACCEPT INVITATION") }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = if (message.contains("couldn't", ignoreCase = true) || message.contains("error", ignoreCase = true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Stage 2 sets up workspace access and roles. Hive and apiary records still sync privately to each account until shared-record sync is added and tested in the next stage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
