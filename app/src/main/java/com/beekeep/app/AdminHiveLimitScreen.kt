package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beekeep.app.cloud.SupportAdminHiveLimitResponse
import com.beekeep.app.cloud.SupabaseGateway
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun AdminHiveLimitScreen(
    padding: androidx.compose.foundation.layout.PaddingValues,
    cloud: SupabaseGateway,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsState()
    var checkingAccess by remember { mutableStateOf(true) }
    var authorized by remember { mutableStateOf(false) }
    var accessMessage by remember { mutableStateOf("") }
    var targetEmail by remember { mutableStateOf("") }
    var limitText by remember { mutableStateOf("11000") }
    var reasonText by remember { mutableStateOf("Approved support exception: increased Ultimate hive allowance") }
    var accountResult by remember { mutableStateOf<SupportAdminHiveLimitResponse?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(account.signedIn, account.email) {
        checkingAccess = true
        authorized = false
        accessMessage = ""
        if (!account.signedIn) {
            accessMessage = "Sign in to the authorized BeeKeep support account first."
        } else {
            val result = cloud.checkSupportAdmin()
            authorized = result.value == true
            if (!authorized) {
                accessMessage = result.error
                    ?: "This account has not been approved for support-admin tools."
            }
        }
        checkingAccess = false
    }

    fun runLookup() {
        scope.launch {
            busy = true
            message = ""
            accountResult = null
            val result = cloud.manageSupportHiveLimit(
                action = "lookup",
                email = targetEmail.trim(),
                hiveLimit = null,
                reason = null
            )
            if (result.isSuccess && result.value != null) {
                accountResult = result.value
                limitText = (result.value.customHiveLimit ?: 11000).toString()
                reasonText = result.value.overrideReason
                    ?: "Approved support exception: increased Ultimate hive allowance"
                message = "Account found. Review its entitlement before making changes."
            } else {
                message = result.error ?: "Could not look up that account."
            }
            busy = false
        }
    }

    fun runMutation(action: String) {
        scope.launch {
            busy = true
            message = ""
            val result = cloud.manageSupportHiveLimit(
                action = action,
                email = accountResult?.accountEmail ?: targetEmail.trim(),
                hiveLimit = if (action == "set") limitText.toIntOrNull() else null,
                reason = if (action == "set") reasonText.trim() else null
            )
            if (result.isSuccess && result.value != null) {
                accountResult = result.value
                limitText = (result.value.customHiveLimit ?: 11000).toString()
                message = if (action == "set") {
                    "Custom hive limit saved. The customer's app will pick it up after its cloud entitlement refreshes."
                } else {
                    "Custom override removed. An active Ultimate account returns to the standard 10,000-hive allowance."
                }
            } else {
                message = result.error ?: "The support-admin request failed."
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Support admin", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Ultimate hive-limit exceptions", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onBack) { Text("BACK") }
        }

        if (checkingAccess) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Verifying support access…")
                }
            }
        } else if (!authorized) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Access not authorized", fontWeight = FontWeight.Bold)
                    Text(accessMessage)
                    OutlinedButton(onClick = onBack) { Text("BACK TO MORE") }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Find a customer", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Search by the customer's exact BeeKeep account email. Only one matching account is returned.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetEmail,
                        onValueChange = {
                            targetEmail = it
                            accountResult = null
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Customer email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    Button(
                        enabled = !busy && targetEmail.trim().contains("@"),
                        onClick = { runLookup() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "PLEASE WAIT…" else "LOOK UP ACCOUNT")
                    }
                }
            }

            accountResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Account details", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text(result.accountEmail.orEmpty(), fontWeight = FontWeight.SemiBold)
                        AdminInfoRow("Plan", (result.planCode ?: "unknown").replace('_', ' ').uppercase(Locale.CANADA))
                        AdminInfoRow("Active Ultimate", if (result.isActiveUltimate) "Yes" else "No")
                        AdminInfoRow(
                            "Current custom limit",
                            result.customHiveLimit?.let { String.format(Locale.CANADA, "%,d hives", it) } ?: "None — standard plan limit"
                        )
                        if (!result.expiresAt.isNullOrBlank()) {
                            AdminInfoRow("Entitlement expiry", result.expiresAt)
                        }
                        if (!result.overrideReason.isNullOrBlank()) {
                            AdminInfoRow("Saved reason", result.overrideReason)
                        }
                        if (!result.isActiveUltimate) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "A custom limit can only be assigned while the account has an active Ultimate entitlement. Lookup and removal remain available.",
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Set a custom hive limit", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = limitText,
                            onValueChange = { value -> limitText = value.filter(Char::isDigit).take(7) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Hive limit (10,000–1,000,000)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = reasonText,
                            onValueChange = { reasonText = it.take(500) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Reason for the exception") },
                            minLines = 2,
                            maxLines = 4
                        )
                        Text(
                            "The backend independently verifies the active Ultimate entitlement and enforces the allowed range.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            enabled = !busy &&
                                result.isActiveUltimate &&
                                limitText.toIntOrNull()?.let { it in 10000..1000000 } == true &&
                                reasonText.trim().length in 3..500,
                            onClick = { pendingAction = "set" },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("SAVE CUSTOM LIMIT") }
                        if (result.customHiveLimit != null) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { pendingAction = "clear" },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("REMOVE CUSTOM OVERRIDE") }
                        }
                    }
                }
            }

            if (message.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.contains("failed", ignoreCase = true) ||
                            message.contains("could not", ignoreCase = true) ||
                            message.contains("error", ignoreCase = true)
                        ) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(message, modifier = Modifier.padding(14.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }

    pendingAction?.let { action ->
        val isSet = action == "set"
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAction = null },
            title = { Text(if (isSet) "Confirm custom hive limit" else "Remove custom override") },
            text = {
                Text(
                    if (isSet) {
                        "Save a limit of ${limitText.toIntOrNull()?.let { String.format(Locale.CANADA, "%,d", it) } ?: limitText} hives for ${accountResult?.accountEmail}?\n\nReason: ${reasonText.trim()}"
                    } else {
                        "Remove the custom hive allowance for ${accountResult?.accountEmail}? If their Ultimate entitlement is active, the standard 10,000-hive limit will apply."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingAction = null
                        runMutation(action)
                    }
                ) { Text(if (isSet) "CONFIRM SAVE" else "REMOVE OVERRIDE") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingAction = null }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun AdminInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.weight(0.9f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Medium)
    }
}
