package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.TrustedDeviceAccessState
import com.beekeep.app.cloud.TrustedDeviceAccessStatus
import kotlinx.coroutines.launch

@Composable
internal fun BeeKeepTrustedDeviceGateScreen(
    cloud: SupabaseGateway,
    access: TrustedDeviceAccessState
) {
    BackHandler(enabled = true) { /* Cloud access requires a trusted phone. */ }
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val liveAccess by cloud.trustedDeviceAccess.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var codeRequested by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var messageIsError by rememberSaveable { mutableStateOf(false) }
    val currentAccess = liveAccess.takeIf { it.status != TrustedDeviceAccessStatus.CHECKING } ?: access

    fun showResult(result: CloudResult, successMessage: String) {
        when (result) {
            CloudResult.Success -> {
                message = successMessage
                messageIsError = false
            }
            CloudResult.NotConfigured -> {
                message = "BeeKeep Cloud isn't configured for this build."
                messageIsError = true
            }
            CloudResult.NotSignedIn -> {
                message = "Your BeeKeep session has ended. Sign in again."
                messageIsError = true
            }
            is CloudResult.Failure -> {
                message = result.message
                messageIsError = true
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Verify this phone",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        account.email.takeIf { it.isNotBlank() } ?: "BeeKeep account",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        when (currentAccess.status) {
                            TrustedDeviceAccessStatus.PENDING_APPROVAL ->
                                "A replacement phone request has been sent. Approve it from the BeeKeep phone you currently trust."
                            TrustedDeviceAccessStatus.REPLACED ->
                                "This phone has been replaced for BeeKeep Cloud access. You can still request access again or verify by email."
                            TrustedDeviceAccessStatus.DENIED ->
                                "The phone approval request was denied. You can request approval again or verify this phone by email."
                            TrustedDeviceAccessStatus.UNAVAILABLE ->
                                "BeeKeep couldn't verify this phone with the security service. Check your connection and try again. Cloud access stays locked until verification succeeds."
                            else ->
                                "BeeKeep allows one trusted phone per account. Verify this phone before accessing synced hives, apiaries, or workspaces."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (!currentAccess.message.isNullOrBlank() &&
                        currentAccess.status != TrustedDeviceAccessStatus.PENDING_APPROVAL
                    ) {
                        Text(
                            currentAccess.message.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (currentAccess.status == TrustedDeviceAccessStatus.PENDING_APPROVAL) {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (busy) return@Button
                                scope.launch {
                                    busy = true
                                    cloud.refreshTrustedDeviceAccess()
                                    message = cloud.trustedDeviceAccess.value.message
                                        ?: "BeeKeep checked the request. This phone is still waiting for approval."
                                    messageIsError = false
                                    busy = false
                                }
                            }
                        ) {
                            if (busy) CircularProgressIndicator() else Text("Check approval status")
                        }
                    } else {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.requestTrustedDeviceApproval()
                                    showResult(
                                        result,
                                        "Your request was submitted. Check the trusted phone for an approval prompt."
                                    )
                                    busy = false
                                }
                            }
                        ) {
                            if (busy) CircularProgressIndicator() else Text("Request approval from trusted phone")
                        }
                    }

                    if (!codeRequested) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.requestTrustedDeviceEmailCode()
                                    showResult(
                                        result,
                                        "A six-digit security code was sent to your account email."
                                    )
                                    if (result == CloudResult.Success) codeRequested = true
                                    busy = false
                                }
                            }
                        ) {
                            Text("Verify with email instead")
                        }
                    } else {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { value ->
                                code = value.filter(Char::isDigit).take(6)
                                message = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Six-digit security code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !busy
                        )
                        Button(
                            enabled = !busy && code.length == 6,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.verifyTrustedDeviceEmailCode(code)
                                    showResult(
                                        result,
                                        "This phone has been verified. BeeKeep is restoring your cloud access."
                                    )
                                    if (result == CloudResult.Success) code = ""
                                    busy = false
                                }
                            }
                        ) {
                            if (busy) CircularProgressIndicator() else Text("Verify and trust this phone")
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.requestTrustedDeviceEmailCode()
                                    showResult(
                                        result,
                                        "A new security code was sent to your account email."
                                    )
                                    busy = false
                                }
                            }
                        ) {
                            Text("Resend code")
                        }
                    }

                    if (message.isNotBlank()) {
                        Text(
                            message,
                            color = if (messageIsError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Your local hive records are kept on this phone. They won't be erased while you verify access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
