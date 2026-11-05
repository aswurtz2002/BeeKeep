package com.beekeep.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.SupabaseGateway
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
internal fun BeeKeepAuthScreen(cloud: SupabaseGateway) {
    BackHandler(enabled = true) { /* Authentication is required; there is no guest route. */ }

    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    var createAccount by rememberSaveable { mutableStateOf(false) }
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
    val canSubmit = !busy && validEmail && password.isNotEmpty() &&
        (!createAccount || (validUsername && password.length >= 8))

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
        if (createAccount && !validUsername) {
            showMessage("Choose a username with 3–20 letters, numbers, or underscores.", true)
            return
        }
        if (createAccount && password.length < 8) {
            showMessage("Use a password with at least 8 characters.", true)
            return
        }

        scope.launch {
            busy = true
            showMessage("")
            try {
                val result = if (createAccount) {
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
                        if (createAccount && !cloud.account.value.signedIn) {
                            showMessage("Check your email to verify your BeeKeep account. Then return here to sign in.")
                        } else {
                            showMessage("You're signed in. Your device data remains protected while setup is completed.")
                        }
                    }
                    CloudResult.NotConfigured -> showMessage("BeeKeep Cloud is not configured for this app build.", true)
                    CloudResult.NotSignedIn -> showMessage("Your account is not signed in yet. Check your email for a verification link.", true)
                    is CloudResult.Failure -> showMessage(result.message, true)
                }
            } catch (_: Throwable) {
                showMessage("BeeKeep couldn't complete that request. Check your connection and try again.", true)
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

                        Button(
                            onClick = { showMessage("Google sign-in is being connected in the next step.") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(25.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Continue with Google", fontWeight = FontWeight.Bold)
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

                        if (createAccount) {
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

                        if (createAccount && password.isNotEmpty()) {
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

                        if (!createAccount) {
                            TextButton(
                                onClick = { showMessage("Password recovery will be connected in the next step.") },
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
                                    createAccount -> "CREATE ACCOUNT"
                                    else -> "SIGN IN"
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
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
