package com.beekeep.app

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
}

@Composable
internal fun AddApiaryScreen(
    locationController: LocationController,
    existingApiary: Apiary?,
    onBack: () -> Unit,
    onDelete: ((Long, (Boolean, String?) -> Unit) -> Unit)? = null,
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Rounded.ArrowBack,"Back")};Text(if (existingApiary == null) "Add Apiary" else "Edit Apiary",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold)}
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Apiary name")},singleLine=true)
        OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Site notes")})
        OutlinedTextField(forage,{forage=it},Modifier.fillMaxWidth(),label={Text("Forage notes")})
        OutlinedTextField(water,{water=it},Modifier.fillMaxWidth(),label={Text("Water notes")})
        OutlinedButton(onClick={
            if(locationController.hasPermission()) locationController.current { r ->
                if(r != null){lat=r.latitude;lon=r.longitude;gpsStatus="GPS captured"} else gpsStatus="Could not get a location"
            } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        },Modifier.fillMaxWidth().height(54.dp)){Icon(Icons.Rounded.LocationOn,null);Spacer(Modifier.width(6.dp));Text(if(lat==null)"CAPTURE CURRENT GPS" else "GPS CAPTURED")}
        if (lat != null && lon != null) {
            Text("Saved GPS: ${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lon)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text("Satellite preview • drag to move, pinch to zoom", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ApiarySatelliteMap(
                apiaries = listOf(
                    Apiary(
                        id = existingApiary?.id ?: Long.MIN_VALUE,
                        name = "Current apiary location",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
            TextButton(onClick = { lat = null; lon = null; gpsStatus = "GPS location removed" }) { Text("REMOVE GPS LOCATION") }
        }
        if(gpsStatus.isNotBlank()) Text(gpsStatus,color=if(gpsStatus.startsWith("Could") || gpsStatus.startsWith("Location permission denied"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)
        Button({onSave(existingApiary?.id, name.trim(),notes.trim(),lat,lon,forage.trim(),water.trim())},Modifier.fillMaxWidth().height(60.dp),enabled=name.isNotBlank(),shape=RoundedCornerShape(24.dp)){Text(if (existingApiary == null) "SAVE APIARY" else "SAVE CHANGES",fontWeight=FontWeight.ExtraBold)}

        if (existingApiary != null && onDelete != null) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    deleteError = null
                    showDeleteConfirmation = true
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !deletingApiary,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("DELETE APIARY", fontWeight = FontWeight.ExtraBold)
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
}
