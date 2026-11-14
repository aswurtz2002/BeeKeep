package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.provider.Settings
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.TrustedDeviceAccessState
import com.beekeep.app.cloud.TrustedDeviceAccessStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class VerifyAccountErrorReason {
    WRONG_CODE,
    EXPIRED_CODE,
    TOO_MANY_ATTEMPTS,
    NO_NETWORK,
    UNKNOWN
}

private sealed interface VerifyAccountUiState {
    data object Idle : VerifyAccountUiState
    data object Submitting : VerifyAccountUiState
    data object WaitingForApproval : VerifyAccountUiState
    data object Success : VerifyAccountUiState
    data class Error(val reason: VerifyAccountErrorReason) : VerifyAccountUiState
    data object Expired : VerifyAccountUiState
}

private val verifyAccountUiStateSaver = Saver<VerifyAccountUiState, String>(
    save = { state ->
        when (state) {
            VerifyAccountUiState.Idle -> "idle"
            VerifyAccountUiState.Submitting -> "idle"
            VerifyAccountUiState.WaitingForApproval -> "waiting"
            VerifyAccountUiState.Success -> "success"
            VerifyAccountUiState.Expired -> "expired"
            is VerifyAccountUiState.Error -> "error:${state.reason.name}"
        }
    },
    restore = { saved ->
        when {
            saved == "waiting" -> VerifyAccountUiState.WaitingForApproval
            saved == "success" -> VerifyAccountUiState.Success
            saved == "expired" -> VerifyAccountUiState.Expired
            saved.startsWith("error:") -> runCatching {
                VerifyAccountUiState.Error(
                    VerifyAccountErrorReason.valueOf(saved.removePrefix("error:"))
                )
            }.getOrDefault(VerifyAccountUiState.Idle)
            else -> VerifyAccountUiState.Idle
        }
    }
)

private fun verifyAccountErrorReason(message: String): VerifyAccountErrorReason {
    val lower = message.lowercase()
    return when {
        listOf("too many", "rate limit", "rate_limit", "429", "53300")
            .any(lower::contains) -> VerifyAccountErrorReason.TOO_MANY_ATTEMPTS
        listOf("expired", "otp_expired", "token has expired")
            .any(lower::contains) -> VerifyAccountErrorReason.EXPIRED_CODE
        listOf("invalid otp", "invalid code", "code isn't right", "token is invalid")
            .any(lower::contains) -> VerifyAccountErrorReason.WRONG_CODE
        listOf("no connection", "network", "unable to resolve", "timed out", "connection")
            .any(lower::contains) -> VerifyAccountErrorReason.NO_NETWORK
        else -> VerifyAccountErrorReason.UNKNOWN
    }
}

private fun VerifyAccountErrorReason.message(): String = when (this) {
    VerifyAccountErrorReason.WRONG_CODE -> "That code isn't right. Try again."
    VerifyAccountErrorReason.EXPIRED_CODE -> "That code has expired. We can send a new one."
    VerifyAccountErrorReason.TOO_MANY_ATTEMPTS -> "Too many tries. Please wait a minute."
    VerifyAccountErrorReason.NO_NETWORK -> "No connection. Check your internet and try again."
    VerifyAccountErrorReason.UNKNOWN -> "Something went wrong. Please try again."
}

/**
 * Email-code verification for a new phone. A single saved UI state owns progress and feedback;
 * the server-side request and OTP recovery markers remain the source of truth across restarts.
 */
