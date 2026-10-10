package com.beekeep.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import com.beekeep.app.data.IdGenerator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.beekeep.app.data.ActivityEvent
import com.beekeep.app.data.Apiary
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import com.beekeep.app.data.LocalHiveRepository
import com.beekeep.app.data.Treatment
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.CloudSyncScheduler
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.location.LocationController
import com.beekeep.app.media.PhotoStore
import com.beekeep.app.nfc.NfcController
import com.beekeep.app.nfc.BeeKeepNfcPayload
import com.beekeep.app.nfc.NfcResult
import com.beekeep.app.ui.camera.CameraCaptureView
import com.beekeep.app.ui.theme.BeeKeepTheme
import com.beekeep.app.ui.theme.LandscapeDay
import com.beekeep.app.ui.theme.LandscapeDusk
import com.beekeep.app.ui.theme.NavBarDark
import com.beekeep.app.ui.theme.NavBarLight
import com.beekeep.app.ui.theme.NavIndicator
import com.beekeep.app.ui.theme.OnNavBar
import com.beekeep.app.ui.theme.OnNavBarMuted
import com.beekeep.app.ui.theme.Trail
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "beekeep_prefs"
private const val INSPECTION_NOTE_DRAFTS = "beekeep_inspection_note_drafts"

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val nfc = NfcController()
    internal val pendingNfcResult = MutableStateFlow<NfcResult.Read?>(null)
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    private var pendingSpeech: String? = null
    private lateinit var photoStore: PhotoStore
    private lateinit var locationController: LocationController
    private lateinit var cloudGateway: SupabaseGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speechEngine = TextToSpeech(this, this)
        nfc.attach(this)
        handleNfcIntent(intent)
        photoStore = PhotoStore(this)
        locationController = LocationController(this)
        val repository = LocalHiveRepository(applicationContext)
        // Keep NFC in foreground-reader mode while BeeKeep is visible. This takes
        // priority over Android's normal tag dispatch, so a tag can open its hive
        // without switching to a browser or another NFC app.
        nfc.setPassiveReadListener(this) { result ->
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val assignedHive = repository.findHiveByNfc(result.uid)
                    val payloadHive = BeeKeepNfcPayload.hiveId(result.text)?.let { repository.getHive(it) }
                    if (assignedHive != null || payloadHive != null) {
                        pendingNfcResult.value = result
                    }
                }
            }
        }
        val cloud = SupabaseGateway(applicationContext, repository)
        cloudGateway = cloud
        CloudSyncScheduler.schedule(applicationContext)
        val vm = ViewModelProvider(this, BeeKeepViewModel.Factory(repository, applicationContext))[BeeKeepViewModel::class.java]
        lifecycleScope.launch { cloud.syncNow() }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            var darkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            BeeKeepTheme(darkMode = darkMode) {
                androidx.compose.runtime.SideEffect {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !darkMode
                    controller.isAppearanceLightNavigationBars = false
                }
                val incomingNfc by pendingNfcResult.collectAsStateWithLifecycle()
                BeeKeepApp(
                    vm, nfc, this, photoStore, locationController, cloud, darkMode, incomingNfc,
                    onSpeakHiveNumber = ::speakHiveNumber
                ) { value ->
                    darkMode = value
                    prefs.edit { putBoolean("dark_mode", value) }
                }
            }
        }
    }


    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            speechReady = false
            pendingSpeech = null
            return
        }
        val engine = speechEngine ?: return
        val languageStatus = engine.setLanguage(Locale.getDefault())
        speechReady = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
        if (!speechReady) {
            pendingSpeech = null
            return
        }
        pendingSpeech?.let { phrase ->
            pendingSpeech = null
            speakText(phrase)
        }
    }

    internal fun speakHiveNumber(number: String) {
        val hiveNumber = number.trim()
        if (hiveNumber.isBlank()) return
        speakText("Hive $hiveNumber")
    }

    private fun speakText(phrase: String) {
        if (!speechReady) {
            // Hold the newest announcement if NFC is scanned before TTS finishes
            // initializing. This is particularly important for cold-start NFC taps.
            pendingSpeech = phrase
            return
        }
        speechEngine?.speak(
            phrase,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "beekeep-hive-${System.currentTimeMillis()}"
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        // NFC dispatch extras are already present in the Intent; parse them immediately
        // so the foreground reader cannot deliver the same physical tap first.
        nfc.readIntent(intent)?.let { result ->
            nfc.rememberHandledUid(result.uid)
            pendingNfcResult.value = result
        }
    }

    override fun onResume() {
        super.onResume()
        nfc.resume(this)
    }

    override fun onPause() {
        nfc.pause(this)
        super.onPause()
    }

    override fun onDestroy() {
        nfc.pause(this)
        if (::cloudGateway.isInitialized) cloudGateway.stopRealtime()
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
        speechReady = false
        super.onDestroy()
    }
}

enum class Screen {
    HOME,
    APIARIES,
    APIARY_HIVES,
    SCAN,
    MORE,
    TAG_MANAGER,
    COLONY_HISTORY
}

enum class HiveLogType { FEED, TREAT, HARVEST }

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0]
}

