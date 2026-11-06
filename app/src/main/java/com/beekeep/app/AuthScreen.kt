package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.CloudSyncScheduler
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.auth.GoogleCredentialSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

@Composable
internal fun BeeKeepAuthLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.beekeep_header_logo),
                contentDescription = "BeeKeep",
                modifier = Modifier.size(92.dp)
            )
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text("Preparing your BeeKeep account…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BeeKeepStartupRecoveryScreen(cloud: SupabaseGateway) {
    BackHandler(enabled = true) { /* Startup recovery actions are presented below. */ }

    val scope = rememberCoroutineScope()
    var canContinueOffline by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var continuingOffline by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        canContinueOffline = cloud.canContinueOffline()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.beekeep_header_logo),
            contentDescription = "BeeKeep",
            modifier = Modifier.size(82.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "BeeKeep is taking longer than expected",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Account startup hasn't finished. Your local hive records have not been changed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                scope.launch {
                    retrying = true
                    message = ""
                    try {
                        withTimeout(20_000L) { cloud.refreshAccount() }
                    } catch (_: TimeoutCancellationException) {
                        message = "Startup is still busy. Continue offline or try again after the current sync finishes."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        message = "BeeKeep couldn't retry startup. Check your connection and try again."
                    } finally {
                        retrying = false
                    }
                }
            },
            enabled = !retrying && !continuingOffline && !signingOut,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            if (retrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(if (retrying) "RETRYING..." else "RETRY")
        }
        if (canContinueOffline) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    scope.launch {
                        continuingOffline = true
                        message = ""
                        try {
                            if (!cloud.continueOffline()) {
                                message = "Offline mode isn't available for this account. Reconnect and retry."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            message = "BeeKeep couldn't open the local workspace. Your records remain unchanged."
                        } finally {
                            continuingOffline = false
                        }
                    }
                },
                enabled = !retrying && !continuingOffline && !signingOut,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                if (continuingOffline) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (continuingOffline) "OPENING LOCAL DATA..." else "CONTINUE OFFLINE")
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = {
                scope.launch {
                    signingOut = true
                    message = ""
                    try {
                        when (val result = withTimeout(20_000L) { cloud.signOut() }) {
                            CloudResult.Success -> Unit
                            CloudResult.NotConfigured -> message = "BeeKeep Cloud isn't configured for this build."
                            CloudResult.NotSignedIn -> Unit
                            is CloudResult.Failure -> message = result.message
                        }
                    } catch (_: TimeoutCancellationException) {
                        message = "Sign out is taking too long. Reconnect and try again."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        message = "BeeKeep couldn't complete sign out. Your local records remain unchanged."
                    } finally {
                        signingOut = false
                    }
                }
            },
            enabled = !retrying && !continuingOffline && !signingOut,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
        ) {
            Text(if (signingOut) "SIGNING OUT..." else "SIGN OUT")
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun BeeKeepAuthScreen(cloud: SupabaseGateway) {
    BackHandler(enabled = true) { /* Authentication is required; there is no guest route. */ }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val passwordRecoveryRequired by cloud.passwordRecoveryRequired.collectAsStateWithLifecycle()
    if (passwordRecoveryRequired) {
        BeeKeepPasswordRecoveryScreen(cloud)
        return
    }
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var forgotPasswordMode by rememberSaveable { mutableStateOf(false) }
    var canResendConfirmation by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var messageIsError by rememberSaveable { mutableStateOf(false) }

    val cleanEmail = email.trim()
    val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()
    val validUsername = username.matches(Regex("^[A-Za-z0-9_]{3,20}$"))
    val passwordScore = remember(password) {
        listOf(
            password.length >= 8,
            password.any(Char::isLowerCase) && password.any(Char::isUpperCase),
            password.any(Char::isDigit),
            password.any { !it.isLetterOrDigit() }
        ).count { it }
    }
    val passwordStrength = when {
        password.isEmpty() -> ""
        passwordScore <= 1 -> "Weak password"
        passwordScore == 2 -> "Fair password"
        passwordScore == 3 -> "Good password"
        else -> "Strong password"
    }
    val canSubmit = !busy && validEmail && (
        forgotPasswordMode ||
            (password.isNotEmpty() && (!createAccount || (validUsername && password.length >= 8)))
        )

    fun showMessage(value: String, isError: Boolean = false) {
        message = value
        messageIsError = isError
    }

    fun submitEmail() {
        if (busy) return
        if (!validEmail) {
            showMessage("Enter a valid email address.", true)
            return
        }
        if (!forgotPasswordMode && createAccount && !validUsername) {
            showMessage("Choose a username with 3–20 letters, numbers, or underscores.", true)
            return
        }
        if (!forgotPasswordMode && createAccount && password.length < 8) {
            showMessage("Use a password with at least 8 characters.", true)
            return
        }

        scope.launch {
            busy = true
            showMessage("")
            try {
                val result = if (forgotPasswordMode) {
                    cloud.requestPasswordReset(cleanEmail)
                } else if (createAccount) {
                    val availability = cloud.isUsernameAvailable(username.trim())
                    when {
                        !availability.isSuccess -> {
                            showMessage(
                                availability.error ?: "Couldn't check username availability. Try again.",
                                true
                            )
                            null
                        }
                        availability.value != true -> {
                            showMessage("That username is already taken. Choose another one.", true)
                            null
                        }
                        else -> cloud.signUp(cleanEmail, password, username.trim())
                    }
                } else {
                    cloud.signIn(cleanEmail, password)
                }

                when (result) {
                    null -> Unit
                    CloudResult.Success -> {
                        when {
                            forgotPasswordMode -> {
                                forgotPasswordMode = false
                                showMessage("If an account exists for that address, a password reset link has been sent. Check your inbox.")
                            }
                            createAccount && !cloud.account.value.signedIn -> {
                                canResendConfirmation = true
                                showMessage("Check your email to verify your BeeKeep account. Then return here to sign in.")
                            }
                            else -> {
                                showMessage("You're signed in. Syncing your BeeKeep data.")
                                CloudSyncScheduler.runNow(context)
                            }
                        }
                    }
                    CloudResult.NotConfigured -> showMessage("BeeKeep Cloud is not configured for this app build.", true)
                    CloudResult.NotSignedIn -> {
                        canResendConfirmation = true
                        showMessage("Your account is not signed in yet. Check your email for a verification link.", true)
                    }
                    is CloudResult.Failure -> {
                        showMessage(result.message, true)
                        val errorText = result.message.lowercase()
                        if ("not confirmed" in errorText || "not verified" in errorText) {
                            canResendConfirmation = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                showMessage("BeeKeep couldn't complete that request. Check your connection and try again.", true)
            } finally {
                busy = false
            }
        }
    }

    fun resendConfirmation() {
        if (busy || !validEmail) return
        scope.launch {
            busy = true
            showMessage("")
            try {
                when (val result = cloud.resendSignupConfirmation(cleanEmail)) {
                    CloudResult.Success -> showMessage("A new confirmation email has been requested. Check your inbox.")
                    CloudResult.NotConfigured -> showMessage("BeeKeep Cloud is not configured for this app build.", true)
                    CloudResult.NotSignedIn -> showMessage("Sign in after confirming your email address.", true)
                    is CloudResult.Failure -> showMessage(result.message, true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                showMessage("BeeKeep couldn't resend the confirmation email. Check your connection and try again.", true)
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 460.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.beekeep_header_logo),
                    contentDescription = "BeeKeep logo",
                    modifier = Modifier.size(94.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "BeeKeep",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Your apiaries. Your records. All in one place.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(26.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                            RoundedCornerShape(28.dp)
                        ),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (createAccount) "Create your account" else "Welcome back",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                if (createAccount) "Set up your BeeKeep account to get started."
                                else "Sign in to continue to your apiaries.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!forgotPasswordMode) {
                            Button(
                                onClick = {
                                    val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
                                    if (webClientId.isBlank()) {
                                        showMessage(
                                            "Google sign-in is not configured for this build. Add googleWebClientId to local.properties and enable Google in Supabase Auth settings.",
                                            true
                                        )
                                    } else if (!busy) {
                                        scope.launch {
                                            busy = true
                                            showMessage("")
                                            try {
                                                val googleToken = GoogleCredentialSignIn.request(context, webClientId)
                                                when (val result = cloud.signInWithGoogle(
                                                    idToken = googleToken.idToken,
                                                    nonce = googleToken.nonce
                                                )) {
                                                    CloudResult.Success -> {
                                                        showMessage("You're signed in. Syncing your BeeKeep data.")
                                                        CloudSyncScheduler.runNow(context)
                                                    }
                                                    CloudResult.NotConfigured -> showMessage(
                                                        "BeeKeep Cloud is not configured for this app build.",
                                                        true
                                                    )
                                                    CloudResult.NotSignedIn -> showMessage(
                                                        "Google sign-in did not create an active BeeKeep session. Please try again.",
                                                        true
                                                    )
                                                    is CloudResult.Failure -> showMessage(result.message, true)
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: GetCredentialCancellationException) {
                                                showMessage("Google sign-in was cancelled.")
                                            } catch (_: GetCredentialException) {
                                                showMessage(
                                                    "Google couldn't provide an account on this device. Check that a Google account is available, then try again.",
                                                    true
                                                )
                                            } catch (_: Exception) {
                                                showMessage(
                                                    "BeeKeep couldn't complete Google sign-in. Check your connection and try again.",
                                                    true
                                                )
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(25.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(if (busy) "Signing in..." else "Continue with Google", fontWeight = FontWeight.Bold)
                            }
    
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                                Text("OR", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        if (createAccount && !forgotPasswordMode) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { value ->
                                    username = value.filter { it.isLetterOrDigit() || it == '_' }.take(20)
                                    message = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                singleLine = true,
                                label = { Text("Username") },
                                placeholder = { Text("e.g. NorthApiary") },
                                supportingText = { Text("3–20 letters, numbers, or underscores") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                            )
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it.take(254); message = "" },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Email address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )

                        if (!forgotPasswordMode) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; message = "" },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                singleLine = true,
                                label = { Text("Password") },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { passwordVisible = !passwordVisible },
                                        enabled = !busy
                                    ) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                        }

                        if (!forgotPasswordMode && createAccount && password.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    repeat(4) { index ->
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .height(4.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (index < passwordScore) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                        )
                                    }
                                }
                                Text(
                                    passwordStrength,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!createAccount && !forgotPasswordMode) {
                            TextButton(
                                onClick = {
                                    forgotPasswordMode = true
                                    message = ""
                                },
                                enabled = !busy,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Forgot password?")
                            }
                        }

                        Button(
                            onClick = { submitEmail() },
                            enabled = canSubmit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(19.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                when {
                                    forgotPasswordMode -> "SEND RESET LINK"
                                    createAccount -> "CREATE ACCOUNT"
                                    else -> "SIGN IN"
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        if (canResendConfirmation && validEmail && !forgotPasswordMode) {
                            TextButton(
                                onClick = { resendConfirmation() },
                                enabled = !busy,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Resend confirmation email")
                            }
                        }

                        if (message.isNotBlank()) {
                            Text(
                                message,
                                color = if (messageIsError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (account.error != null && !account.signedIn) {
                            Text(
                                account.error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (forgotPasswordMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Remembered your password?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(
                                    onClick = {
                                        forgotPasswordMode = false
                                        message = ""
                                    },
                                    enabled = !busy
                                ) {
                                    Text("Back to sign in")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (createAccount) "Already have an account?" else "New to BeeKeep?",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        createAccount = !createAccount
                                        message = ""
                                    },
                                    enabled = !busy
                                ) {
                                    Text(if (createAccount) "Sign in" else "Create account")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "By continuing, you agree to BeeKeep's Terms of Service and Privacy Policy. Links will be added before production release.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                if (!cloud.configured) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Cloud access is not configured in this build.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun BeeKeepPasswordRecoveryScreen(cloud: SupabaseGateway) {
    val scope = rememberCoroutineScope()
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var messageIsError by rememberSaveable { mutableStateOf(false) }
    var passwordUpdated by rememberSaveable { mutableStateOf(false) }

    val passwordsMatch = newPassword.isNotEmpty() && newPassword == confirmPassword
    val canSubmit = !busy && newPassword.length >= 8 && passwordsMatch

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 30.dp)
                .widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.beekeep_header_logo),
                contentDescription = "BeeKeep logo",
                modifier = Modifier.size(88.dp)
            )
            Text(
                "Choose a new password",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                "Your email link is verified. Set a new password for your BeeKeep account.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (passwordUpdated) {
                        Text(
                            "Your password has been updated.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    cloud.finishPasswordRecovery()
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("CONTINUE TO BEEKEEP", fontWeight = FontWeight.ExtraBold)
                        }
                    } else {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; message = "" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("New password") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                enabled = !busy
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; message = "" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Confirm new password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                        Text(
                            "Passwords do not match.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (newPassword.isNotEmpty() && newPassword.length < 8) {
                        Text(
                            "Use at least 8 characters.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            if (!canSubmit) return@Button
                            scope.launch {
                                busy = true
                                message = ""
                                when (val result = cloud.updatePassword(newPassword)) {
                                    CloudResult.Success -> {
                                        passwordUpdated = true
                                        message = ""
                                        messageIsError = false
                                    }
                                    CloudResult.NotConfigured -> {
                                        message = "BeeKeep Cloud is not configured for this app build."
                                        messageIsError = true
                                    }
                                    CloudResult.NotSignedIn -> {
                                        message = "This recovery link is no longer valid. Request a new password reset link."
                                        messageIsError = true
                                    }
                                    is CloudResult.Failure -> {
                                        message = result.message
                                        messageIsError = true
                                    }
                                }
                                busy = false
                            }
                        },
                        enabled = canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(19.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text("UPDATE PASSWORD", fontWeight = FontWeight.ExtraBold)
                    }
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            color = if (messageIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                cloud.signOut()
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Cancel and return to sign in")
                    }
                    }
                }
            }
        }
    }
}

