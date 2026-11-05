package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beekeep.app.cloud.BeeKeepAccountSetupState
import com.beekeep.app.cloud.SupabaseGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BeeKeepAccountSetupScreen(
    cloud: SupabaseGateway,
    setup: BeeKeepAccountSetupState.UsernameRequired
) {
    BackHandler(enabled = true) { /* A username is required before entering BeeKeep. */ }
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var checking by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var available by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var isError by rememberSaveable { mutableStateOf(false) }

    val validUsername = username.trim().matches(Regex("^[A-Za-z0-9_]{3,20}$"))
    val busy = checking || saving

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background
            )
            .imePadding(),
        contentAlignment = Alignment.Center
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
                    painter = androidx.compose.ui.res.painterResource(R.drawable.beekeep_header_logo),
                    contentDescription = "BeeKeep",
                    modifier = Modifier.size(94.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Finish setting up BeeKeep",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Choose the username your teammates will see.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Your personal workspace", fontWeight = FontWeight.ExtraBold)
                        Text(
                            setup.personalWorkspaceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "This workspace belongs to your BeeKeep account. It stays separate from other accounts.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (setup.isCached) {
                            Text(
                                "This workspace was verified on this device previously. Connect to the internet to finish saving your profile.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it.filter { char ->
                                    char in 'A'..'Z' || char in 'a'..'z' ||
                                        char in '0'..'9' || char == '_'
                                }.take(20)
                                available = false
                                message = ""
                                isError = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Username") },
                            placeholder = { Text("e.g. NorthApiary") },
                            supportingText = { Text("3–20 letters, numbers, or underscores") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    checking = true
                                    available = false
                                    message = ""
                                    isError = false
                                    try {
                                        val result = cloud.isUsernameAvailable(username.trim())
                                        if (result.isSuccess && result.value == true) {
                                            available = true
                                            message = "That username is available."
                                        } else if (result.isSuccess) {
                                            message = "That username is already taken. Choose another."
                                            isError = true
                                        } else {
                                            message = result.error ?: "Couldn't check username availability. Try again."
                                            isError = true
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Throwable) {
                                        message = "Couldn't check username availability. Check your connection and try again."
                                        isError = true
                                    } finally {
                                        checking = false
                                    }
                                }
                            },
                            enabled = validUsername && !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                        ) {
                            if (checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.size(8.dp))
                            }
                            Text(if (checking) "CHECKING..." else "CHECK USERNAME")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    saving = true
                                    message = ""
                                    isError = false
                                    try {
                                        // Recheck before writing so a username claimed after the
                                        // first check is reported cleanly. The database unique
                                        // index remains the final race-safe authority.
                                        val availability = cloud.isUsernameAvailable(username.trim())
                                        if (!availability.isSuccess) {
                                            message = availability.error ?: "Couldn't verify that username. Try again."
                                            isError = true
                                        } else if (availability.value != true) {
                                            available = false
                                            message = "That username is already taken. Choose another."
                                            isError = true
                                        } else {
                                            val result = cloud.setUsername(username.trim())
                                            if (result.isSuccess && !result.value.isNullOrBlank()) {
                                                message = "Profile saved."
                                                available = false
                                            } else {
                                                message = result.error ?: "Couldn't save your username. Try again."
                                                isError = true
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Throwable) {
                                        message = "BeeKeep couldn't save your profile. Check your connection and try again."
                                        isError = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = available && validUsername && !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.size(8.dp))
                            }
                            Text(if (saving) "SAVING..." else "SAVE AND CONTINUE")
                        }
                        if (message.isNotBlank()) {
                            Text(
                                message,
                                color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeeKeepAccountSetupUnavailableScreen(
    cloud: SupabaseGateway,
    message: String
) {
    BackHandler(enabled = true) { /* Account setup must be verified before entering BeeKeep. */ }
    val scope = rememberCoroutineScope()
    var retrying by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Couldn't finish account setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                scope.launch {
                    retrying = true
                    try {
                        cloud.retryAccountSetup()
                    } finally {
                        retrying = false
                    }
                }
            },
            enabled = !retrying,
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
    }
}