@Composable
fun BeeKeepApp(
    vm: BeeKeepViewModel,
    nfc: NfcController,
    activity: ComponentActivity,
    photoStore: PhotoStore,
    locationController: LocationController,
    cloud: SupabaseGateway,
    darkMode: Boolean,
    incomingNfc: NfcResult.Read?,
    onSpeakHiveNumber: (String) -> Unit,
    onDarkModeChange: (Boolean) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedHiveOpen by rememberSaveable { mutableStateOf(false) }
    var selectedApiaryName by rememberSaveable { mutableStateOf<String?>(null) }
    var inspecting by rememberSaveable { mutableStateOf(false) }
    var addHive by rememberSaveable { mutableStateOf(false) }
    var logType by rememberSaveable { mutableStateOf<HiveLogType?>(null) }
    var addApiary by rememberSaveable { mutableStateOf(false) }
    var editingApiary by remember { mutableStateOf<Apiary?>(null) }
    var unassignedTagUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTagUid by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var pendingLocationHive by remember { mutableStateOf<Hive?>(null) }
    var pendingApiaryMove by remember { mutableStateOf<Pair<Hive, Apiary>?>(null) }
    var locationCheckCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = androidx.compose.material3.SnackbarHostState()

    val hives by vm.hives.collectAsStateWithLifecycle()
    val deadHives by vm.deadHives.collectAsStateWithLifecycle()
    val apiaries by vm.apiaries.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val inspections by vm.inspections.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val feedings by vm.feedings.collectAsStateWithLifecycle()
    val treatments by vm.treatments.collectAsStateWithLifecycle()
    val harvests by vm.harvests.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationCheckCallback?.invoke()
        } else {
            pendingLocationHive = null
            scope.launch { snackbarHostState.showSnackbar("Location permission denied. Hive apiary was not changed.") }
        }
        locationCheckCallback = null
    }

    fun checkHiveLocation(hive: Hive) {
        val currentApiary = apiaries.firstOrNull { it.name.equals(hive.apiary, ignoreCase = true) }
        if (apiaries.none { it.latitude != null && it.longitude != null }) {
            scope.launch { snackbarHostState.showSnackbar("Save GPS coordinates for your apiaries to enable hive location checks.") }
            return
        }
        if (!locationController.hasPermission()) {
            pendingLocationHive = hive
            locationCheckCallback = { pendingLocationHive?.let { checkHiveLocation(it) } }
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        locationController.current { phoneLocation ->
            scope.launch {
                if (phoneLocation == null) {
                    snackbarHostState.showSnackbar("Could not get GPS. Hive apiary was not changed.")
                    return@launch
                }
                val assignedDistance = if (currentApiary?.latitude != null && currentApiary.longitude != null) {
                    distanceMeters(phoneLocation.latitude, phoneLocation.longitude, currentApiary.latitude, currentApiary.longitude)
                } else Float.POSITIVE_INFINITY
                // Ignore ordinary GPS drift. Only consider a move if the phone is
                // clearly away from the assigned yard and close to another saved yard.
                if (assignedDistance <= 300f) return@launch
                val nearestOther = apiaries
                    .filter { apiary ->
                        apiary.latitude != null && apiary.longitude != null &&
                            (currentApiary == null || apiary.id != currentApiary.id)
                    }
                    .map { apiary ->
                        apiary to distanceMeters(
                            phoneLocation.latitude, phoneLocation.longitude,
                            apiary.latitude!!, apiary.longitude!!
                        )
                    }
                    .filter { it.second <= 500f }
                    .minByOrNull { it.second }
                if (nearestOther != null) {
                    pendingApiaryMove = hive to nearestOther.first
                } else if (currentApiary != null) {
                    snackbarHostState.showSnackbar("Hive ${hive.number} is far from ${currentApiary.name}, but no other saved apiary GPS location is nearby. No changes made.")
                } else {
                    snackbarHostState.showSnackbar("No saved apiary GPS location is close enough to identify a destination. No changes made.")
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(ready, incomingNfc?.uid, screen) {
        val result = incomingNfc ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect

        // Manual NFC management and scan screens own their tag reads. Ignore
        // passive-reader notifications there, otherwise a just-assigned tag can
        // navigate away to its hive before the write prompt becomes visible.
        if (screen == Screen.TAG_MANAGER || screen == Screen.SCAN) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }

        val payloadHiveId = BeeKeepNfcPayload.hiveId(result.text)
        val assignedHive = vm.findHiveByTag(result.uid)
        val payloadHive = payloadHiveId?.let { id -> hives.firstOrNull { it.id == id } }
        val resolvedHive = assignedHive ?: payloadHive
        if (resolvedHive != null) {
            checkHiveLocation(resolvedHive)
            // Announce every accepted tag read, even when this hive is already open.
            onSpeakHiveNumber(resolvedHive.number)
            // A tag may be seen repeatedly while held near the phone. Do not
            // reload the same hive detail screen if it is already open.
            val alreadyOpen = selectedHiveOpen && selected?.id == resolvedHive.id
            if (!alreadyOpen) {
                vm.openHive(resolvedHive.id)
                selectedHiveOpen = true
                screen = Screen.HOME
                scope.launch { snackbarHostState.showSnackbar("Hive ${resolvedHive.number} recognized") }
            }
        } else {
            screen = Screen.SCAN
            unassignedTagUid = result.uid
        }
        (activity as? MainActivity)?.pendingNfcResult?.value = null
    }

    unassignedTagUid?.let { uid ->
        AlertDialog(
            onDismissRequest = { unassignedTagUid = null },
            title = { Text("Unassigned NFC tag", fontWeight = FontWeight.ExtraBold) },
            text = { Text("Tag $uid is not assigned to a colony. What would you like to do with it?") },
            confirmButton = {
                TextButton(onClick = {
                    unassignedTagUid = null
                    pendingTagUid = uid
                    screen = Screen.TAG_MANAGER
                }) { Text("ASSIGN TO EXISTING HIVE", fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        unassignedTagUid = null
                        pendingTagUid = uid
                        addHive = true
                    }) { Text("CREATE NEW HIVE") }
                    TextButton(onClick = { unassignedTagUid = null }) { Text("CANCEL") }
                }
            }
        )
    }

    pendingApiaryMove?.let { (hive, targetApiary) ->
        AlertDialog(
            onDismissRequest = { pendingApiaryMove = null },
            title = { Text("Hive scanned at another apiary?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("Hive ${hive.number} is assigned to ${hive.apiary}, but your phone's GPS is near ${targetApiary.name}. Would you like to move this hive to ${targetApiary.name}?")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.moveHiveToApiary(hive.id, targetApiary.name) { success, error ->
                        pendingApiaryMove = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (success) "Hive ${hive.number} moved to ${targetApiary.name}"
                                else error ?: "Could not move the hive."
                            )
                        }
                    }
                }) { Text("MOVE HIVE", fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingApiaryMove = null }) { Text("KEEP CURRENT APIARY") }
            }
        )
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BeeKeep", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Loading your hives…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    if (addHive) {
        AddHiveScreen(apiaries, selectedApiaryName, { addHive = false }) { number, apiary, queen, strength ->
            vm.createHive(number, apiary, queen, strength, tagUid = pendingTagUid) { success, error ->
                scope.launch { snackbarHostState.showSnackbar(error ?: "Hive ${number.trim()} created") }
                if (success) { addHive = false; pendingTagUid = null }
            }
        }
        return
    }
    if (editingApiary != null) {
        val apiaryToEdit = editingApiary!!
        AddApiaryScreen(locationController, apiaryToEdit, { editingApiary = null }) { id, name, notes, lat, lon, forage, water ->
            vm.updateApiary(id ?: apiaryToEdit.id, name, notes, lat, lon, forage, water) { success, error ->
                scope.launch { snackbarHostState.showSnackbar(error ?: "Apiary updated") }
                if (success) editingApiary = null
            }
        }
        return
    }
    if (addApiary) {
        AddApiaryScreen(locationController, null, { addApiary = false }) { _, name, notes, lat, lon, forage, water ->
            vm.saveApiary(name, notes, lat, lon, forage, water) { success, error ->
                scope.launch { snackbarHostState.showSnackbar(error ?: "Apiary saved") }
                if (success) addApiary = false
            }
        }
        return
    }
    if (inspecting) {
        val hiveForInspection = selected ?: run { inspecting = false; return }
        InspectionScreen(
            activity, photoStore, locationController, hiveForInspection,
            priorInspections = inspections,
            onBack = { inspecting = false },
            onSave = { inspection ->
                val saved = vm.saveInspection(inspection)
                if (saved) {
                    // Keep the full note log (including an intentional empty value) available for the next Inspect session.
                    activity.getSharedPreferences(INSPECTION_NOTE_DRAFTS, android.content.Context.MODE_PRIVATE)
                        .edit { putString("hive_${inspection.hiveId}", inspection.notes) }
                    inspecting = false
                    scope.launch { snackbarHostState.showSnackbar("Inspection saved") }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("Could not save inspection. Your field screen is still open; try again.") }
                }
            }
        )
        return
    }
    val currentLogType = logType
    val currentSelected = selected
    if (currentLogType != null && currentSelected != null) {
        when (currentLogType) {
            HiveLogType.FEED -> FeedDialog({ logType = null }) { type, ratio, amount, unit, notes ->
                vm.saveFeeding(type, ratio, amount, unit, notes) { success, error ->
                    if (success) logType = null
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Feeding logged") }
                }
            }
            HiveLogType.TREAT -> TreatmentDialog({ logType = null }) { type, product, days, withdrawal, notes ->
                vm.saveTreatment(type, product, days, withdrawal, notes) { success, error ->
                    if (success) logType = null
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Treatment logged") }
                }
            }
            HiveLogType.HARVEST -> HarvestDialog({ logType = null }) { supers, wet, dry, unit, wax, propolis, notes ->
                vm.saveHarvest(supers, wet, dry, unit, wax, propolis, notes) { success, error ->
                    if (success) logType = null
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Harvest logged") }
                }
            }
        }
        return
    }
    if (selectedHiveOpen) {
        val hiveForDetail = selected
        if (hiveForDetail == null) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Loading hive…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }
        HiveDetailScreen(
            hive = hiveForDetail,
            inspections = inspections,
            events = events,
            feedings = feedings,
            treatments = treatments,
            harvests = harvests,
            nfc = nfc,
            activity = activity,
            onBack = { selectedHiveOpen = false; vm.clearHive() },
            onInspect = { inspecting = true },
            onFeed = { logType = HiveLogType.FEED },
            onTreat = { logType = HiveLogType.TREAT },
            onHarvest = { logType = HiveLogType.HARVEST },
            onTag = { uid, onResult -> vm.assignTag(uid, onResult) },
            onClearTag = { vm.clearTag() },
            onVerifyTag = {
                nfc.startRead(activity) { result ->
                    when (result) {
                        is NfcResult.Read -> {
                            val matchesUid = result.uid.equals(hiveForDetail.tagUid, ignoreCase = true)
                            val payloadHive = BeeKeepNfcPayload.hiveId(result.text)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (matchesUid && (payloadHive == null || payloadHive == hiveForDetail.id)) "NFC tag verified"
                                    else "Tag read • ${result.uid} • does not match this hive"
                                )
                            }
                        }
                        is NfcResult.Error -> scope.launch { snackbarHostState.showSnackbar(result.message) }
                        is NfcResult.Written -> Unit
                    }
                }
            },
            onEditQueen = { status, mark, origin, age, temperament -> vm.updateQueenProfile(status, mark, origin, age, temperament) },
            onMarkDead = {
                vm.markHiveDead(hiveForDetail.id) { _, error ->
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Hive ${hiveForDetail.number} marked dead. History preserved.") }
                }
            },
            onRestore = {
                vm.restoreHive(hiveForDetail.id) { _, error ->
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Hive ${hiveForDetail.number} restored to active.") }
                }
            },
            onDelete = {
                vm.deleteHivePermanently(hiveForDetail.id) { success, error ->
                    if (success) { selectedHiveOpen = false; vm.clearHive() }
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Hive ${hiveForDetail.number} permanently deleted.") }
                }
            }
        )
        return
    }
    if (screen == Screen.SCAN) {
        ScanScreen(
            nfc.isAvailable(), nfc.isEnabled(), hives,
            scanning = scanning,
            onBack = { nfc.stop(activity); scanning = false; screen = Screen.HOME },
            onCancelScan = { nfc.stop(activity); scanning = false },
            onScan = {
                nfc.startRead(activity) { result ->
                    scanning = false
                    when (result) {
                        is NfcResult.Read -> {
                            scope.launch {
                                val payloadId = BeeKeepNfcPayload.hiveId(result.text)
                                val assignedHive = vm.findHiveByTag(result.uid)
                                val payloadHive = payloadId?.let { id -> hives.firstOrNull { it.id == id } }
                                val resolvedHive = assignedHive ?: payloadHive
                                if (resolvedHive != null) {
                                    checkHiveLocation(resolvedHive)
                                    onSpeakHiveNumber(resolvedHive.number)
                                    vm.openHive(resolvedHive.id)
                                    selectedHiveOpen = true
                                    screen = Screen.HOME
                                    snackbarHostState.showSnackbar("Hive ${resolvedHive.number} recognized")
                                } else {
                                    unassignedTagUid = result.uid
                                }
                            }
                        }
                        is NfcResult.Error -> scope.launch { snackbarHostState.showSnackbar(result.message) }
                        is NfcResult.Written -> Unit
                    }
                }
                scanning = nfc.isScanning()
            }
        )
        return
    }
    if (screen == Screen.APIARY_HIVES) {
        val apiaryName = selectedApiaryName
        if (apiaryName == null) {
            screen = Screen.APIARIES
            return
        }
        ApiaryHivesScreen(
            apiaryName = apiaryName,
            hives = hives.filter { it.apiary.equals(apiaryName, ignoreCase = true) },
            padding = androidx.compose.foundation.layout.PaddingValues(),
            onBack = { selectedApiaryName = null; screen = Screen.APIARIES },
            onAddHive = { addHive = true },
            onOpenHive = { vm.openHive(it); selectedHiveOpen = true }
        )
        return
    }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = if (darkMode) NavBarDark else NavBarLight, contentColor = OnNavBar, tonalElevation = 0.dp) {
                NavItem("Home", Icons.Rounded.Home, screen == Screen.HOME) { screen = Screen.HOME }
                NavItem("Apiaries", Icons.Rounded.Yard, screen == Screen.APIARIES || screen == Screen.APIARY_HIVES) { selectedApiaryName = null; screen = Screen.APIARIES }
                NavItem("Scan", Icons.Rounded.Nfc, screen == Screen.SCAN) { screen = Screen.SCAN }
                NavItem("More", Icons.Rounded.Settings, screen == Screen.MORE) { screen = Screen.MORE }
            }
        }
    ) { padding ->
        when (screen) {
            Screen.HOME -> HomeScreen(hives, padding, onScan = { screen = Screen.SCAN })
            Screen.APIARIES -> ApiariesScreen(apiaries, hives, padding, onAddApiary = { addApiary = true }, onEditApiary = { editingApiary = it }, onOpenApiary = { selectedApiaryName = it; screen = Screen.APIARY_HIVES })
            Screen.MORE -> MoreScreen(padding, darkMode, onDarkModeChange, onTagManager = { screen = Screen.TAG_MANAGER }, onColonyHistory = { screen = Screen.COLONY_HISTORY }, deadCount = deadHives.size, activity, cloud)
            Screen.TAG_MANAGER -> TagManagementScreen(
                padding = padding,
                hives = hives,
                nfc = nfc,
                activity = activity,
                pendingUid = pendingTagUid,
                onPendingUidConsumed = { pendingTagUid = null },
                onBack = { screen = Screen.MORE },
                onOpenHive = { vm.openHive(it); selectedHiveOpen = true; screen = Screen.HOME },
                onAssignTag = { hiveId, uid, reassign, onResult -> vm.assignTagToHive(hiveId, uid, reassign, onResult) },
                onClearTag = { hiveId -> vm.clearTagForHive(hiveId) }
            )
            Screen.COLONY_HISTORY -> ColonyHistoryScreen(
                deadHives = deadHives,
                padding = padding,
                onBack = { screen = Screen.MORE },
                onOpenHive = { vm.openHive(it); selectedHiveOpen = true }
            )
            Screen.SCAN, Screen.APIARY_HIVES -> Unit
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label, maxLines = 1) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Trail.Bark,
            selectedTextColor = OnNavBar,
            indicatorColor = NavIndicator,
            unselectedIconColor = OnNavBarMuted,
            unselectedTextColor = OnNavBarMuted
        )
    )
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(title, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
            supporting?.let { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun LandscapeCanvas(modifier: Modifier, palette: com.beekeep.app.ui.theme.LandscapePalette) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(palette.sky)
        drawCircle(palette.sun, radius = h * 0.15f, center = Offset(w * 0.80f, h * 0.26f))
        drawPath(Path().apply {
            moveTo(0f, h * 0.60f)
            cubicTo(w * 0.16f, h * 0.42f, w * 0.30f, h * 0.54f, w * 0.44f, h * 0.38f)
            cubicTo(w * 0.60f, h * 0.22f, w * 0.74f, h * 0.46f, w, h * 0.34f)
            lineTo(w, h); lineTo(0f, h); close()
        }, palette.hillBack)
        drawPath(Path().apply {
            moveTo(0f, h * 0.76f)
            cubicTo(w * 0.22f, h * 0.58f, w * 0.38f, h * 0.72f, w * 0.55f, h * 0.56f)
            cubicTo(w * 0.74f, h * 0.42f, w * 0.86f, h * 0.64f, w, h * 0.54f)
            lineTo(w, h); lineTo(0f, h); close()
        }, palette.hillMid)
        drawPath(Path().apply {
            moveTo(0f, h * 0.90f)
            cubicTo(w * 0.25f, h * 0.76f, w * 0.45f, h * 0.94f, w * 0.68f, h * 0.80f)
            cubicTo(w * 0.84f, h * 0.72f, w * 0.94f, h * 0.84f, w, h * 0.78f)
            lineTo(w, h); lineTo(0f, h); close()
        }, palette.hillFront)
        listOf(w * 0.62f to h * 0.88f, w * 0.72f to h * 0.82f, w * 0.90f to h * 0.80f).forEach { (cx, base) ->
            val ph = h * 0.30f
            for (tier in 0..2) {
                val top = base - ph + tier * ph * 0.26f
                val half = ph * 0.16f * (0.6f + tier * 0.4f)
                drawPath(Path().apply {
                    moveTo(cx, top)
                    lineTo(cx + half, top + ph * 0.45f)
                    lineTo(cx - half, top + ph * 0.45f)
                    close()
                }, palette.tree)
            }
            drawRect(palette.tree, Offset(cx - w * 0.005f, base - ph * 0.08f), Size(w * 0.010f, ph * 0.10f))
        }
    }
}

