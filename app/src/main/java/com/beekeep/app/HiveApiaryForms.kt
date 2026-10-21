package com.beekeep.app

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.data.Apiary
import com.beekeep.app.data.Hive
import com.beekeep.app.location.LocationController
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun AddHiveScreen(
    apiaries: List<Apiary>,
    initialApiary: String?,
    defaultQueenStatus: String = "Laying",
    defaultStrength: Int = 3,
    hiveLimitPromptVisible: Boolean = false,
    currentPlan: BeeKeepPlan = BeeKeepPlan.FREE,
    onDismissHiveLimitPrompt: () -> Unit = {},
    onOpenUpgrade: () -> Unit = {},
    onBack: () -> Unit,
    onCreate: (String, String, String, Int) -> Unit
) {
    BackHandler { onBack() }
    var number by rememberSaveable { mutableStateOf("") }
    val apiaryOptionsKey = apiaries.joinToString("|") { "${it.id}:${it.name}" }
    var apiary by rememberSaveable(initialApiary, apiaryOptionsKey) {
        mutableStateOf(
            initialApiary?.let { selected -> apiaries.firstOrNull { it.name.equals(selected, ignoreCase = true) }?.name }
                ?: apiaries.firstOrNull()?.name.orEmpty()
        )
    }
    var apiaryMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var queen by rememberSaveable { mutableStateOf(defaultQueenStatus) }
    var strength by rememberSaveable { mutableIntStateOf(defaultStrength.coerceIn(1, 5)) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text("Add Hive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        }
        OutlinedTextField(number, { number = it }, Modifier.fillMaxWidth(), label = { Text("Hive number") }, singleLine = true)
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = apiary,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().clickable(enabled = apiaries.isNotEmpty()) {
                    apiaryMenuExpanded = true
                },
                label = { Text("Apiary / Yard") },
                trailingIcon = {
                    IconButton(
                        onClick = { apiaryMenuExpanded = !apiaryMenuExpanded },
                        enabled = apiaries.isNotEmpty()
                    ) {
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose an apiary")
                    }
                },
                singleLine = true,
                readOnly = true,
                enabled = apiaries.isNotEmpty()
            )
            DropdownMenu(
                expanded = apiaryMenuExpanded && apiaries.isNotEmpty(),
                onDismissRequest = { apiaryMenuExpanded = false },
                modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 320.dp)
            ) {
                apiaries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            apiary = option.name
                            apiaryMenuExpanded = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (apiaries.isEmpty()) {
            Text(
                "Create an apiary first before adding a hive.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("Queen status", fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (status in listOf("Laying", "Queenless", "Virgin")) {
                FilterChip(queen == status, { queen = status }, { Text(status) })
            }
        }
        Counter(
            "Starting strength",
            strength,
            1..5,
            valueLabel = ColonyStrength.labelForGrade(strength)
        ) { strength = it }
        Button(
            { onCreate(number, apiary, queen, ColonyStrength.storedFromGrade(strength)) },
            Modifier.fillMaxWidth().height(60.dp),
            enabled = number.isNotBlank() && apiaries.any { it.name == apiary },
            shape = RoundedCornerShape(24.dp)
        ) { Text("CREATE HIVE", fontWeight = FontWeight.ExtraBold) }
    }

    if (hiveLimitPromptVisible) {
        AlertDialog(
            onDismissRequest = onDismissHiveLimitPrompt,
            shape = RoundedCornerShape(26.dp),
            containerColor = androidx.compose.ui.graphics.Color(0xFFFFFBEB),
            titleContentColor = androidx.compose.ui.graphics.Color(0xFF78350F),
            textContentColor = androidx.compose.ui.graphics.Color(0xFF654321),
            title = {
                Text("Hive Limit Reached", fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(BeeKeepEntitlements.hiveLimitMessage(currentPlan))
                    Text(
                        if (currentPlan == BeeKeepPlan.ULTIMATE || currentPlan == BeeKeepPlan.ULTIMATE_YEARLY) {
                            "Your existing hive records remain safe. Contact BeeKeep support if you need a higher limit."
                        } else {
                            "Your existing hive records remain safe. Choose a plan with a higher limit to keep adding hives."
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onOpenUpgrade,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (currentPlan == BeeKeepPlan.ULTIMATE || currentPlan == BeeKeepPlan.ULTIMATE_YEARLY) "VIEW PLAN DETAILS" else "VIEW UPGRADE PLANS", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissHiveLimitPrompt) {
                    Text("NOT NOW", color = androidx.compose.ui.graphics.Color(0xFF78350F))
                }
            }
        )
    }
}

@Composable
internal fun AddApiaryScreen(
    locationController: LocationController,
    existingApiary: Apiary?,
    onBack: () -> Unit,
    onDelete: ((Long, (Boolean, String?) -> Unit) -> Unit)? = null,
    apiaryLimitPromptVisible: Boolean = false,
    currentPlan: BeeKeepPlan = BeeKeepPlan.FREE,
    onDismissApiaryLimitPrompt: () -> Unit = {},
    onOpenUpgrade: () -> Unit = {},
    onSave: (Long?, String, String, Double?, Double?, String, String) -> Unit
) {
    var name by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.name.orEmpty()) }
    var notes by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.notes.orEmpty()) }
    var forage by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.forageNotes.orEmpty()) }
    var water by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.waterNotes.orEmpty()) }
    var lat by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.latitude) }
    var lon by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.longitude) }
    var gpsStatus by rememberSaveable { mutableStateOf("") }
    var showDeleteConfirmation by rememberSaveable(existingApiary?.id) { mutableStateOf(false) }
    var deletingApiary by rememberSaveable(existingApiary?.id) { mutableStateOf(false) }
    var deleteError by rememberSaveable(existingApiary?.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    BackHandler {
        if (showDeleteConfirmation) {
            if (!deletingApiary) {
                showDeleteConfirmation = false
                deleteError = null
            }
        } else if (!deletingApiary) {
            onBack()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            locationController.current { r ->
                if (r != null) { lat = r.latitude; lon = r.longitude; gpsStatus = "GPS captured" }
                else gpsStatus = "Could not get a location"
            }
        } else gpsStatus = "Location permission denied"
    }
    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedBorderColor = androidx.compose.ui.graphics.Color(0xFFD97706),
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedLabelColor = androidx.compose.ui.graphics.Color(0xFF92400E),
        cursorColor = androidx.compose.ui.graphics.Color(0xFFD97706)
    )
    val captureLocation = {
        if (locationController.hasPermission()) {
            locationController.current { r ->
                if (r != null) {
                    lat = r.latitude
                    lon = r.longitude
                    gpsStatus = "GPS captured"
                } else {
                    gpsStatus = "Could not get a location"
                }
            }
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InspectionHoneycombBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = androidx.compose.ui.graphics.Color(0xFFFFF3C4),
                    contentColor = androidx.compose.ui.graphics.Color(0xFF92400E)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to apiaries")
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (existingApiary == null) "APIARY SETUP" else "APIARY SETTINGS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = androidx.compose.ui.graphics.Color(0xFFB45309)
                    )
                    Text(
                        if (existingApiary == null) "Add an apiary" else "Edit Apiary",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        if (existingApiary == null) "Set up a home for your hives." else "Keep your yard details up to date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = androidx.compose.ui.graphics.Color(0xFFFFF3C4),
                            contentColor = androidx.compose.ui.graphics.Color(0xFF92400E)
                        ) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Yard, contentDescription = null)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Apiary details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text("Give this yard a name you will recognize.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Apiary name") },
                        placeholder = { Text("e.g. North Meadow") },
                        supportingText = { Text("Required") },
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        colors = fieldColors
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("YARD NOTES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color(0xFFB45309))
                    Text("Useful details for you or your team when visiting this location.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Site notes") },
                        placeholder = { Text("Gate access, parking, hazards…") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(15.dp),
                        colors = fieldColors
                    )
                    OutlinedTextField(
                        value = forage,
                        onValueChange = { forage = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Forage notes") },
                        placeholder = { Text("Nearby crops, flowering plants, seasonal forage…") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(15.dp),
                        colors = fieldColors
                    )
                    OutlinedTextField(
                        value = water,
                        onValueChange = { water = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Water notes") },
                        placeholder = { Text("Ponds, streams, water sources…") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(15.dp),
                        colors = fieldColors
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = androidx.compose.ui.graphics.Color(0xFFFFF3C4),
                            contentColor = androidx.compose.ui.graphics.Color(0xFF92400E)
                        ) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.LocationOn, contentDescription = null)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Apiary location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text("Save a pin so you can find this yard again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (lat != null && lon != null) androidx.compose.ui.graphics.Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (lat != null && lon != null) androidx.compose.ui.graphics.Color(0xFF166534) else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                if (lat != null && lon != null) "PIN SET" else "OPTIONAL",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    Button(
                        onClick = { captureLocation() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFFD97706),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        )
                    ) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (lat == null || lon == null) "CAPTURE CURRENT GPS" else "UPDATE GPS LOCATION", fontWeight = FontWeight.ExtraBold)
                    }
                    if (lat != null && lon != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "SAVED COORDINATES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lon)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Satellite preview · drag to move and pinch to zoom",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ApiarySatelliteMap(
                            apiaries = listOf(
                                Apiary(
                                    id = existingApiary?.id ?: Long.MIN_VALUE,
                                    name = name.ifBlank { "Current apiary location" },
                                    notes = notes,
                                    latitude = lat,
                                    longitude = lon,
                                    forageNotes = forage,
                                    waterNotes = water
                                )
                            ),
                            focusedApiaryId = existingApiary?.id ?: Long.MIN_VALUE,
                            currentLocation = null,
                            satelliteView = true,
                            modifier = Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(18.dp))
                        )
                        TextButton(
                            onClick = {
                                lat = null
                                lon = null
                                gpsStatus = "GPS location removed"
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("REMOVE LOCATION", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "No map pin yet. Capture GPS while you are at the apiary, or save this yard without a location.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (gpsStatus.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (gpsStatus.startsWith("Could") || gpsStatus.startsWith("Location permission denied")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                gpsStatus,
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                color = if (gpsStatus.startsWith("Could") || gpsStatus.startsWith("Location permission denied")) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onSave(existingApiary?.id, name.trim(), notes.trim(), lat, lon, forage.trim(), water.trim()) },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                enabled = name.isNotBlank() && !deletingApiary,
                shape = RoundedCornerShape(18.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFFD97706),
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = androidx.compose.ui.graphics.Color(0xFFD97706).copy(alpha = 0.45f),
                    disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f)
                )
            ) {
                Text(if (existingApiary == null) "SAVE APIARY" else "SAVE CHANGES", fontWeight = FontWeight.ExtraBold)
            }

            if (existingApiary != null && onDelete != null) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("DANGER ZONE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
                        Text("Deleting this apiary also deletes its assigned hives and their histories. This cannot be undone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = {
                                deleteError = null
                                showDeleteConfirmation = true
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !deletingApiary,
                            shape = RoundedCornerShape(14.dp),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(if (deletingApiary) "DELETING…" else "DELETE APIARY", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation && existingApiary != null && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!deletingApiary) {
                    showDeleteConfirmation = false
                    deleteError = null
                }
            },
            title = { Text("Permanently delete apiary?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Deleting \"${existingApiary.name}\" will permanently delete this apiary, its map pin, and every hive assigned to it, including their inspection history, feeding records, treatments, harvests, and photos."
                    )
                    Text(
                        "This action cannot be undone.",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    deleteError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!deletingApiary) {
                            deletingApiary = true
                            deleteError = null
                            onDelete(existingApiary.id) { success, error ->
                                deletingApiary = false
                                if (success) showDeleteConfirmation = false
                                else deleteError = error ?: "Could not delete apiary."
                            }
                        }
                    },
                    enabled = !deletingApiary,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (deletingApiary) "DELETING…" else "DELETE APIARY", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!deletingApiary) {
                            showDeleteConfirmation = false
                            deleteError = null
                        }
                    },
                    enabled = !deletingApiary
                ) { Text("CANCEL") }
            }
        )
    }
    if (existingApiary == null && apiaryLimitPromptVisible) {
        AlertDialog(
            onDismissRequest = onDismissApiaryLimitPrompt,
            shape = RoundedCornerShape(26.dp),
            containerColor = androidx.compose.ui.graphics.Color(0xFFFFFBEB),
            titleContentColor = androidx.compose.ui.graphics.Color(0xFF78350F),
            textContentColor = androidx.compose.ui.graphics.Color(0xFF654321),
            title = { Text("Apiary Limit Reached", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(BeeKeepEntitlements.apiaryLimitMessage(currentPlan))
                    Text("Your existing apiaries and hive records remain safe.")
                }
            },
            confirmButton = {
                Button(onClick = onOpenUpgrade, shape = RoundedCornerShape(14.dp)) {
                    Text(
                        if (currentPlan == BeeKeepPlan.ULTIMATE || currentPlan == BeeKeepPlan.ULTIMATE_YEARLY) "VIEW PLAN DETAILS" else "VIEW UPGRADE PLANS",
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissApiaryLimitPrompt) {
                    Text("NOT NOW", color = androidx.compose.ui.graphics.Color(0xFF78350F))
                }
            }
        )
    }

}
