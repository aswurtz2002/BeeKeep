package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.beekeep.app.cloud.SupportAdminCommercialAccessResponse
import com.beekeep.app.cloud.SupportAdminHiveLimitResponse
import com.beekeep.app.cloud.SupabaseGateway
import kotlinx.coroutines.launch
import java.util.Locale

private const val ADMIN_MAX_CUSTOM_HIVE_LIMIT = 1_000_000

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
    var limitText by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf("Approved support exception: increased hive allowance") }
    var accountResult by remember { mutableStateOf<SupportAdminHiveLimitResponse?>(null) }
    var commercialAccessResult by remember { mutableStateOf<SupportAdminCommercialAccessResponse?>(null) }
    var commercialDurationDaysText by remember { mutableStateOf("") }
    var commercialReasonText by remember { mutableStateOf("Approved support grant: Commercial access") }
    var commercialMessage by remember { mutableStateOf("") }
    var commercialMessageIsError by remember { mutableStateOf(false) }
    var pendingCommercialAction by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
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

    fun runLookup(email: String = targetEmail.trim()) {
        scope.launch {
            busy = true
            message = ""
            messageIsError = false
            accountResult = null
            commercialAccessResult = null
            commercialMessage = ""
            commercialMessageIsError = false
            val result = cloud.manageSupportHiveLimit(
                action = "lookup",
                email = email,
                hiveLimit = null,
                reason = null
            )
            if (result.isSuccess && result.value != null) {
                val found = result.value
                accountResult = found
                targetEmail = found.accountEmail.orEmpty()
                limitText = (found.customHiveLimit ?: suggestedHiveLimit(found.baseHiveLimit)).toString()
                reasonText = if (found.customHiveLimit == null) {
                    "Approved support exception: increased hive allowance"
                } else {
                    "Support adjustment following review of the existing custom limit"
                }
                message = "Account loaded. No changes have been made."
            } else {
                message = result.error ?: "Could not look up that account."
                messageIsError = true
            }

            val accessResult = cloud.manageSupportCommercialAccess(
                action = "commercial_lookup",
                email = email,
                expiresInDays = null,
                reason = null
            )
            if (accessResult.isSuccess && accessResult.value != null) {
                commercialAccessResult = accessResult.value
                commercialDurationDaysText = ""
                commercialReasonText = "Approved support grant: Commercial access"
                commercialMessage = if (accessResult.value.adminGrantActive) {
                    "A manual Commercial grant is active for this account."
                } else if (accessResult.value.isActiveCommercial) {
                    "Commercial access is active through the account's existing subscription."
                } else {
                    "No active Commercial access. You can grant it below."
                }
                commercialMessageIsError = false
            } else {
                commercialMessage = accessResult.error ?: "Could not load Commercial grant details."
                commercialMessageIsError = true
            }
            busy = false
        }
    }

    fun runMutation(action: String) {
        val selectedAccount = accountResult ?: return
        scope.launch {
            busy = true
            message = ""
            messageIsError = false
            val result = cloud.manageSupportHiveLimit(
                action = action,
                email = selectedAccount.accountEmail ?: targetEmail.trim(),
                hiveLimit = if (action == "set") limitText.toIntOrNull() else null,
                reason = reasonText.trim().takeIf { it.isNotBlank() }
            )
            if (result.isSuccess && result.value != null) {
                val updated = result.value
                accountResult = updated
                targetEmail = updated.accountEmail.orEmpty()
                limitText = (updated.customHiveLimit ?: suggestedHiveLimit(updated.baseHiveLimit)).toString()
                message = if (action == "set") {
                    "Saved successfully: ${formatLimit(updated.customHiveLimit ?: limitText.toIntOrNull() ?: updated.baseHiveLimit)} hives. This changes the account's hive ceiling, not its subscription. The customer's device picks up the new limit on its next successful cloud sync."
                } else {
                    "Override removed. The active plan now uses its standard ${formatLimit(updated.baseHiveLimit)}-hive allowance."
                }
            } else {
                message = result.error ?: "The support-admin request failed."
                messageIsError = true
            }
            busy = false
        }
    }

    fun runCommercialMutation(action: String) {
        val selectedAccount = commercialAccessResult ?: return
        val expiryDays = commercialDurationDaysText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        scope.launch {
            busy = true
            commercialMessage = ""
            commercialMessageIsError = false
            val result = cloud.manageSupportCommercialAccess(
                action = action,
                email = selectedAccount.accountEmail ?: targetEmail.trim(),
                expiresInDays = if (action == "commercial_grant") expiryDays else null,
                reason = commercialReasonText.trim().takeIf { it.isNotBlank() }
            )
            if (result.isSuccess && result.value != null) {
                commercialAccessResult = result.value
                val refreshed = cloud.manageSupportHiveLimit(
                    action = "lookup",
                    email = result.value.accountEmail ?: targetEmail.trim(),
                    hiveLimit = null,
                    reason = null
                )
                if (refreshed.isSuccess && refreshed.value != null) {
                    accountResult = refreshed.value
                    limitText = (refreshed.value.customHiveLimit
                        ?: suggestedHiveLimit(refreshed.value.baseHiveLimit)).toString()
                }
                commercialMessage = if (action == "commercial_grant") {
                    if (result.value.adminGrantExpiresAt.isNullOrBlank()) {
                        "Commercial access granted permanently. The account receives the Commercial limits and features on its next successful entitlement refresh."
                    } else {
                        "Commercial access granted until ${result.value.adminGrantExpiresAt}. The account receives the Commercial limits and features on its next successful entitlement refresh."
                    }
                } else if (result.value.isActiveCommercial) {
                    "The manual grant was removed. This account still has Commercial access through its existing subscription."
                } else {
                    "Manual Commercial access revoked. The account now returns to Hobbyist limits and access on its next successful entitlement refresh."
                }
                commercialMessageIsError = false
            } else {
                commercialMessage = result.error ?: "The Commercial access request failed."
                commercialMessageIsError = true
            }
            busy = false
        }
    }

    val current = accountResult
    val minimumAllowed = current?.let {
        if (it.isActiveUltimate) {
            maxOf(BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT, it.baseHiveLimit)
        } else {
            BeeKeepEntitlements.HOBBYIST_HIVE_LIMIT
        }
    } ?: BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT
    val proposedLimit = limitText.toIntOrNull()
    val canSave = !busy &&
        current?.isActiveUltimate == true &&
        proposedLimit != null &&
        proposedLimit in minimumAllowed..ADMIN_MAX_CUSTOM_HIVE_LIMIT &&
        reasonText.trim().length in 3..500

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
                Text("BeeKeep Admin", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Account access, hive allowances and change history", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onBack) { Text("BACK") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Support access verified", fontWeight = FontWeight.Bold)
                    Text(
                        "Changes are checked by Supabase and recorded with the operator, reason and timestamp.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("ADMIN", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
            }
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
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Find a customer", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use the exact email attached to the customer's BeeKeep account. Search returns one account only.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetEmail,
                        onValueChange = {
                            targetEmail = it
                            accountResult = null
                            message = ""
                            messageIsError = false
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
                        Text(if (busy) "PLEASE WAIT…" else if (current != null) "REFRESH ACCOUNT" else "LOOK UP ACCOUNT")
                    }
                }
            }

            current?.let { result ->
                val tierName = adminTierName(result)
                val status = when {
                    result.isActiveUltimate -> "Active Commercial"
                    result.planCode?.contains("ultimate", ignoreCase = true) == true ||
                        result.planCode?.contains("commercial", ignoreCase = true) == true -> "Commercial inactive or expired"
                    else -> "Hobbyist plan"
                }
                val standardLimit = if (result.isActiveUltimate) {
                    maxOf(BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT, result.baseHiveLimit)
                } else {
                    BeeKeepEntitlements.HOBBYIST_HIVE_LIMIT
                }
                val effectiveLimit = if (result.isActiveUltimate) {
                    result.customHiveLimit?.coerceAtLeast(standardLimit) ?: standardLimit
                } else {
                    BeeKeepEntitlements.HOBBYIST_HIVE_LIMIT
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Customer overview", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Text(result.accountEmail.orEmpty(), fontWeight = FontWeight.SemiBold)
                            }
                            Surface(shape = RoundedCornerShape(50), color = if (result.isActiveUltimate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    status.uppercase(Locale.CANADA),
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (result.isActiveUltimate) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        AdminInfoRow("Plan on account", tierName)
                        AdminInfoRow("Standard hive allowance", "${formatLimit(standardLimit)} hives")
                        AdminInfoRow("Effective hive ceiling", "${formatLimit(effectiveLimit)} hives")
                        AdminInfoRow(
                            "Custom override",
                            if (result.isActiveUltimate) {
                                result.customHiveLimit?.let { "${formatLimit(it)} hives" } ?: "Not set"
                            } else {
                                if (result.customHiveLimit != null) "Ignored on Hobbyist" else "Not available on Hobbyist"
                            }
                        )
                        if (!result.expiresAt.isNullOrBlank()) AdminInfoRow("Entitlement expiry", result.expiresAt)
                        if (!result.overrideUpdatedAt.isNullOrBlank()) AdminInfoRow("Last override change", result.overrideUpdatedAt)
                        if (!result.overrideUpdatedBy.isNullOrBlank()) AdminInfoRow("Last updated by (account ID)", result.overrideUpdatedBy)
                        if (!result.overrideReason.isNullOrBlank()) {
                            Text("Current override reason", fontWeight = FontWeight.SemiBold)
                            Text(result.overrideReason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (!result.isActiveUltimate) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "Custom limits are available only while the account has an active Commercial entitlement. This account currently resolves to its standard plan limit; changing a hive allowance does not activate or extend a subscription.",
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
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Commercial access", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        val access = commercialAccessResult
                        val commercialActive = access?.isActiveCommercial == true
                        val manualGrantActive = access?.adminGrantActive == true
                        AdminInfoRow(
                            "Current access",
                            when {
                                manualGrantActive -> "Commercial — admin grant active"
                                commercialActive -> "Commercial — existing subscription"
                                access?.hasAdminGrant == true -> "Hobbyist — previous admin grant expired"
                                access != null -> "Hobbyist"
                                else -> "Grant details unavailable"
                            }
                        )
                        if (access?.hasAdminGrant == true) {
                            AdminInfoRow(
                                "Manual grant expiry",
                                access.adminGrantExpiresAt ?: "Permanent (no expiry)"
                            )
                            if (!access.adminGrantReason.isNullOrBlank()) {
                                Text("Grant reason", fontWeight = FontWeight.SemiBold)
                                Text(access.adminGrantReason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!access.adminGrantUpdatedAt.isNullOrBlank()) {
                                AdminInfoRow("Last grant update", access.adminGrantUpdatedAt)
                            }
                            if (!access.adminGrantedBy.isNullOrBlank()) {
                                AdminInfoRow("Granted by (account ID)", access.adminGrantedBy)
                            }
                        }
                        Text(
                            "A manual grant enables Commercial's 10,000-hive and 1,000-apiary limits, NFC features and Commercial workspace access. It does not alter the customer's billing or store purchase records.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = commercialDurationDaysText,
                            onValueChange = { value ->
                                commercialDurationDaysText = value.filter(Char::isDigit).take(4)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Grant duration in days (blank = permanent)") },
                            supportingText = { Text("Use 1–3,650 days, or leave blank for no expiry.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = commercialReasonText,
                            onValueChange = { commercialReasonText = it.take(500) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Reason for Commercial access") },
                            supportingText = { Text("Required for the admin audit history (3–500 characters).") },
                            minLines = 2,
                            maxLines = 4
                        )
                        val parsedDuration = commercialDurationDaysText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                        val durationValid = commercialDurationDaysText.isBlank() ||
                            (parsedDuration != null && parsedDuration in 1..3650)
                        Button(
                            enabled = !busy && access != null &&
                                durationValid && commercialReasonText.trim().length in 3..500 &&
                                !manualGrantActive,
                            onClick = { pendingCommercialAction = "commercial_grant" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (manualGrantActive) "COMMERCIAL GRANT ALREADY ACTIVE" else "GRANT COMMERCIAL ACCESS")
                        }
                        if (access?.hasAdminGrant == true) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { pendingCommercialAction = "commercial_revoke" },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("REVOKE MANUAL COMMERCIAL GRANT") }
                        }
                        if (access != null) {
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("Recent Commercial access activity", fontWeight = FontWeight.SemiBold)
                            if (access.auditHistory.isEmpty()) {
                                Text(
                                    "No manual Commercial access changes have been recorded.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                access.auditHistory.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (entry.action.equals("revoke", ignoreCase = true)) "GRANT REVOKED" else "COMMERCIAL GRANTED / UPDATED",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Text(
                                                entry.createdAt.orEmpty(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        AdminInfoRow(
                                            "Expiry",
                                            entry.newExpiresAt ?: if (entry.action.equals("grant", ignoreCase = true)) "Permanent" else "Removed"
                                        )
                                        Text(entry.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        if (!entry.actorUserId.isNullOrBlank()) {
                                            Text(
                                                "Operator account: " + entry.actorUserId,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (commercialMessage.isNotBlank()) {
                            Surface(
                                color = if (commercialMessageIsError) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    commercialMessage,
                                    modifier = Modifier.padding(12.dp),
                                    color = if (commercialMessageIsError) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer
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
                        Text("Hive allowance controls", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Choose a custom ceiling for an active Commercial account. The server enforces the same ceiling during cloud sync.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = limitText,
                            onValueChange = { value -> limitText = value.filter(Char::isDigit).take(7) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom limit (${formatLimit(minimumAllowed)}–1,000,000)") },
                            supportingText = { Text("The minimum is this plan's standard hive allowance.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text("Quick presets", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { limitText = suggestedHiveLimit(result.baseHiveLimit).toString() },
                                enabled = result.isActiveUltimate && !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("Suggested") }
                            OutlinedButton(
                                onClick = { limitText = scaleHiveLimit(minimumAllowed, 2).toString() },
                                enabled = result.isActiveUltimate && !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("2× base") }
                            OutlinedButton(
                                onClick = { limitText = scaleHiveLimit(minimumAllowed, 5).toString() },
                                enabled = result.isActiveUltimate && !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text("5× base") }
                        }
                        OutlinedTextField(
                            value = reasonText,
                            onValueChange = { reasonText = it.take(500) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Reason for this change") },
                            supportingText = { Text("Required for audit history (3–500 characters).") },
                            minLines = 2,
                            maxLines = 4
                        )
                        Button(
                            enabled = canSave,
                            onClick = { pendingAction = "set" },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("REVIEW CUSTOM LIMIT") }
                        if (result.customHiveLimit != null) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { pendingAction = "clear" },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("REMOVE OVERRIDE / RESET TO STANDARD") }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recent limit activity", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The latest 10 changes are retained by the server, including removals.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (result.auditHistory.isEmpty()) {
                            Text("No custom limit changes recorded for this account yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            result.auditHistory.forEachIndexed { index, entry ->
                                if (index > 0) {
                                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (entry.action.equals("clear", ignoreCase = true)) "OVERRIDE REMOVED" else "LIMIT SET", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                        Text(entry.createdAt.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        "${entry.oldHiveLimit?.let(::formatLimit) ?: "No previous override"} → ${entry.newHiveLimit?.let { "${formatLimit(it)} hives" } ?: "Plan default"}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(entry.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    if (!entry.actorUserId.isNullOrBlank()) {
                                        Text("Operator account: ${entry.actorUserId}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (message.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(14.dp),
                        color = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }

    pendingCommercialAction?.let { action ->
        val isGrant = action == "commercial_grant"
        val selected = commercialAccessResult
        val durationLabel = if (commercialDurationDaysText.isBlank()) {
            "permanent (no expiry)"
        } else {
            commercialDurationDaysText.trim() + " days"
        }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingCommercialAction = null },
            title = { Text(if (isGrant) "Confirm Commercial access grant" else "Revoke manual Commercial access") },
            text = {
                Text(
                    if (isGrant) {
                        "Grant Commercial access to ${selected?.accountEmail} for $durationLabel?\n\nThis enables Commercial limits and features but does not change billing records.\n\nReason: ${commercialReasonText.trim()}"
                    } else {
                        "Remove the manual Commercial grant for ${selected?.accountEmail}? Any separate active paid Commercial subscription will remain active.\n\nThe account will return to Hobbyist only if no other Commercial entitlement is active."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingCommercialAction = null
                        runCommercialMutation(action)
                    }
                ) { Text(if (isGrant) "CONFIRM GRANT" else "CONFIRM REVOKE") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingCommercialAction = null }) { Text("CANCEL") }
            }
        )
    }

    pendingAction?.let { action ->
        val isSet = action == "set"
        val selected = accountResult
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAction = null },
            title = { Text(if (isSet) "Confirm custom hive limit" else "Reset to standard limit") },
            text = {
                Text(
                    if (isSet) {
                        "Set the ${adminTierName(selected)} hive ceiling to ${proposedLimit?.let(::formatLimit) ?: limitText} for ${selected?.accountEmail}?\n\nReason: ${reasonText.trim()}\n\nThis will not change the subscription itself."
                    } else {
                        "Remove the custom hive override for ${selected?.accountEmail}? The active plan will fall back to its standard ${selected?.baseHiveLimit?.let(::formatLimit) ?: "default"}-hive allowance.\n\nReason: ${reasonText.trim().ifBlank { "Use the previous override reason" }}"
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

private fun suggestedHiveLimit(baseLimit: Int): Int =
    (baseLimit.toLong() + maxOf(500L, baseLimit / 10L))
        .coerceAtMost(ADMIN_MAX_CUSTOM_HIVE_LIMIT.toLong())
        .toInt()

private fun scaleHiveLimit(baseLimit: Int, multiplier: Int): Int =
    (baseLimit.toLong() * multiplier).coerceAtMost(ADMIN_MAX_CUSTOM_HIVE_LIMIT.toLong()).toInt()

private fun formatLimit(value: Int): String = String.format(Locale.CANADA, "%,d", value)

private fun adminTierName(result: SupportAdminHiveLimitResponse?): String {
    val plan = result?.planCode.orEmpty().lowercase(Locale.ROOT).replace("_", "").replace("-", "")
    return when {
        result?.isActiveUltimate == true -> "Commercial"
        plan.contains("ultimate") || plan.contains("commercial") -> "Commercial"
        else -> "Hobbyist"
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