@Composable
internal fun BeeKeepTrustedDeviceGateScreen(
    cloud: SupabaseGateway,
    access: TrustedDeviceAccessState
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) <= 0f
        }.getOrDefault(false)
    }
    val account by cloud.account.collectAsStateWithLifecycle()
    val liveAccess by cloud.trustedDeviceAccess.collectAsStateWithLifecycle()
    val pendingRequestId by cloud.pendingTrustedDeviceEmailRequestId.collectAsStateWithLifecycle()
    val emailCodeSent by cloud.pendingTrustedDeviceEmailCodeSent.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var uiState by rememberSaveable(stateSaver = verifyAccountUiStateSaver) {
        mutableStateOf(VerifyAccountUiState.Idle)
    }
    var cooldownUntilMillis by rememberSaveable { mutableStateOf(0L) }
    var cooldownSeconds by rememberSaveable { mutableIntStateOf(0) }
    var requestSecondsRemaining by rememberSaveable { mutableIntStateOf(0) }

    val currentAccess = liveAccess.takeIf {
        it.status != TrustedDeviceAccessStatus.CHECKING
    } ?: access
    val codeSent = emailCodeSent && !pendingRequestId.isNullOrBlank() &&
        currentAccess.status != TrustedDeviceAccessStatus.EXPIRED &&
        currentAccess.status != TrustedDeviceAccessStatus.DENIED
    val finalizing = currentAccess.status == TrustedDeviceAccessStatus.PENDING_FINALIZATION
    val submitting = uiState == VerifyAccountUiState.Submitting
    val canSendCode = !submitting && cooldownSeconds == 0

    LaunchedEffect(currentAccess.status) {
        when (currentAccess.status) {
            TrustedDeviceAccessStatus.PENDING_FINALIZATION ->
                uiState = VerifyAccountUiState.WaitingForApproval
            TrustedDeviceAccessStatus.EXPIRED ->
                uiState = VerifyAccountUiState.Expired
            TrustedDeviceAccessStatus.DENIED ->
                uiState = VerifyAccountUiState.Error(VerifyAccountErrorReason.UNKNOWN)
            else -> Unit
        }
    }

    LaunchedEffect(cooldownUntilMillis) {
        while (cooldownUntilMillis > 0L) {
            val remaining = ((cooldownUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            cooldownSeconds = remaining.toInt()
            if (remaining <= 0L) break
            delay(1_000L)
        }
        cooldownSeconds = 0
    }

    LaunchedEffect(currentAccess.expiresAt) {
        val expiryMillis = currentAccess.expiresAt?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: 0L
        requestSecondsRemaining = 0
        while (expiryMillis > 0L) {
            val remaining = ((expiryMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            requestSecondsRemaining = remaining.toInt()
            if (remaining <= 0L) {
                uiState = VerifyAccountUiState.Expired
                break
            }
            delay(1_000L)
        }
    }

    fun showResult(result: CloudResult, successful: Boolean) {
        uiState = when (result) {
            CloudResult.Success -> {
                if (successful) VerifyAccountUiState.Success else VerifyAccountUiState.Idle
            }
            CloudResult.NotConfigured ->
                VerifyAccountUiState.Error(VerifyAccountErrorReason.UNKNOWN)
            CloudResult.NotSignedIn ->
                VerifyAccountUiState.Error(VerifyAccountErrorReason.UNKNOWN)
            is CloudResult.Failure ->
                VerifyAccountUiState.Error(verifyAccountErrorReason(result.message))
        }
    }

    fun sendCode() {
        if (uiState == VerifyAccountUiState.Submitting || cooldownSeconds > 0) return
        uiState = VerifyAccountUiState.Submitting
        scope.launch {
            val result = cloud.requestTrustedDeviceEmailCode()
            showResult(result, successful = true)
            if (result == CloudResult.Success) {
                code = ""
                cooldownUntilMillis = System.currentTimeMillis() + 60_000L
            }
        }
    }

    fun submitCode(value: String = code) {
        if (uiState == VerifyAccountUiState.Submitting || value.length != 6) return
        val submittedCode = value
        uiState = VerifyAccountUiState.Submitting
        scope.launch {
            val result = cloud.verifyTrustedDeviceEmailCode(submittedCode)
            showResult(result, successful = false)
            if (result == CloudResult.Success) code = ""
        }
    }

    fun returnToSignIn() {
        if (uiState == VerifyAccountUiState.Submitting) return
        uiState = VerifyAccountUiState.Submitting
        scope.launch {
            val result = cloud.signOut()
            if (result != CloudResult.Success) {
                showResult(result, successful = false)
            }
        }
    }

    BackHandler(enabled = true) { returnToSignIn() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "BeeKeep",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                )
                Text(
                    if (finalizing) "Securing this phone" else "Verify your account",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    when {
                        finalizing -> "Your email is verified. BeeKeep is finishing the phone handover."
                        codeSent -> "Enter the six-digit code sent to your verified email address."
                        else -> "Confirm your email to securely use BeeKeep on this phone."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Text(
                    account.email.takeIf { it.isNotBlank() } ?: "BeeKeep account",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                currentAccess.deviceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        "Device: $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (requestSecondsRemaining > 0) {
                    Text(
                        "Request expires in ${requestSecondsRemaining / 60}:${(requestSecondsRemaining % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (finalizing || uiState == VerifyAccountUiState.WaitingForApproval) {
                    if (reduceMotion) {
                        Text(
                            "Finishing securely…",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    Text(
                        "Finishing sign-in on this phone…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            uiState = VerifyAccountUiState.Submitting
                            scope.launch {
                                cloud.refreshTrustedDeviceAccess()
                                val latest = cloud.trustedDeviceAccess.value
                                uiState = when (latest.status) {
                                    TrustedDeviceAccessStatus.EXPIRED ->
                                        VerifyAccountUiState.Expired
                                    TrustedDeviceAccessStatus.TRUSTED ->
                                        VerifyAccountUiState.Success
                                    TrustedDeviceAccessStatus.PENDING_FINALIZATION ->
                                        VerifyAccountUiState.WaitingForApproval
                                    TrustedDeviceAccessStatus.OFFLINE_LOCAL ->
                                        VerifyAccountUiState.Error(VerifyAccountErrorReason.NO_NETWORK)
                                    TrustedDeviceAccessStatus.UNAVAILABLE -> {
                                        val message = latest.message.orEmpty().lowercase()
                                        VerifyAccountUiState.Error(
                                            if (message.contains("no connection") ||
                                                message.contains("internet") ||
                                                message.contains("couldn't reach")
                                            ) VerifyAccountErrorReason.NO_NETWORK
                                            else VerifyAccountErrorReason.UNKNOWN
                                        )
                                    }
                                    else -> VerifyAccountUiState.Error(VerifyAccountErrorReason.UNKNOWN)
                                }
                            }
                        }
                    ) {
                        Text(if (submitting) "Checking…" else "Try again")
                    }
                    TextButton(
                        enabled = !submitting,
                        onClick = { returnToSignIn() }
                    ) { Text("Cancel") }
                } else {
                    if (codeSent) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { value ->
                                val updatedCode = value.filter(Char::isDigit).take(6)
                                code = updatedCode
                                if (uiState is VerifyAccountUiState.Error ||
                                    uiState == VerifyAccountUiState.Expired ||
                                    uiState == VerifyAccountUiState.Success
                                ) {
                                    uiState = VerifyAccountUiState.Idle
                                }
                                if (updatedCode.length == 6) submitCode(updatedCode)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Six digit BeeKeep security code"
                                },
                            label = { Text("Security code") },
                            placeholder = { Text("000000") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            enabled = !submitting
                        )
                        Button(
                            enabled = !submitting && code.length == 6,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { submitCode() }
                        ) {
                            Text(if (submitting) "Verifying…" else "Verify and continue")
                        }
                        TextButton(
                            enabled = canSendCode,
                            onClick = { uiState = VerifyAccountUiState.Idle; sendCode() }
                        ) {
                            Text(
                                if (cooldownSeconds > 0) {
                                    "Resend code in ${cooldownSeconds / 60}:${(cooldownSeconds % 60).toString().padStart(2, '0')}"
                                } else {
                                    "Resend code"
                                }
                            )
                        }
                    } else {
                        Button(
                            enabled = canSendCode,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { sendCode() }
                        ) {
                            Text(if (submitting) "Sending…" else "Send verification code")
                        }
                    }
                }

                when (val state = uiState) {
                    is VerifyAccountUiState.Error -> Text(
                        state.reason.message(),
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    VerifyAccountUiState.Expired -> Text(
                        VerifyAccountErrorReason.EXPIRED_CODE.message(),
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    VerifyAccountUiState.Success -> Text(
                        "Code sent. Check your email.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    else -> Unit
                }

                Spacer(Modifier.height(4.dp))
                if (!finalizing && uiState != VerifyAccountUiState.WaitingForApproval) {
                    TextButton(
                        enabled = !submitting,
                        onClick = { returnToSignIn() }
                    ) { Text("Wrong email? Back to sign in") }
                }
            }
        }
    }
}