@Composable
private fun HomeScreen(
    hives: List<Hive>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onScan: () -> Unit
) {
    val flagged = hives.count { it.mitePercent >= 3.0 || it.queenStatus == "Queenless" }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val landscape = if (MaterialTheme.colorScheme.background.luminance() < 0.35f) LandscapeDusk else LandscapeDay
            Box(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(24.dp))) {
                LandscapeCanvas(Modifier.fillMaxSize(), landscape)
                Column(Modifier.align(Alignment.TopStart).padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("GOOD FIELD DAY", style = MaterialTheme.typography.labelLarge, color = landscape.ink.copy(alpha = .8f))
                    Text("BeeKeep", style = MaterialTheme.typography.headlineLarge, color = landscape.ink)
                    Text("Your operation at a glance", color = landscape.ink.copy(alpha = .72f))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Hives", hives.size.toString(), Modifier.weight(1f))
                MetricCard("Watch", flagged.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Fast field scan", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Tap a hive tag and jump straight to its record.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .88f))
                    }
                    Button(onClick = onScan, shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Rounded.Nfc, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("SCAN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hive lists are organized by apiary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Open Apiaries to choose a yard, then see only the hives belonging to that apiary.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ApiariesScreen(
    apiaries: List<Apiary>,
    hives: List<Hive>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onAddApiary: () -> Unit,
    onEditApiary: (Apiary) -> Unit,
    onOpenApiary: (String) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = apiaries.filter { it.name.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apiaries", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("${filtered.size} ${if (filtered.size == 1) "apiary" else "apiaries"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAddApiary, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Add, "Add apiary") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search apiaries") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (apiaries.isEmpty()) "No apiaries yet" else "No matching apiaries",
                    if (apiaries.isEmpty()) "Create an apiary first, then add hives inside it." else "Try a different apiary name.",
                    if (apiaries.isEmpty()) "ADD APIARY" else null,
                    if (apiaries.isEmpty()) onAddApiary else null
                )
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { apiary ->
                    val count = hives.count { it.apiary.equals(apiary.name, ignoreCase = true) }
                    Card(onClick = { onOpenApiary(apiary.name) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Yard, "Apiary", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(apiary.name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Text("$count ${if (count == 1) "hive" else "hives"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                apiary.notes.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { onEditApiary(apiary) }) { Icon(Icons.Rounded.Edit, "Edit apiary ${apiary.name}") }
                                if (apiary.latitude != null && apiary.longitude != null) {
                                    IconButton(onClick = {
                                        val uri = android.net.Uri.parse("geo:${apiary.latitude},${apiary.longitude}?q=${apiary.latitude},${apiary.longitude}(${android.net.Uri.encode(apiary.name)})")
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                    }) { Icon(Icons.Rounded.LocationOn, "Open apiary location in maps") }
                                } else {
                                    Icon(Icons.Rounded.ChevronRight, "Open apiary", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiaryHivesScreen(
    apiaryName: String,
    hives: List<Hive>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onAddHive: () -> Unit,
    onOpenHive: (Long) -> Unit
) {
    BackHandler { onBack() }
    var query by rememberSaveable(apiaryName) { mutableStateOf("") }
    val filtered = hives.filter { it.number.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(padding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to apiaries") }
            Column(Modifier.weight(1f)) {
                Text(apiaryName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("${hives.size} ${if (hives.size == 1) "hive" else "hives"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAddHive, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Add, "Add hive") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search hive number") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (hives.isEmpty()) "No hives in this apiary" else "No matching hives",
                    if (hives.isEmpty()) "Add a hive to $apiaryName to start its record." else "Try a different hive number.",
                    if (hives.isEmpty()) "ADD HIVE" else null,
                    if (hives.isEmpty()) onAddHive else null
                )
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { hive ->
                    HiveRow(hive) { onOpenHive(hive.id) }
                }
            }
        }
    }
}

@Composable
private fun HiveRow(hive: Hive, supporting: String? = null, onClick: () -> Unit) {
    val attention = hive.mitePercent >= 3.0 || hive.queenStatus == "Queenless"
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), border = if (attention) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .35f)) else null) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Hive ${hive.number}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Queen ${hive.queenStatus} • Strength ${hive.strength}/10", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                supporting?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${String.format(Locale.US, "%.1f", hive.mitePercent)}%", fontWeight = FontWeight.ExtraBold, color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Icon(Icons.Rounded.ChevronRight, "Open hive", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ColonyHistoryScreen(deadHives: List<Hive>, padding: androidx.compose.foundation.layout.PaddingValues, onBack: () -> Unit, onOpenHive: (Long) -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Colony history", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("${deadHives.size} dead ${if (deadHives.size == 1) "colony" else "colonies"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (deadHives.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No dead colonies", "When a colony dies, mark it dead to keep its history here and release its NFC tag.", null, null)
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(deadHives, key = { it.id }) { hive ->
                    Card(onClick = { onOpenHive(hive.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Hive ${hive.number}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "Dead: ${hive.deadAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "date unknown"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, "Open colony history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CompactStatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
    }
}

@Composable
private fun HiveDetailScreen(
    hive: Hive,
    inspections: List<Inspection>,
    events: List<ActivityEvent>,
    feedings: List<com.beekeep.app.data.Feeding>,
    treatments: List<Treatment>,
    harvests: List<Harvest>,
    nfc: NfcController,
    activity: ComponentActivity,
    onBack: () -> Unit,
    onInspect: () -> Unit,
    onFeed: () -> Unit,
    onTreat: () -> Unit,
    onHarvest: () -> Unit,
    onTag: (String, (Boolean, String?) -> Unit) -> Unit,
    onClearTag: () -> Unit,
    onVerifyTag: () -> Unit,
    onEditQueen: (String, String, String, Int?, Int) -> Unit,
    onMarkDead: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    var writeStatus by rememberSaveable { mutableStateOf("") }
    var editQueen by rememberSaveable { mutableStateOf(false) }
    var confirmDead by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    BackHandler { onBack() }
    val recent = inspections.take(6).reversed()
    val photoInspections = inspections.filter { !it.photoPath.isNullOrBlank() }.take(6)

    if (editQueen) {
        QueenProfileDialog(
            hive = hive,
            onDismiss = { editQueen = false },
            onSave = { status, mark, origin, age, temperament ->
                onEditQueen(status, mark, origin, age, temperament)
                editQueen = false
            }
        )
        return
    }

    if (confirmDead) {
        AlertDialog(
            onDismissRequest = { confirmDead = false },
            title = { Text("Mark colony dead?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("Hive ${hive.number} will move to colony history. All inspections, treatments, feedings, harvests and photos stay attached. Its NFC tag is released for reuse.") },
            confirmButton = { TextButton(onClick = { confirmDead = false; onMarkDead() }) { Text("MARK DEAD", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDead = false }) { Text("CANCEL") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Hive ${hive.number} permanently?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("This removes the hive and all of its associated history. This cannot be undone. To preserve history, mark the colony dead instead.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("DELETE PERMANENTLY", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") } }
        )
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Hive ${hive.number}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            HiveStatusPill(hive)
        }

        if (hive.isDead) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Colony marked dead", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Died ${hive.deadAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "on an unknown date"}. History is preserved below and the NFC tag was released.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (!hive.isDead) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onInspect,
                    modifier = Modifier.weight(1.3f).height(42.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(Icons.Rounded.TaskAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("INSPECT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onFeed,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("FEED", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onTreat,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("TREAT", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onHarvest,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("HARVEST", style = MaterialTheme.typography.labelMedium) }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactStatItem("Strength", "${hive.strength}/10", Modifier.weight(1f))
                    CompactStatItem("Mites", "${String.format(Locale.US, "%.2f", hive.mitePercent)}%", Modifier.weight(1f))
                    CompactStatItem("Inspections", inspections.size.toString(), Modifier.weight(1f))
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Queen profile", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("• ${hive.queenStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "${hive.queenMarkColor.ifBlank { "No mark" }} • ${hive.queenOrigin.ifBlank { "Origin n/a" }} • ${hive.queenAgeMonths?.let { "${it}mo" } ?: "Age n/a"} • Temp ${hive.queenTemperament}/5",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { editQueen = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("EDIT", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Strength & mite trend", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (recent.isEmpty()) {
                    Text("Complete a few inspections to see trends.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.forEach { item ->
                            val height = (16 + item.strength.coerceIn(0, 10) * 5).dp
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                Text("${item.strength}", style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.width(20.dp).height(height).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
                                Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.createdAt)), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val latestMite = recent.last().mitePercent
                    Text("Latest mite rate ${String.format(Locale.US, "%.2f", latestMite)}%", style = MaterialTheme.typography.bodySmall, color = if (latestMite >= 3.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (photoInspections.isNotEmpty()) {
            Text("Recent photos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photoInspections.forEach { inspection ->
                    rememberPhotoBitmap(inspection.photoPath, 480)?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), "Inspection photo", Modifier.size(90.dp).clip(RoundedCornerShape(16.dp)))
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Records", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Feedings: ${feedings.size} • Treatments: ${treatments.size} • Harvests: ${harvests.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                feedings.firstOrNull()?.let { Text("Last feed: ${it.amount} ${it.unit} • ${it.feedType}", style = MaterialTheme.typography.bodySmall) }
                treatments.firstOrNull()?.let { Text("Last treatment: ${it.product}", style = MaterialTheme.typography.bodySmall) }
                harvests.firstOrNull()?.let { Text("Last dry harvest: ${it.dryHoneyWeight} ${it.weightUnit}", style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NFC hive tag", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (hive.isDead) {
                    Text("Released", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("The physical tag was released when the colony died.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(hive.tagUid ?: "Not assigned", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onVerifyTag,
                            enabled = hive.tagUid != null,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.Nfc, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("VERIFY", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                nfc.startWrite(
                                    activity = activity,
                                    text = BeeKeepNfcPayload.forHive(hive.id),
                                    allowOverwriteOtherHive = hive.tagUid != null,
                                    onResult = { result ->
                                        when (result) {
                                            is NfcResult.Written -> onTag(result.uid) { success, error ->
                                                writeStatus = if (success) "Tag written • ${result.uid}" else (error ?: "Could not assign tag.")
                                            }
                                            is NfcResult.Error -> writeStatus = result.message
                                            is NfcResult.Read -> Unit
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(if (hive.tagUid == null) "WRITE TAG" else "REPLACE", style = MaterialTheme.typography.labelMedium) }
                    }
                    if (hive.tagUid != null) {
                        TextButton(onClick = onClearTag, modifier = Modifier.fillMaxWidth().height(36.dp)) { Text("REMOVE TAG", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (writeStatus.isNotBlank()) Text(writeStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Colony lifecycle", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (hive.isDead) {
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(16.dp)) { Text("RESTORE COLONY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
                } else {
                    OutlinedButton(onClick = { confirmDead = true }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(16.dp)) { Text("MARK COLONY DEAD", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(36.dp)) { Text("DELETE HIVE PERMANENTLY", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }

        Text("Recent activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (events.isEmpty()) Text("No events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        for (event in events.take(15)) { EventCard(event) }
        inspections.firstOrNull()?.let { InspectionSnapshot(it) }
    }
}

@Composable
private fun HiveStatusPill(hive: Hive) {
    if (hive.isDead) {
        Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("DEAD", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        return
    }
    val flagged = hive.queenStatus == "Queenless" || hive.mitePercent >= 3.0
    Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = if (flagged) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Text(if (flagged) "CHECK" else "OK", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.ExtraBold, color = if (flagged) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun QueenProfileDialog(
    hive: Hive,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int?, Int) -> Unit
) {
    var status by rememberSaveable { mutableStateOf(hive.queenStatus) }
    var mark by rememberSaveable { mutableStateOf(hive.queenMarkColor) }
    var origin by rememberSaveable { mutableStateOf(hive.queenOrigin) }
    var age by rememberSaveable { mutableStateOf(hive.queenAgeMonths?.toString() ?: "") }
    var temperament by rememberSaveable { mutableIntStateOf(hive.queenTemperament.coerceIn(1, 5)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Queen profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (st in listOf("Laying", "Spotted", "Unspotted", "Queenless", "Virgin")) {
                        FilterChip(status == st, { status = st }, { Text(st) })
                    }
                }
                OutlinedTextField(mark, { mark = it }, Modifier.fillMaxWidth(), label = { Text("Mark color") }, singleLine = true)
                OutlinedTextField(origin, { origin = it }, Modifier.fillMaxWidth(), label = { Text("Origin") }, singleLine = true)
                OutlinedTextField(age, { age = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Age (months)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Temperament ${temperament}/5", fontWeight = FontWeight.Bold)
                Counter("Temperament", temperament, 1..5) { temperament = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(status, mark, origin, age.toIntOrNull(), temperament) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable private fun EventCard(event:ActivityEvent){Card(shape=RoundedCornerShape(16.dp)){Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Rounded.Yard,null);Column{Text(event.title,fontWeight=FontWeight.Bold);Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(event.createdAt)),color=MaterialTheme.colorScheme.onSurfaceVariant);if(event.detail.isNotBlank())Text(event.detail)}}}}
@Composable
private fun InspectionSnapshot(i: Inspection) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${i.strength}/10 strength • ${i.queenStatus}")
            Text("Mites ${i.miteCount}/${i.sampleSize} = ${String.format(Locale.US, "%.2f", i.mitePercent)}%")
            Text("Brood: eggs ${i.eggs}, open ${i.openBrood}, capped ${i.cappedBrood}")
            Text("Stores: honey ${i.honeyStores}, pollen ${i.pollen}")
            if (i.diseaseFlags.isNotBlank()) {
                Text("Flags: ${i.diseaseFlags}", color = MaterialTheme.colorScheme.error)
            }
            if (i.notes.isNotBlank()) {
                Text("FIELD NOTES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                Text(i.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InspectionScreen(
    activity: ComponentActivity,
    photoStore: PhotoStore,
    locationController: LocationController,
    hive: Hive,
    priorInspections: List<Inspection>,
    onBack: () -> Unit,
    onSave: suspend (Inspection) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noteDraftPrefs = remember(context) {
        context.getSharedPreferences(INSPECTION_NOTE_DRAFTS, android.content.Context.MODE_PRIVATE)
    }
    val noteDraftKey = remember(hive.id) { "hive_${hive.id}" }
    val lastInspection = remember(priorInspections) { priorInspections.maxByOrNull { it.createdAt } }
    val hasSavedNoteDraft = remember(hive.id) { noteDraftPrefs.contains(noteDraftKey) }
    val savedNoteDraft = remember(hive.id) { noteDraftPrefs.getString(noteDraftKey, "").orEmpty() }
    // A stored empty string means the beekeeper deliberately cleared the notes; don't restore older text.
    val startingNotes = remember(hive.id, hasSavedNoteDraft, savedNoteDraft, lastInspection?.id, lastInspection?.notes) {
        if (hasSavedNoteDraft) savedNoteDraft else lastInspection?.notes.orEmpty()
    }

    var strength by rememberSaveable { mutableIntStateOf(hive.strength) }
    var queen by rememberSaveable { mutableStateOf(hive.queenStatus) }
    var mites by rememberSaveable { mutableIntStateOf(lastInspection?.miteCount ?: 0) }
    var sample by rememberSaveable { mutableIntStateOf(lastInspection?.sampleSize ?: 300) }
    var notes by rememberSaveable(hive.id) { mutableStateOf(startingNotes) }
    var noteSaveStatus by rememberSaveable(hive.id) {
        mutableStateOf(if (startingNotes.isBlank()) "" else if (hasSavedNoteDraft) "Auto-saved note restored" else "Saved notes loaded")
    }
    var eggs by rememberSaveable { mutableIntStateOf(lastInspection?.eggs ?: 0) }
    var openBrood by rememberSaveable { mutableIntStateOf(lastInspection?.openBrood ?: 0) }
    var cappedBrood by rememberSaveable { mutableIntStateOf(lastInspection?.cappedBrood ?: 0) }
    var honey by rememberSaveable { mutableIntStateOf(lastInspection?.honeyStores ?: 0) }
    var pollen by rememberSaveable { mutableIntStateOf(lastInspection?.pollen ?: 0) }
    var emptyComb by rememberSaveable { mutableIntStateOf(lastInspection?.emptyDrawnComb ?: 0) }
    var emergency by rememberSaveable { mutableIntStateOf(0) }
    var supercedure by rememberSaveable { mutableIntStateOf(0) }
    var swarm by rememberSaveable { mutableIntStateOf(0) }
    var diseasesCsv by rememberSaveable { mutableStateOf("") }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraOpen by rememberSaveable { mutableStateOf(false) }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lon by rememberSaveable { mutableStateOf<Double?>(null) }
    var locationStatus by rememberSaveable { mutableStateOf("No GPS captured") }
    var voiceStatus by rememberSaveable { mutableStateOf("") }
    var cameraStatus by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var photoCaptured by rememberSaveable { mutableStateOf(false) }
    var photoProcessing by rememberSaveable { mutableStateOf(false) }
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }


    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraStatus = ""
            cameraOpen = true
        } else {
            pendingPhotoPath?.let { File(it).delete() }
            pendingPhotoPath = null
            cameraStatus = "Camera permission denied"
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val granted = r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            locationStatus = "Location permission denied"
            return@rememberLauncherForActivityResult
        }
        locationController.current { loc ->
            if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                locationStatus = "GPS captured • ${"%.5f".format(Locale.US, loc.latitude)}, ${"%.5f".format(Locale.US, loc.longitude)}"
            } else locationStatus = "Could not get a location"
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spokenText ->
            // Each completed dictation is a separate note entry. Keep earlier notes and add this one below.
            val cleanSpokenText = spokenText.trim()
            val updatedNotes = if (notes.isBlank()) cleanSpokenText else "${notes.trimEnd()}\n$cleanSpokenText"
            notes = updatedNotes
            // Save immediately when Android returns the finished speech transcription.
            noteDraftPrefs.edit { putString(noteDraftKey, updatedNotes) }
            noteSaveStatus = "Note auto-saved"
            voiceStatus = "Voice added"
        }
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe what you see in Hive ${hive.number}")
            }
            runCatching { voiceLauncher.launch(intent) }.onFailure { voiceStatus = "Voice input unavailable" }
        } else voiceStatus = "Microphone permission denied"
    }

    androidx.compose.runtime.LaunchedEffect(hive.id, notes) {
        // Debounce edits so a longer note is stored when the user pauses typing.
        delay(400)
        // Store blank too: it records an intentional manual clear rather than reviving old notes later.
        noteDraftPrefs.edit { putString(noteDraftKey, notes) }
        noteSaveStatus = if (notes.isBlank()) "" else "Note auto-saved"
    }

    BackHandler {
        if (cameraOpen && pendingPhotoPath != null) {
            if (!photoCaptured) File(pendingPhotoPath!!).delete()
            pendingPhotoPath = null
            cameraOpen = false
        } else {
            photoPath?.let { File(it).delete() }
            pendingPhotoPath?.let { File(it).delete() }
            onBack()
        }
    }

    if (cameraOpen && pendingPhotoPath != null) {
        val pendingFile = File(pendingPhotoPath!!)
        CameraCaptureView(
            outputFile = pendingFile,
            onCaptured = {
                val previousPhoto = photoPath
                photoPath = pendingFile.absolutePath
                if (!previousPhoto.isNullOrBlank() && previousPhoto != pendingFile.absolutePath) {
                    File(previousPhoto).delete()
                }
                photoCaptured = true
                photoProcessing = true
                cameraOpen = false
                pendingPhotoPath = null
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val optimized = photoStore.optimizeInPlace(pendingFile)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        photoProcessing = false
                        cameraStatus = if (optimized) "Photo ready" else "Photo saved; optimization unavailable"
                    }
                }
            },
            onClose = {
                if (!photoCaptured) pendingFile.delete()
                pendingPhotoPath = null
                cameraOpen = false
            },
            onError = { message ->
                cameraStatus = message
                if (!photoCaptured) pendingFile.delete()
                pendingPhotoPath = null
                cameraOpen = false
            }
        )
        return
    }

    val miteRate = if (sample > 0) mites * 100.0 / sample else 0.0
    val previousForComparison = remember(priorInspections) {
        priorInspections.sortedByDescending { it.createdAt }.getOrNull(1)
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("Hive ${hive.number}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("FIELD INSPECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },

    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldActionButton(Icons.Rounded.CameraAlt, "PHOTO", Modifier.weight(1f)) {
                                val file = photoStore.createInspectionPhoto(hive.id).file
                                pendingPhotoPath = file.absolutePath
                                photoCaptured = false
                                cameraStatus = ""
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    cameraOpen = true
                                } else cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                            FieldActionButton(Icons.Rounded.Mic, "VOICE", Modifier.weight(1f)) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe Hive ${hive.number}")
                                    }
                                    runCatching { voiceLauncher.launch(intent) }.onFailure { voiceStatus = "Voice input unavailable" }
                                } else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldActionButton(Icons.Rounded.GpsFixed, "GPS", Modifier.weight(1f)) {
                                if (locationController.hasPermission()) {
                                    locationController.current { loc ->
                                        if (loc != null) {
                                            lat = loc.latitude
                                            lon = loc.longitude
                                            locationStatus = "GPS captured • ${"%.5f".format(Locale.US, loc.latitude)}, ${"%.5f".format(Locale.US, loc.longitude)}"
                                        } else locationStatus = "Could not get a location"
                                    }
                                } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                            Button(
                                onClick = {
                                    if (!saving && !photoProcessing) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        saving = true
                                        val now = System.currentTimeMillis()
                                        val inspection = Inspection(
                                            IdGenerator.nextLong(), hive.id, now, strength, queen, mites,
                                            sample.coerceAtLeast(1), notes.trim(), photoPath, lat, lon,
                                            emergency, supercedure, swarm, eggs, openBrood, cappedBrood,
                                            honey, pollen, emptyComb, diseasesCsv
                                        )
                                        scope.launch {
                                            onSave(inspection)
                                            saving = false
                                        }
                                    }
                                },
                                enabled = !saving && !photoProcessing,
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Check, null)
                                Spacer(Modifier.width(5.dp))
                                Text(if (saving) "SAVING…" else "SAVE", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                        }
                        if (photoProcessing) {
                            Text("Preparing photo…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                SectionHeader("QUEEN")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in listOf("Laying", "Spotted", "Unspotted", "Queenless", "Virgin")) {
                        FilterChip(
                            selected = queen == option,
                            onClick = {
                                queen = option
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            },
                            label = { Text(option) }
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FIELD NOTES", fontWeight = FontWeight.ExtraBold)
                        OutlinedTextField(
                            value = notes,
                            onValueChange = {
                                notes = it
                                noteSaveStatus = if (it.isBlank()) "" else "Saving note…"
                            },
                            modifier = Modifier.fillMaxWidth().height(124.dp),
                            label = { Text("What did you see?") }
                        )
                        if (noteSaveStatus.isNotBlank()) {
                            Text(
                                noteSaveStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (voiceStatus.isNotBlank()) Text(voiceStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (locationStatus != "No GPS captured") Text(locationStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                ComparisonCounter("Colony strength", strength, previousForComparison?.strength, 0..10, haptic) { strength = it }
            }
            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("Mite wash", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                                Text("${mites} / ${sample} bees", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(String.format(Locale.US, "%.2f%%", miteRate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumberField("Mites", mites, Modifier.weight(1f)) { mites = it.coerceIn(0, sample) }
                            NumberField("Sample", sample, Modifier.weight(1f)) {
                                sample = it.coerceAtLeast(1)
                                mites = mites.coerceAtMost(sample)
                            }
                        }
                        val priorMite = previousForComparison?.mitePercent
                        if (priorMite != null) {
                            Text(
                                "Previous ${String.format(Locale.US, "%.2f%%", priorMite)} • Change ${String.format(Locale.US, "%+.2f%%", miteRate - priorMite)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (miteRate > priorMite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("BROOD & STORES")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactCounter("Eggs", eggs, 0..30, haptic, Modifier.weight(1f)) { eggs = it }
                    CompactCounter("Open", openBrood, 0..30, haptic, Modifier.weight(1f)) { openBrood = it }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactCounter("Capped", cappedBrood, 0..30, haptic, Modifier.weight(1f)) { cappedBrood = it }
                    CompactCounter("Honey", honey, 0..30, haptic, Modifier.weight(1f)) { honey = it }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactCounter("Pollen", pollen, 0..30, haptic, Modifier.weight(1f)) { pollen = it }
                    CompactCounter("Empty comb", emptyComb, 0..30, haptic, Modifier.weight(1f)) { emptyComb = it }
                }
            }

            item {
                SectionHeader("QUEEN CELLS")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactCounter("Emergency", emergency, 0..20, haptic, Modifier.weight(1f)) { emergency = it }
                    CompactCounter("Supercedure", supercedure, 0..20, haptic, Modifier.weight(1f)) { supercedure = it }
                }
            }
            item {
                CompactCounter("Swarm", swarm, 0..20, haptic, Modifier.fillMaxWidth()) { swarm = it }
            }

            item {
                SectionHeader("HEALTH FLAGS")
                val activeDiseases = remember(diseasesCsv) { diseasesCsv.split(",").map(String::trim).filter(String::isNotBlank).toSet() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (flag in listOf("AFB", "EFB", "Chalkbrood", "Small Hive Beetle", "Wax Moth", "Nosema")) {
                        val active = activeDiseases.contains(flag)
                        FilterChip(
                            selected = active,
                            onClick = {
                                val current = activeDiseases.toMutableSet()
                                if (active) current.remove(flag) else current.add(flag)
                                diseasesCsv = current.joinToString(", ")
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            },
                            label = { Text(flag) }
                        )
                    }
                }
            }



            item {
                photoPath?.let { path ->
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PHOTO", fontWeight = FontWeight.ExtraBold)
                            rememberPhotoBitmap(path, 1200)?.let { bitmap ->
                                Image(bitmap.asImageBitmap(), "Inspection photo", Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp).clip(RoundedCornerShape(16.dp)))
                            }
                            if (photoProcessing) Text("Preparing photo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cameraStatus.isNotBlank()) Text(cameraStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun FieldActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    ) {
        Icon(icon, contentDescription = label)
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp))
}

@Composable
private fun ComparisonCounter(
    label: String,
    value: Int,
    previous: Int?,
    range: IntRange,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onChange: (Int) -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                previous?.let {
                    val delta = value - it
                    Text(
                        "Prev $it • ${if (delta >= 0) "+$delta" else delta}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (delta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value - 1).coerceIn(range)) },
                    modifier = Modifier.size(36.dp)
                ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value + 1).coerceIn(range)) },
                    modifier = Modifier.size(36.dp)
                ) { Text("+", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun CompactCounter(
    label: String,
    value: Int,
    range: IntRange,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value - 1).coerceIn(range)) },
                modifier = Modifier.size(32.dp)
            ) { Text("−", style = MaterialTheme.typography.bodyMedium) }
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value + 1).coerceIn(range)) },
                modifier = Modifier.size(32.dp)
            ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun rememberPhotoBitmap(path: String?, maxDimension: Int): Bitmap? {
    val currentPath = path
    return produceState<Bitmap?>(initialValue = null, key1 = currentPath, key2 = maxDimension) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { PhotoStore.decodeSampled(currentPath, maxDimension) }
        awaitDispose { value?.recycle() }
    }.value
}

@Composable private fun Counter(label:String,value:Int,range:IntRange,onChange:(Int)->Unit){Card(shape=RoundedCornerShape(16.dp)){Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(label,fontWeight=FontWeight.Bold)};IconButton({onChange((value-1).coerceIn(range))}){Text("−",style=MaterialTheme.typography.headlineMedium)};Text(value.toString(),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold);IconButton({onChange((value+1).coerceIn(range))}){Text("+",style=MaterialTheme.typography.headlineMedium)}}}}
@Composable private fun NumberField(label:String,value:Int,mod:Modifier,onChange:(Int)->Unit){OutlinedTextField(value.toString(),{it.filter(Char::isDigit).toIntOrNull()?.let(onChange)},mod,label={Text(label)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)}

@Composable private fun AddHiveScreen(apiaries:List<Apiary>, initialApiary: String?, onBack:()->Unit, onCreate:(String,String,String,Int)->Unit){BackHandler{onBack()};var number by rememberSaveable{mutableStateOf("")};var apiary by rememberSaveable(initialApiary) { mutableStateOf(initialApiary ?: apiaries.firstOrNull()?.name ?: "Home Yard") };var queen by rememberSaveable{mutableStateOf("Laying")};var strength by rememberSaveable{mutableIntStateOf(5)};Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Rounded.ArrowBack,"Back")};Text("Add Hive",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold)};OutlinedTextField(number,{number=it},Modifier.fillMaxWidth(),label={Text("Hive number")},singleLine=true);OutlinedTextField(apiary,{ if (initialApiary == null) apiary=it },Modifier.fillMaxWidth(),label={Text("Apiary / Yard")},singleLine=true,readOnly=initialApiary != null);Text("Queen status",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){for(status in listOf("Laying","Spotted","Unspotted","Queenless","Virgin")){FilterChip(queen==status,{queen=status},{Text(status)})}};Counter("Starting strength",strength,0..10){strength=it};Button({onCreate(number,apiary,queen,strength)},Modifier.fillMaxWidth().height(60.dp),enabled=number.isNotBlank(),shape=RoundedCornerShape(24.dp)){Text("CREATE HIVE",fontWeight=FontWeight.ExtraBold)}}}

@Composable
private fun AddApiaryScreen(
    locationController: LocationController,
    existingApiary: Apiary?,
    onBack: () -> Unit,
    onSave: (Long?, String, String, Double?, Double?, String, String) -> Unit
) {
    var name by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.name.orEmpty()) }
    var notes by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.notes.orEmpty()) }
    var forage by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.forageNotes.orEmpty()) }
    var water by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.waterNotes.orEmpty()) }
    var lat by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.latitude) }
    var lon by rememberSaveable(existingApiary?.id) { mutableStateOf(existingApiary?.longitude) }
    var gpsStatus by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    BackHandler { onBack() }
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
            TextButton(onClick = { lat = null; lon = null; gpsStatus = "GPS location removed" }) { Text("REMOVE GPS LOCATION") }
        }
        if(gpsStatus.isNotBlank()) Text(gpsStatus,color=if(gpsStatus.startsWith("Could") || gpsStatus.startsWith("Location permission denied"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)
        Button({onSave(existingApiary?.id, name.trim(),notes.trim(),lat,lon,forage.trim(),water.trim())},Modifier.fillMaxWidth().height(60.dp),enabled=name.isNotBlank(),shape=RoundedCornerShape(24.dp)){Text(if (existingApiary == null) "SAVE APIARY" else "SAVE CHANGES",fontWeight=FontWeight.ExtraBold)}
    }
}

@Composable
private fun ScanScreen(
    available: Boolean,
    enabled: Boolean,
    hives: List<Hive>,
    scanning: Boolean,
    onBack: () -> Unit,
    onCancelScan: () -> Unit,
    onScan: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) onCancelScan()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onCancelScan()
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        if (scanning) {
            val pulse = rememberInfiniteTransition(label = "scanPulse")
            val ringScale by pulse.animateFloat(1f, 1.9f, infiniteRepeatable(tween(1100)), label = "ringScale")
            val ringAlpha by pulse.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1100)), label = "ringAlpha")
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
                Icon(Icons.Rounded.Nfc,null,Modifier.size(88.dp),tint=MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            Text("Scanning…",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold)
            Text("Hold the back of your phone near the tag.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Icon(Icons.Rounded.Nfc,null,Modifier.size(88.dp),tint=MaterialTheme.colorScheme.primary)
            Text("Scan a Hive",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold)
            Text(
                when {
                    !available -> "NFC is not available on this phone."
                    !enabled -> "NFC is turned off. Turn it on to scan hive tags."
                    else -> "Hold the back of your phone near the tag."
                },
                color=MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        if (scanning) {
            OutlinedButton(onClick=onCancelScan,Modifier.fillMaxWidth().height(52.dp)){Text("CANCEL SCAN",fontWeight=FontWeight.ExtraBold)}
        } else {
            Button(onClick=onScan,Modifier.fillMaxWidth().height(62.dp),enabled=available && enabled){Text("START NFC SCAN",fontWeight=FontWeight.ExtraBold)}
            if (available && !enabled) {
                OutlinedButton(onClick={runCatching{context.startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))}},Modifier.fillMaxWidth().height(52.dp)){Text("OPEN NFC SETTINGS")}
            }
        }
        OutlinedButton(onBack){Text("Back")}
    }
}

@Composable
private fun TagManagementScreen(
    padding: PaddingValues,
    hives: List<Hive>,
    nfc: NfcController,
    activity: ComponentActivity,
    pendingUid: String?,
    onPendingUidConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenHive: (Long) -> Unit,
    onAssignTag: (Long, String, Boolean, (Boolean, String?) -> Unit) -> Unit,
    onClearTag: (Long) -> Unit
) {
    val context = LocalContext.current
    var status by rememberSaveable { mutableStateOf("") }
    var assigningHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reassignPrompt by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }
    var launchWriteHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var launchWriteUid by rememberSaveable { mutableStateOf<String?>(null) }
    var launchWriteOverwrite by rememberSaveable { mutableStateOf(false) }
    var launchWriteStatusHandler by remember { mutableStateOf<((String) -> Unit)?>(null) }
    BackHandler { onBack() }
    val assigned = hives.count { !it.tagUid.isNullOrBlank() }

    fun writeLaunchDataAfterAssignment(
        hiveId: Long,
        uid: String,
        allowOverwriteOtherHive: Boolean = false,
        updateStatus: (String) -> Unit
    ) {
        // Do not immediately re-arm the NFC reader here: the user's first tap
        // may still be against the phone. Show an explicit prompt first, then
        // start a fresh write scan only after they confirm they are ready.
        updateStatus("Tag $uid assigned. One more tap is needed to write BeeKeep launch data.")
        launchWriteHiveId = hiveId
        launchWriteUid = uid
        launchWriteOverwrite = allowOverwriteOtherHive
        launchWriteStatusHandler = updateStatus
    }

    if (launchWriteHiveId != null && launchWriteUid != null) {
        val targetHive = hives.firstOrNull { it.id == launchWriteHiveId }
        val targetNumber = targetHive?.number ?: launchWriteHiveId.toString()
        AlertDialog(
            onDismissRequest = {
                nfc.stop(activity)
                launchWriteStatusHandler?.invoke("Tag ${launchWriteUid} is assigned, but launch data was not written. Open the hive and use WRITE TAG to retry.")
                launchWriteHiveId = null
                launchWriteUid = null
                launchWriteStatusHandler = null
            },
            title = { Text("One more tap needed", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hive $targetNumber has been assigned to tag $launchWriteUid.")
                    Text("The first tap saved the tag's UID only. To open this hive when BeeKeep is closed, its launch data must also be written.")
                    Text("Remove the tag from the phone if it is still touching it. Then choose CONTINUE and tap the SAME physical tag again. Hold it still until BeeKeep confirms the write.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    val hiveId = launchWriteHiveId
                    val uid = launchWriteUid
                    val allowOverwrite = launchWriteOverwrite
                    val updateStatus = launchWriteStatusHandler
                    launchWriteHiveId = null
                    launchWriteUid = null
                    launchWriteStatusHandler = null
                    if (hiveId != null && uid != null) {
                        nfc.startWrite(
                            activity = activity,
                            text = BeeKeepNfcPayload.forHive(hiveId),
                            allowOverwriteOtherHive = allowOverwrite,
                            expectedUid = uid,
                            onResult = { result ->
                                when (result) {
                                    is NfcResult.Written -> updateStatus?.invoke("Tag assigned and written. Tapping it with BeeKeep closed can now open Hive $targetNumber.")
                                    is NfcResult.Error -> updateStatus?.invoke("Tag $uid is assigned, but launch data was not written: ${result.message} Open this hive and use WRITE TAG to retry.")
                                    is NfcResult.Read -> Unit
                                }
                            }
                        )
                    }
                }) { Text("CONTINUE TO TAG WRITE") }
            },
            dismissButton = {
                TextButton(onClick = {
                    launchWriteStatusHandler?.invoke("Tag $launchWriteUid is assigned, but launch data was not written. Open the hive and use WRITE TAG to retry.")
                    launchWriteHiveId = null
                    launchWriteUid = null
                    launchWriteStatusHandler = null
                }) { Text("NOT NOW") }
            }
        )
    }

    reassignPrompt?.let { (targetHiveId, tagUid) ->
        AlertDialog(
            onDismissRequest = { reassignPrompt = null },
            title = { Text("Tag already assigned", fontWeight = FontWeight.ExtraBold) },
            text = { Text("This NFC tag is assigned to another hive. Reassigning will remove it from that hive and attach it to the selected one.") },
            confirmButton = {
                TextButton(onClick = {
                    onAssignTag(targetHiveId, tagUid, true) { success, error ->
                        if (success) {
                            if (pendingUid != null) onPendingUidConsumed()
                            writeLaunchDataAfterAssignment(targetHiveId, tagUid, allowOverwriteOtherHive = true) { status = it }
                        } else {
                            status = error ?: "Could not reassign tag."
                        }
                    }
                    reassignPrompt = null
                }) { Text("REASSIGN", fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = { TextButton(onClick = { reassignPrompt = null }) { Text("CANCEL") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("NFC Tags", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("$assigned of ${hives.size} hives tagged", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How BeeKeep tags work", fontWeight = FontWeight.Bold)
                Text("Assign links the tag UID to a hive. BeeKeep then asks you to tap the same tag again to write its launch data. That small NDEF payload lets Android open BeeKeep directly when the app is closed; hive history stays in BeeKeep, not on the tag.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Tags are reusable: when a colony dies its tag is released and can be assigned to another hive. Scanning always opens the colony the tag is currently assigned to.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                Text("Use durable, weather-resistant NFC tags on the hive lid or another protected surface.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (pendingUid != null) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tag ready to assign", fontWeight = FontWeight.ExtraBold)
                    Text("Tag $pendingUid is not linked to a colony. Tap ASSIGN on the hive it belongs to.", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = onPendingUidConsumed) { Text("CANCEL PENDING ASSIGNMENT") }
                }
            }
        }

        Button(
            onClick = {
                assigningHiveId = null
                nfc.startRead(activity) { result ->
                    when (result) {
                        is NfcResult.Read -> {
                            val hiveByUid = hives.firstOrNull { it.tagUid.equals(result.uid, ignoreCase = true) }
                            val hiveByPayload = BeeKeepNfcPayload.hiveId(result.text)?.let { id -> hives.firstOrNull { it.id == id } }
                            status = when {
                                hiveByUid != null -> "Hive ${hiveByUid.number} • tag verified"
                                hiveByPayload != null -> "Hive ${hiveByPayload.number} • tag payload recognized"
                                else -> "Unassigned tag • ${result.uid}"
                            }
                            hiveByUid?.let { onOpenHive(it.id) } ?: hiveByPayload?.let { onOpenHive(it.id) }
                        }
                        is NfcResult.Error -> status = result.message
                        is NfcResult.Written -> Unit
                    }
                }
            },
            enabled = nfc.isAvailable() && nfc.isEnabled(),
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Icon(Icons.Rounded.Nfc, null); Spacer(Modifier.width(7.dp)); Text("SCAN & OPEN HIVE", fontWeight = FontWeight.ExtraBold) }

        if (!nfc.isAvailable()) {
            Text("This phone does not support NFC.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else if (!nfc.isEnabled()) {
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) } }, modifier = Modifier.fillMaxWidth()) { Text("TURN ON NFC") }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        for (hive in hives.sortedBy { it.number }) {
            var verifyText by rememberSaveable(hive.id, hive.tagUid) { mutableStateOf("") }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hive ${hive.number}", fontWeight = FontWeight.Bold)
                            Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (hive.tagUid.isNullOrBlank()) "UNASSIGNED" else "TAGGED", fontWeight = FontWeight.Bold, color = if (hive.tagUid.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Text(hive.tagUid ?: "No NFC tag assigned", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = { onOpenHive(hive.id) }, modifier = Modifier.weight(1f)) { Text("OPEN") }
                        OutlinedButton(
                            onClick = {
                                if (pendingUid != null) {
                                    onAssignTag(hive.id, pendingUid, false) { success, error ->
                                        if (success) {
                                            onPendingUidConsumed()
                                            writeLaunchDataAfterAssignment(hive.id, pendingUid) { verifyText = it }
                                        } else if (error?.contains("already assigned") == true) {
                                            reassignPrompt = hive.id to pendingUid
                                        } else {
                                            verifyText = error ?: "Could not assign tag."
                                        }
                                    }
                                    return@OutlinedButton
                                }
                                assigningHiveId = hive.id
                                nfc.startRead(activity) { result ->
                                    when (result) {
                                        is NfcResult.Read -> {
                                            val payloadHiveId = BeeKeepNfcPayload.hiveId(result.text)
                                            if (payloadHiveId != null && payloadHiveId != hive.id) {
                                                verifyText = "Tag payload belongs to another hive. Use REPLACE/WRITE on the destination hive."
                                                assigningHiveId = null
                                            } else {
                                                onAssignTag(hive.id, result.uid, false) { success, error ->
                                                    if (success) {
                                                        writeLaunchDataAfterAssignment(hive.id, result.uid) { verifyText = it }
                                                    } else if (error?.contains("already assigned") == true) {
                                                        reassignPrompt = hive.id to result.uid
                                                    } else {
                                                        verifyText = error ?: "Could not assign tag."
                                                    }
                                                    assigningHiveId = null
                                                }
                                            }
                                        }
                                        is NfcResult.Error -> { verifyText = result.message; assigningHiveId = null }
                                        is NfcResult.Written -> Unit
                                    }
                                }
                            },
                            enabled = nfc.isAvailable() && nfc.isEnabled(),
                            modifier = Modifier.weight(1f)
                        ) { Text(if (hive.tagUid.isNullOrBlank()) "ASSIGN" else "REPLACE") }
                    }
                    if (hive.tagUid != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(onClick = {
                                nfc.startRead(activity) { result ->
                                    when (result) {
                                        is NfcResult.Read -> {
                                            val payloadId = BeeKeepNfcPayload.hiveId(result.text)
                                            verifyText = when {
                                                !result.uid.equals(hive.tagUid, true) -> "⚠ Tag ${result.uid} does not match Hive ${hive.number}"
                                                payloadId == hive.id -> "✓ Tag matches Hive ${hive.number} and has BeeKeep launch data"
                                                payloadId == null -> "UID matches, but this tag has no BeeKeep launch data. Use WRITE TAG from hive details."
                                                else -> "⚠ Tag payload belongs to another hive"
                                            }
                                        }
                                        is NfcResult.Error -> verifyText = result.message
                                        is NfcResult.Written -> Unit
                                    }
                                }
                            }, modifier = Modifier.weight(1f)) { Text("VERIFY") }
                            TextButton(onClick = { onClearTag(hive.id); verifyText = "Tag removed" }, modifier = Modifier.weight(1f)) { Text("REMOVE") }
                        }
                    }
                    verifyText.takeIf { it.isNotBlank() }?.let { Text(it, color = if (it.startsWith("⚠")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (assigningHiveId == hive.id) Text("Hold the physical tag to the back of the phone…", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MoreScreen(
    padding: PaddingValues,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onTagManager: () -> Unit,
    onColonyHistory: () -> Unit,
    deadCount: Int,
    activity: ComponentActivity,
    cloud: SupabaseGateway
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var authMessage by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)

        Card(shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Yellow + black field mode", fontWeight = FontWeight.Bold)
                    Text("High-contrast controls for bright outdoor conditions", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
            }
        }

        Card(onClick = onTagManager, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("NFC tag management", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("Scan, verify, assign, replace or remove hive tags", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Nfc, "Open NFC tag management", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }

        Card(onClick = onColonyHistory, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Colony history", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(if (deadCount == 0) "Dead colonies are archived here with their full history" else "$deadCount dead ${if (deadCount == 1) "colony" else "colonies"} preserved", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.History, "Open colony history", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("BeeKeep Cloud", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !account.configured -> "Add Supabase credentials to enable cloud sync"
                                account.signedIn -> "Signed in as ${account.email}"
                                else -> "Local-only until you sign in"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(if (account.syncing) "SYNCING" else if (account.signedIn) "ONLINE" else "LOCAL", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                if (account.configured && !account.signedIn) {
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    authMessage = ""
                                    when (val result = cloud.signIn(email, password)) {
                                        CloudResult.Success -> { authMessage = "Signed in. Syncing…"; CloudSyncScheduler.runNow(context) }
                                        CloudResult.NotConfigured -> authMessage = "Cloud is not configured."
                                        CloudResult.NotSignedIn -> authMessage = "Please sign in."
                                        is CloudResult.Failure -> authMessage = result.message
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("SIGN IN") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    authMessage = ""
                                    when (val result = cloud.signUp(email, password)) {
                                        CloudResult.Success -> { authMessage = "Account created. Check your email if verification is required."; CloudSyncScheduler.runNow(context) }
                                        CloudResult.NotConfigured -> authMessage = "Cloud is not configured."
                                        CloudResult.NotSignedIn -> authMessage = "Account created but not signed in yet."
                                        is CloudResult.Failure -> authMessage = result.message
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("CREATE") }
                    }
                } else if (account.signedIn) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    when (val result = cloud.syncNow()) {
                                        CloudResult.Success -> authMessage = "Synced just now."
                                        CloudResult.NotSignedIn -> authMessage = "Sign in required."
                                        CloudResult.NotConfigured -> authMessage = "Cloud is not configured."
                                        is CloudResult.Failure -> authMessage = result.message
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !account.syncing
                        ) { Text("SYNC NOW") }
                        OutlinedButton(
                            onClick = { scope.launch { cloud.signOut(); authMessage = "Signed out. Your phone data is still available offline." } },
                            modifier = Modifier.weight(1f)
                        ) { Text("SIGN OUT") }
                    }
                }

                if (authMessage.isNotBlank()) Text(authMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                account.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (account.lastSyncAt != null) {
                    Text("Last sync: ${account.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Never"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Native Android • Kotlin + Compose", fontWeight = FontWeight.Bold)
                Text("Room offline database • NFC • CameraX • GPS • Voice • WorkManager • cloud sync outbox", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeedDialog(onDismiss: () -> Unit, onSave: (String, String, Double, String, String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("Syrup") }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var amount by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("gal") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    SimpleLogDialog("Log Feeding", onDismiss, {
        val value = amount.trim().toDoubleOrNull()
        when {
            type.isBlank() -> error = "Enter a feed type."
            value == null || !value.isFinite() || value < 0 -> error = "Enter a valid non-negative amount."
            unit.isBlank() -> error = "Enter a unit."
            else -> onSave(type.trim(), ratio.trim(), value, unit.trim(), notes.trim())
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(type, { type = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Feed type") }, singleLine = true)
            OutlinedTextField(ratio, { ratio = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Ratio (optional)") }, singleLine = true)
            OutlinedTextField(amount, { amount = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(unit, { unit = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Unit") }, singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable
private fun TreatmentDialog(onDismiss: () -> Unit, onSave: (String, String, Int, Int, String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("Mite treatment") }
    var product by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf("42") }
    var withdrawal by rememberSaveable { mutableStateOf("0") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    SimpleLogDialog("Log Treatment", onDismiss, {
        val removal = duration.trim().toIntOrNull()
        val withdraw = withdrawal.trim().toIntOrNull()
        when {
            type.isBlank() -> error = "Enter a treatment type."
            product.isBlank() -> error = "Enter a product."
            removal == null || removal < 0 -> error = "Removal days must be a non-negative whole number."
            withdraw == null || withdraw < 0 -> error = "Withdrawal days must be a non-negative whole number."
            else -> onSave(type.trim(), product.trim(), removal, withdraw, notes.trim())
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(type, { type = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Treatment type") }, singleLine = true)
            OutlinedTextField(product, { product = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Product") }, singleLine = true)
            OutlinedTextField(duration, { duration = it.filter(Char::isDigit); error = "" }, Modifier.fillMaxWidth(), label = { Text("Removal in days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(withdrawal, { withdrawal = it.filter(Char::isDigit); error = "" }, Modifier.fillMaxWidth(), label = { Text("Extra withdrawal days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable
private fun HarvestDialog(onDismiss: () -> Unit, onSave: (Int, Double, Double, String, Double, Double, String) -> Unit) {
    var supers by rememberSaveable { mutableStateOf("0") }
    var wet by rememberSaveable { mutableStateOf("0") }
    var dry by rememberSaveable { mutableStateOf("0") }
    var unit by rememberSaveable { mutableStateOf("lb") }
    var wax by rememberSaveable { mutableStateOf("0") }
    var prop by rememberSaveable { mutableStateOf("0") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    SimpleLogDialog("Log Harvest", onDismiss, {
        val s = supers.trim().toIntOrNull()
        val w = wet.trim().toDoubleOrNull()
        val d = dry.trim().toDoubleOrNull()
        val wx = wax.trim().toDoubleOrNull()
        val pr = prop.trim().toDoubleOrNull()
        when {
            s == null || s < 0 -> error = "Supers pulled must be a non-negative whole number."
            w == null || !w.isFinite() || w < 0 -> error = "Enter a valid wet honey weight."
            d == null || !d.isFinite() || d < 0 -> error = "Enter a valid dry honey weight."
            unit.isBlank() -> error = "Enter a weight unit."
            wx == null || !wx.isFinite() || wx < 0 -> error = "Enter a valid wax weight."
            pr == null || !pr.isFinite() || pr < 0 -> error = "Enter a valid propolis weight."
            else -> onSave(s, w, d, unit.trim(), wx, pr, notes.trim())
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(supers, { supers = it.filter(Char::isDigit); error = "" }, Modifier.fillMaxWidth(), label = { Text("Supers pulled") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(wet, { wet = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Wet honey") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(dry, { dry = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Dry honey") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(unit, { unit = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Weight unit") }, singleLine = true)
            OutlinedTextField(wax, { wax = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Wax") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(prop, { prop = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Propolis") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable private fun SimpleLogDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { content() },
        confirmButton = { TextButton(onClick = onConfirm) { Text("SAVE", fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
