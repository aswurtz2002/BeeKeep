package com.beekeep.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.data.DataBackupPreview
import com.beekeep.app.data.LocalHiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsScreen(
    padding: PaddingValues,
    activity: ComponentActivity,
    preferences: android.content.SharedPreferences,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    autoVoiceEnabled: Boolean,
    onAutoVoiceEnabledChange: (Boolean) -> Unit,
    fastTagAssignmentMode: Boolean,
    onFastTagAssignmentModeChange: (Boolean) -> Unit,
    largeFieldText: Boolean,
    onLargeFieldTextChange: (Boolean) -> Unit,
    compactHiveRows: Boolean,
    onCompactHiveRowsChange: (Boolean) -> Unit,
    cloud: SupabaseGateway,
    onBack: () -> Unit,
    onOpenMore: () -> Unit,
    onResetApiaries: (String?) -> Unit
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsState()
    val repository = remember(context) { LocalHiveRepository(context) }

    var readyBeep by remember(preferences) { mutableStateOf(preferences.getBoolean("voice_ready_beep", true)) }
    var speakHiveNumber by remember(preferences) { mutableStateOf(preferences.getBoolean("speak_hive_number_on_scan", true)) }
    var confirmTagRemoval by remember(preferences) { mutableStateOf(preferences.getBoolean("confirm_tag_removal", true)) }
    var autoMoveHiveApiary by remember(preferences) { mutableStateOf(preferences.getBoolean("auto_move_hive_apiary", false)) }
    var defaultQueenStatus by remember(preferences) {
        mutableStateOf(preferences.getString("default_queen_status", "Laying")?.takeIf { it in setOf("Laying", "Queenless", "Virgin") } ?: "Laying")
    }
    var startingStrength by remember(preferences) {
        mutableIntStateOf(preferences.getInt("default_starting_strength", 3).coerceIn(1, 5))
    }
    var sampleSizeText by remember(preferences) {
        mutableStateOf(preferences.getInt("default_mite_sample_size", 300).coerceIn(1, 10000).toString())
    }
    var sampleSizeError by remember { mutableStateOf("") }
    var rememberLastInspection by remember(preferences) {
        mutableStateOf(preferences.getBoolean("remember_last_inspection_values", true))
    }
    var savedLastSync by remember(preferences) { mutableLongStateOf(preferences.getLong("last_successful_sync_at", 0L)) }
    var settingsMessage by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingBackupJson by remember { mutableStateOf<String?>(null) }
    var pendingBackupPreview by remember { mutableStateOf<DataBackupPreview?>(null) }
    var confirmResetApiaries by remember { mutableStateOf(false) }
    var resettingApiaries by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micGranted = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val fineLocationGranted = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val coarseLocationGranted = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val nfcSupported = remember(permissionRevision) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) }
    val nfcEnabled = remember(permissionRevision) { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRevision++
        settingsMessage = if (granted) "Microphone permission is ready." else "Microphone permission was not granted. You can change it in Android app settings."
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionRevision++
        settingsMessage = if (grants.values.any { it }) "Location permission updated." else "Location permission was not granted."
    }

    LaunchedEffect(account.lastSyncAt) {
        account.lastSyncAt?.let { timestamp ->
            savedLastSync = timestamp
            preferences.edit { putLong("last_successful_sync_at", timestamp) }
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                backupMessage = "Creating backup…"
                try {
                    val json = repository.createDataBackupJson()
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Android could not open the selected destination.")
                        output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    backupMessage = "Backup saved successfully."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    backupMessage = "Backup failed: ${error.message ?: "unknown error"}"
                } finally {
                    busy = false
                }
            }
        }
    }
    val openBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                backupMessage = "Checking backup…"
                try {
                    val raw = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Android could not read the selected file.")
                        input.use {
                            readUtf8Limited(
                                input = it,
                                maxBytes = MAX_BACKUP_FILE_BYTES,
                                tooLargeMessage = "The backup file is larger than 25 MB."
                            )
                        }
                    }
                    val preview = repository.previewDataBackup(raw)
                    pendingBackupJson = raw
                    pendingBackupPreview = preview
                    backupMessage = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    pendingBackupJson = null
                    pendingBackupPreview = null
                    backupMessage = "Could not use this backup: ${error.message ?: "the file is invalid"}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val lastSyncLabel = (account.lastSyncAt ?: savedLastSync.takeIf { it > 0L })?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: "Never"

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InspectionHoneycombBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
}

            SettingsSection("Layout & Appearance", Icons.Rounded.Settings) {
                SettingsSwitchRow(
                    title = "Yellow + black field mode",
                    checked = darkMode,
                    onCheckedChange = onDarkModeChange
                )
                SettingsSwitchRow(
                    title = "Larger field text",
                    checked = largeFieldText,
                    onCheckedChange = onLargeFieldTextChange
                )
                SettingsSwitchRow(
                    title = "Compact hive list cards",
                    checked = compactHiveRows,
                    onCheckedChange = onCompactHiveRowsChange
                )
            }

            SettingsSection("Voice & NFC behaviour", Icons.Rounded.RecordVoiceOver) {
                SettingsSwitchRow(
                    title = "Auto-start voice after an NFC hive scan",
                    checked = autoVoiceEnabled,
                    onCheckedChange = onAutoVoiceEnabledChange
                )
                SettingsSwitchRow(
                    title = "Fast tag assignment mode",
                    checked = fastTagAssignmentMode,
                    onCheckedChange = onFastTagAssignmentModeChange
                )
                if (fastTagAssignmentMode) {
                    Text(
                        "TURN THIS SETTING OFF TO CONTINUE NORMAL OPERATION",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SettingsSwitchRow(
                    title = "Beep when voice is ready",
                    checked = readyBeep,
                    onCheckedChange = {
                        readyBeep = it
                        preferences.edit { putBoolean("voice_ready_beep", it) }
                    }
                )
                SettingsSwitchRow(
                    title = "Speak the hive number aloud",
                    checked = speakHiveNumber,
                    onCheckedChange = {
                        speakHiveNumber = it
                        preferences.edit { putBoolean("speak_hive_number_on_scan", it) }
                    }
                )
                SettingsSwitchRow(
                    title = "Confirm before removing a tag",
                    checked = confirmTagRemoval,
                    onCheckedChange = {
                        confirmTagRemoval = it
                        preferences.edit { putBoolean("confirm_tag_removal", it) }
                    }
                )

            }

            SettingsSection("Apiary location", Icons.Rounded.LocationOn) {
                SettingsSwitchRow(
                    title = "Automatically move hives to nearby apiaries",
                    checked = autoMoveHiveApiary,
                    onCheckedChange = {
                        autoMoveHiveApiary = it
                        preferences.edit { putBoolean("auto_move_hive_apiary", it) }
                    }
                )
            }

            SettingsSection("Inspection defaults", Icons.Rounded.Nfc) {
                Text("Default mite wash sample size", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sampleSizeText,
                        onValueChange = { sampleSizeText = it.filter(Char::isDigit).take(5); sampleSizeError = "" },
                        modifier = Modifier.weight(1f),
                        label = { Text("Bees in sample") },

                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = sampleSizeError.isNotBlank()
                    )
                    Button(onClick = {
                        val value = sampleSizeText.toIntOrNull()
                        if (value == null || value !in 1..10000) {
                            sampleSizeError = "Enter 1–10,000."
                        } else {
                            preferences.edit { putInt("default_mite_sample_size", value) }
                            sampleSizeText = value.toString()
                            sampleSizeError = ""
                            settingsMessage = "Default mite sample size saved."
                        }
                    }) { Text("SAVE") }
                }
                if (sampleSizeError.isNotBlank()) Text(sampleSizeError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                Text("Default starting colony strength", fontWeight = FontWeight.Bold)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { grade ->
                        FilterChip(
                            selected = startingStrength == grade,
                            onClick = {
                                startingStrength = grade
                                preferences.edit { putInt("default_starting_strength", grade) }
                            },
                            label = { Text(grade.toString()) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(ColonyStrength.labelForGrade(startingStrength), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("Default queen status for new hives", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Laying", "Queenless", "Virgin").forEach { status ->
                        FilterChip(
                            selected = defaultQueenStatus == status,
                            onClick = {
                                defaultQueenStatus = status
                                preferences.edit { putString("default_queen_status", status) }
                            },
                            label = { Text(status) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                SettingsSwitchRow(
                    title = "Use last inspection values",
                    checked = rememberLastInspection,
                    onCheckedChange = {
                        rememberLastInspection = it
                        preferences.edit { putBoolean("remember_last_inspection_values", it) }
                    }
                )
            }

            SettingsSection("Data, backup & sync", Icons.Rounded.Cloud) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("BeeKeep Cloud", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !account.configured -> "Cloud sync is not configured on this build."
                                account.signedIn -> "Signed in • online sync available"
                                else -> "Local data remains available offline. Open Settings → More to enable cloud sync."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        when {
                            account.syncing -> "SYNCING"
                            account.signedIn -> "ONLINE"
                            else -> "LOCAL"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text("Last successful sync: $lastSyncLabel", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        scope.launch {
                            settingsMessage = "Syncing…"
                            when (val result = cloud.syncNow()) {
                                CloudResult.Success -> {
                                    val now = System.currentTimeMillis()
                                    savedLastSync = now
                                    preferences.edit { putLong("last_successful_sync_at", now) }
                                    settingsMessage = "Cloud sync completed."
                                }
                                CloudResult.NotConfigured -> settingsMessage = "Cloud sync is not configured."
                                CloudResult.NotSignedIn -> settingsMessage = "Open Settings → More and sign in before syncing."
                                is CloudResult.Failure -> settingsMessage = "Sync failed: ${result.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = account.configured && account.signedIn && !account.syncing && !busy
                ) { Text(if (account.syncing) "SYNCING…" else "SYNC NOW") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { createBackupLauncher.launch("BeeKeep-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json") },
                        modifier = Modifier.weight(1f),
                        enabled = !busy
                    ) {
                        Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("EXPORT")
                    }
                    OutlinedButton(
                        onClick = { openBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy
                    ) {
                        Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("RESTORE")
                    }
                }
if (backupMessage.isNotBlank()) Text(backupMessage, color = if (backupMessage.startsWith("Backup failed") || backupMessage.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SettingsSection("Permissions & troubleshooting", Icons.Rounded.Security) {
                SettingsStatusRow(
                    title = "NFC",
                    detail = when {
                        !nfcSupported -> "This phone does not report NFC hardware."
                        nfcEnabled -> "Available and enabled."
                        else -> "Available but switched off in Android."
                    },
                    ready = nfcSupported && nfcEnabled,
                    actionLabel = if (nfcSupported && !nfcEnabled) "TURN ON" else null,
                    onAction = {
                        runCatching { activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                            .onFailure { settingsMessage = "Open Android Settings and enable NFC." }
                    }
                )
                SettingsStatusRow(
                    title = "Microphone",
                    detail = if (micGranted) "Permission granted for voice notes." else "Permission needed for hands-free voice notes.",
                    ready = micGranted,
                    actionLabel = if (micGranted) null else "ALLOW",
                    onAction = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
                SettingsStatusRow(
                    title = "Location",
                    detail = if (fineLocationGranted || coarseLocationGranted) "Location permission granted." else "Permission needed to capture an apiary or hive location.",
                    ready = fineLocationGranted || coarseLocationGranted,
                    actionLabel = if (fineLocationGranted || coarseLocationGranted) null else "ALLOW",
                    onAction = { locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }.onFailure { settingsMessage = "Open Android Settings and select BeeKeep to manage permissions." }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("OPEN ANDROID APP SETTINGS") }

            }

            SettingsSection("About BeeKeep", Icons.Rounded.Info) {
                Text("BeeKeep · native Android", fontWeight = FontWeight.Bold)
                Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}", color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedButton(
                    onClick = {
                        val diagnostics = buildString {
                            appendLine("BeeKeep version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                            appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                            appendLine("Device: ${android.os.Build.MODEL}")
                            appendLine("NFC hardware: $nfcSupported")
                            appendLine("NFC enabled: $nfcEnabled")
                            appendLine("Microphone permission: $micGranted")
                            appendLine("Location permission: ${fineLocationGranted || coarseLocationGranted}")
                            appendLine("Cloud configured: ${account.configured}")
                            appendLine("Cloud signed in: ${account.signedIn}")
                            appendLine("Last successful sync: $lastSyncLabel")
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("BeeKeep diagnostics", diagnostics))
                        Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("COPY DIAGNOSTICS") }
            }

            if (settingsMessage.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(settingsMessage, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Card(
                onClick = { if (!resettingApiaries) confirmResetApiaries = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Reset Apiaries",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Delete all hives, records and NFC assignments",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.86f)
                        )
                    }
                    Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp))
                }
            }

            Card(onClick = onOpenMore, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("More", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)

                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Open More", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmResetApiaries) {
        AlertDialog(
            onDismissRequest = { if (!resettingApiaries) confirmResetApiaries = false },
            title = { Text("Reset all apiaries?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This permanently deletes every apiary and hive, along with inspections, notes, feedings, treatments, harvests, activity history, tasks and all NFC tag assignments.")
                    Text(
                        "This cannot be undone. BeeKeep Cloud data for the signed-in account will also be cleared when cloud access is available.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "App assignments for all tags will be cleared. Data physically written onto NFC tags cannot be erased remotely; each physical tag would need to be scanned to rewrite it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !resettingApiaries,
                    onClick = { confirmResetApiaries = false }
                ) { Text("CANCEL") }
            },
            confirmButton = {
                Button(
                    enabled = !resettingApiaries,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        if (resettingApiaries) return@Button
                        scope.launch {
                            resettingApiaries = true
                            try {
                                // Pause realtime updates while the workspace and cloud tombstones are reset.
                                cloud.stopRealtime()
                                val cloudResult = cloud.resetWorkspace()
                                repository.resetAllData()
                                preferences.edit {
                                    putBoolean("initial_setup_completed", false)
                                    putLong("last_successful_sync_at", 0L)
                                }
                                savedLastSync = 0L
                                confirmResetApiaries = false
                                val cloudWarning = when (cloudResult) {
                                    CloudResult.Success, CloudResult.NotConfigured -> null
                                    CloudResult.NotSignedIn -> "Local data was reset, but BeeKeep Cloud could not be cleared because you're signed out. Sign in to the same account before syncing."
                                    is CloudResult.Failure -> "Local data was reset, but the cloud reset failed: ${cloudResult.message}. Reconnect and retry cloud sync."
                                }
                                if (account.signedIn) cloud.startRealtime()
                                onResetApiaries(cloudWarning)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                settingsMessage = "Reset failed: ${error.message ?: "unknown error"}. Your data may not have been fully reset."
                                cloud.stopRealtime()
                                if (account.signedIn) cloud.startRealtime()
                            } finally {
                                resettingApiaries = false
                            }
                        }
                    }
                ) { Text(if (resettingApiaries) "RESETTING…" else "RESET EVERYTHING") }
            }
        )
    }

    val restorePreview = pendingBackupPreview
    val restoreJson = pendingBackupJson
    if (restorePreview != null && restoreJson != null) {
        AlertDialog(
            onDismissRequest = {
                pendingBackupPreview = null
                pendingBackupJson = null
            },
            title = { Text("Restore BeeKeep backup?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This backup contains ${restorePreview.totalRecords} records.")
                    listOf(
                        "apiary" to "Apiaries",
                        "hive" to "Hives",
                        "nfc_assignment" to "NFC assignment history",
                        "inspection" to "Inspections",
                        "feeding" to "Feeding logs",
                        "treatment" to "Treatments",
                        "harvest" to "Harvest logs",
                        "event" to "Activity history"
                    ).forEach { (key, label) ->
                        val count = restorePreview.counts[key] ?: 0
                        if (count > 0) Text("• $label: $count", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("This merges records. Newer records already on this phone are kept; local-only records are not deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        pendingBackupPreview = null
                        pendingBackupJson = null
                        scope.launch {
                            busy = true
                            backupMessage = "Restoring backup…"
                            try {
                                val result = repository.restoreDataBackupJson(restoreJson)
                                backupMessage = "Processed ${result.processedRecords} backup records. Newer local records were preserved."
                                when (val syncResult = cloud.syncNow()) {
                                    CloudResult.Success -> {
                                        val now = System.currentTimeMillis()
                                        savedLastSync = now
                                        preferences.edit { putLong("last_successful_sync_at", now) }
                                        backupMessage += " Cloud sync completed."
                                    }
                                    CloudResult.NotSignedIn -> backupMessage += " The restored data is on this phone; open Settings → More to sign in and sync it."
                                    CloudResult.NotConfigured -> backupMessage += " Cloud sync is not configured."
                                    is CloudResult.Failure -> backupMessage += " Cloud sync did not finish: ${syncResult.message}"
                                }
                            } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                                backupMessage = "Restore failed: ${error.message ?: "unknown error"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("MERGE BACKUP") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingBackupPreview = null
                    pendingBackupJson = null
                }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

@Composable
private fun SettingsStatusRow(
    title: String,
    detail: String,
    ready: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
