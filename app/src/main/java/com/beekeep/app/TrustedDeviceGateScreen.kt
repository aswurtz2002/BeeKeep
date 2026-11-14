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
 * New-device verification.
 * A password/Google-authenticated session first requests a code at the account's confirmed
 * email. The code creates a fresh Auth session which replaces the previous phone only after
 * the server validates it and finalizes the one-session handover.
 */
@Composable
internal fun BeeKeepTrustedDeviceGateScreen(
    cloud: SupabaseGateway,
    access: TrustedDeviceAccessState
) {
    BackHandler(enabled = true) { /* Cloud access requires completed email verification. */ }
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val liveAccess by cloud.trustedDeviceAccess.collectAsStateWithLifecycle()
    val pendingRequestId by cloud.pendingTrustedDeviceEmailRequestId.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var messageIsError by rememberSaveable { mutableStateOf(false) }
    val currentAccess = liveAccess.takeIf {
        it.status != TrustedDeviceAccessStatus.CHECKING
    } ?: access
    val finalizing = currentAccess.status == TrustedDeviceAccessStatus.PENDING_FINALIZATION
    val codeSent = !pendingRequestId.isNullOrBlank() &&
        currentAccess.status != TrustedDeviceAccessStatus.EXPIRED &&
        currentAccess.status != TrustedDeviceAccessStatus.DENIED

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
                message = "Your BeeKeep sign-in session has ended. Sign in again."
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
                        if (finalizing) "Finishing sign-in" else "Verify this phone",
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
                            finalizing ->
                                "Your email code was verified. BeeKeep is signing out the previous phone and activating this one. Keep the app open."
                            currentAccess.status == TrustedDeviceAccessStatus.UNAVAILABLE ->
                                "BeeKeep can't currently reach the security service. Check your connection and try again. Cloud access stays locked."
                            currentAccess.status == TrustedDeviceAccessStatus.EXPIRED ->
                                "That verification request expired. Send a new code to continue."
                            currentAccess.status == TrustedDeviceAccessStatus.DENIED ->
                                "That request is no longer active. Send a new code to start again."
                            codeSent ->
                                "Enter the six-digit security code sent to your verified account email. The previous phone stays active until this code is accepted."
                            else ->
                                "To use BeeKeep Cloud on this phone, verify the account's existing email address. Once the code is accepted, other phones are signed out automatically."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (!currentAccess.message.isNullOrBlank() &&
                        currentAccess.status != TrustedDeviceAccessStatus.PENDING_EMAIL_CODE
                    ) {
                        Text(
                            currentAccess.message.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (finalizing) {
                        CircularProgressIndicator()
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    cloud.refreshTrustedDeviceAccess()
                                    val latest = cloud.trustedDeviceAccess.value
                                    message = latest.message ?: "BeeKeep checked the handover."
                                    messageIsError = latest.status != TrustedDeviceAccessStatus.TRUSTED
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (busy) "Checking…" else "Finish sign-in")
                        }
                    } else if (codeSent) {
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
                            Text(if (busy) "Verifying…" else "Verify and continue")
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.requestTrustedDeviceEmailCode()
                                    showResult(result, "A new six-digit security code was sent.")
                                    busy = false
                                }
                            }
                        ) {
                            Text("Resend code")
                        }
                    } else {
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val result = cloud.requestTrustedDeviceEmailCode()
                                    showResult(
                                        result,
                                        "A six-digit security code was sent to your verified email."
                                    )
                                    busy = false
                                }
                            }
                        ) {
                            Text(if (busy) "Sending…" else "Send verification code")
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
                        "Your local hive records stay on this phone. The previous phone is signed out only after this code is verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
