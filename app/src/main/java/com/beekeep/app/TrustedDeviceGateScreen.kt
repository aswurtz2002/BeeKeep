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

/**
 * One small gate for a newly signed-in phone.
 * Replacement phones wait for the trusted phone; after approval, finalization is automatic.
 * Email verification is the fallback when no trusted phone is available.
 */
@Composable
internal fun BeeKeepTrustedDeviceGateScreen(
    cloud: SupabaseGateway,
    access: TrustedDeviceAccessState
) {
    BackHandler(enabled = true) { /* Cloud access requires an approved session. */ }
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val liveAccess by cloud.trustedDeviceAccess.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var codeRequested by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var messageIsError by rememberSaveable { mutableStateOf(false) }
    val currentAccess = liveAccess.takeIf {
        it.status != TrustedDeviceAccessStatus.CHECKING
    } ?: access

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

    val waiting = currentAccess.status == TrustedDeviceAccessStatus.PENDING_APPROVAL
    val finalizing = currentAccess.status == TrustedDeviceAccessStatus.PENDING_FINALIZATION

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
                        when {
                            waiting -> "Waiting for approval"
                            finalizing -> "Finishing sign-in"
                            else -> "Verify this phone"
                        },
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
                        when {
                            waiting ->
                                "Open BeeKeep on your currently trusted phone and approve this request. This phone will finish signing in automatically."
                            finalizing ->
                                "Your request was approved. BeeKeep is securely handing the account over to this phone. Keep the app open."
                            currentAccess.status == TrustedDeviceAccessStatus.DENIED ->
                                "The request was denied. You can submit another request or verify your account email."
                            currentAccess.status == TrustedDeviceAccessStatus.EXPIRED ->
                                "The request expired. Submit another request or verify your account email."
                            currentAccess.status == TrustedDeviceAccessStatus.UNAVAILABLE ->
                                "BeeKeep can't currently reach the security service. Check your connection and retry. Cloud access stays locked."
                            else ->
                                "Only one phone can access BeeKeep Cloud for this account at a time. Request approval from your current phone, or verify your email if that phone is unavailable."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (!currentAccess.message.isNullOrBlank() && !waiting) {
                        Text(
                            currentAccess.message.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (waiting || finalizing) {
                        CircularProgressIndicator()
                    }

                    if (waiting) {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    cloud.refreshTrustedDeviceAccess()
                                    val latest = cloud.trustedDeviceAccess.value
                                    message = latest.message
                                        ?: "BeeKeep checked the request. It is still waiting for approval."
                                    messageIsError = false
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (busy) "Checking…" else "Check approval")
                        }
                    } else if (finalizing) {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    cloud.refreshTrustedDeviceAccess()
                                    val latest = cloud.trustedDeviceAccess.value
                                    message = latest.message
                                        ?: "BeeKeep checked the handover."
                                    messageIsError = latest.status != TrustedDeviceAccessStatus.TRUSTED
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (busy) "Checking…" else "Retry handover")
                        }
                    } else {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.requestTrustedDeviceApproval()
                                    val latest = cloud.trustedDeviceAccess.value
                                    val successMessage = if (
                                        latest.status == TrustedDeviceAccessStatus.PENDING_APPROVAL
                                    ) {
                                        "Request sent. Approve this phone from your trusted BeeKeep phone."
                                    } else {
                                        latest.message ?: "This phone needs email verification."
                                    }
                                    showResult(result, successMessage)
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (busy) "Sending…" else "Request phone approval")
                        }
                    }

                    if (!waiting && !finalizing && !codeRequested) {
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
                    } else if (!waiting && !finalizing) {
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
                                        "Email verified. BeeKeep is activating this phone."
                                    )
                                    if (result == CloudResult.Success) code = ""
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (busy) "Verifying…" else "Verify and activate")
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

                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = cloud.signOut()
                                if (result != CloudResult.Success) showResult(result, "")
                                busy = false
                            }
                        }
                    ) {
                        Text("Sign out and use another account")
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
                        "Your local hive records stay on this phone. Unapproved phones cannot access or sync cloud data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
