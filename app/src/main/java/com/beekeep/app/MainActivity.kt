package com.beekeep.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import com.beekeep.app.data.IdGenerator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.beekeep.app.data.ActivityEvent
import com.beekeep.app.data.Apiary
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import com.beekeep.app.data.LocalHiveRepository
import com.beekeep.app.data.Task
import com.beekeep.app.analytics.HealthAnalytics
import com.beekeep.app.analytics.HealthPoint
import com.beekeep.app.data.Treatment
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.CloudSyncScheduler
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.location.LocationController
import com.beekeep.app.media.PhotoStore
import com.beekeep.app.nfc.NfcController
import com.beekeep.app.nfc.BeeKeepNfcPayload
import com.beekeep.app.nfc.NfcResult
import com.beekeep.app.notifications.ReminderScheduler
import com.beekeep.app.ui.camera.CameraCaptureView
import com.beekeep.app.ui.calendar.CalendarScreen
import com.beekeep.app.ui.analytics.AdvancedAnalyticsCard
import com.beekeep.app.ui.theme.BeeKeepTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "beekeep_prefs"

class MainActivity : ComponentActivity() {
    private val nfc = NfcController()
    internal val pendingNfcResult = MutableStateFlow<NfcResult.Read?>(null)
    private lateinit var photoStore: PhotoStore
    private lateinit var locationController: LocationController
    private lateinit var cloudGateway: SupabaseGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc.attach(this)
        handleNfcIntent(intent)
        photoStore = PhotoStore(this)
        locationController = LocationController(this)
        ReminderScheduler.ensureChannel(this)
        val repository = LocalHiveRepository(applicationContext)
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
                    controller.isAppearanceLightNavigationBars = !darkMode
                }
                val incomingNfc by pendingNfcResult.collectAsStateWithLifecycle()
                BeeKeepApp(vm, nfc, this, photoStore, locationController, cloud, darkMode, incomingNfc) { value ->
                    darkMode = value
                    prefs.edit().putBoolean("dark_mode", value).apply()
                }
            }
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            nfc.readIntent(intent)?.let { pendingNfcResult.value = it }
        }
    }

    override fun onResume() {
        super.onResume()
        nfc.attach(this)
    }

    override fun onPause() {
        nfc.stop(this)
        super.onPause()
    }

    override fun onDestroy() {
        nfc.stop(this)
        if (::cloudGateway.isInitialized) cloudGateway.stopRealtime()
        super.onDestroy()
    }
}

enum class Screen { HOME, APIARIES, APIARY_HIVES, SCAN, CALENDAR, MORE, INSIGHTS, TAG_MANAGER, COLONY_HISTORY }

enum class HiveLogType { FEED, TREAT, HARVEST }

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
    onDarkModeChange: (Boolean) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedHiveOpen by rememberSaveable { mutableStateOf(false) }
    var selectedApiaryName by rememberSaveable { mutableStateOf<String?>(null) }
    var inspecting by rememberSaveable { mutableStateOf(false) }
    var addHive by rememberSaveable { mutableStateOf(false) }
    var logType by rememberSaveable { mutableStateOf<HiveLogType?>(null) }
    var addApiary by rememberSaveable { mutableStateOf(false) }
    var unassignedTagUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTagUid by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = androidx.compose.material3.SnackbarHostState()

    val hives by vm.hives.collectAsStateWithLifecycle()
    val deadHives by vm.deadHives.collectAsStateWithLifecycle()
    val apiaries by vm.apiaries.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val inspections by vm.inspections.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val feedings by vm.feedings.collectAsStateWithLifecycle()
    val treatments by vm.treatments.collectAsStateWithLifecycle()
    val harvests by vm.harvests.collectAsStateWithLifecycle()
    val allInspections by vm.allInspections.collectAsStateWithLifecycle()
    val allFeedings by vm.allFeedings.collectAsStateWithLifecycle()
    val allTreatments by vm.allTreatments.collectAsStateWithLifecycle()
    val allHarvests by vm.allHarvests.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(ready, incomingNfc?.uid) {
        val result = incomingNfc ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val payloadHiveId = BeeKeepNfcPayload.hiveId(result.text)
        val resolvedId = vm.findHiveByTag(result.uid)?.id
            ?: payloadHiveId?.takeIf { id -> hives.any { it.id == id } }
        if (resolvedId != null) {
            vm.openHive(resolvedId)
            selectedHiveOpen = true
            screen = Screen.HOME
            scope.launch { snackbarHostState.showSnackbar("Hive tag ${result.uid} recognized") }
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
    if (addApiary) {
        AddApiaryScreen(locationController, { addApiary = false }) { name, notes, lat, lon, forage, water ->
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
            tasks = tasks,
            onBack = { inspecting = false },
            onSave = { inspection ->
                val saved = vm.saveInspection(inspection)
                if (saved) {
                    inspecting = false
                    scope.launch { snackbarHostState.showSnackbar("Inspection saved") }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("Could not save inspection. Your field screen is still open; try again.") }
                }
            },
            onScheduleRecommendation = { recommendation ->
                val dueAt = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(recommendation.daysFromNow)
                vm.createScheduledTask(recommendation.title, hiveForInspection.id, dueAt, true) { success, error ->
                    scope.launch { snackbarHostState.showSnackbar(if (success) "Added to calendar" else (error ?: "Could not add task")) }
                }
            }
        )
        return
    }
    if (logType != null && selected != null) {
        when (logType) {
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
            null -> Unit
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
            tasks = tasks,
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
            onScheduleRecommendation = { recommendation ->
                val dueAt = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(recommendation.daysFromNow)
                vm.createScheduledTask(recommendation.title, hiveForDetail.id, dueAt, true) { success, error ->
                    scope.launch { snackbarHostState.showSnackbar(if (success) "Added to calendar" else (error ?: "Could not add task")) }
                }
            },
            onEditQueen = { status, mark, origin, age, temperament -> vm.updateQueenProfile(status, mark, origin, age, temperament) },
            onMarkDead = {
                vm.markHiveDead(hiveForDetail.id) { success, error ->
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Hive ${hiveForDetail.number} marked dead. History preserved.") }
                }
            },
            onRestore = {
                vm.restoreHive(hiveForDetail.id) { success, error ->
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
            onBack = { screen = Screen.HOME },
            onScan = {
                nfc.startRead(activity) { result ->
                    when (result) {
                        is NfcResult.Read -> {
                            scope.launch {
                                val payloadId = BeeKeepNfcPayload.hiveId(result.text)
                                val resolvedId = vm.findHiveByTag(result.uid)?.id
                                    ?: payloadId?.takeIf { id -> hives.any { it.id == id } }
                                if (resolvedId != null) {
                                    vm.openHive(resolvedId)
                                    selectedHiveOpen = true
                                    screen = Screen.HOME
                                    snackbarHostState.showSnackbar("Hive tag recognized")
                                } else {
                                    unassignedTagUid = result.uid
                                }
                            }
                        }
                        is NfcResult.Error -> scope.launch { snackbarHostState.showSnackbar(result.message) }
                        is NfcResult.Written -> Unit
                    }
                }
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
    if (screen == Screen.INSIGHTS) {
        InsightsScreen(
            hives = hives,
            inspections = allInspections,
            feedings = allFeedings,
            treatments = allTreatments,
            harvests = allHarvests,
            tasks = tasks,
            onBack = { screen = Screen.MORE },
            onOpenHive = { vm.openHive(it); selectedHiveOpen = true }
        )
        return
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(tonalElevation = 3.dp) {
                NavItem("Home", Icons.Rounded.Home, screen == Screen.HOME) { screen = Screen.HOME }
                NavItem("Apiaries", Icons.Rounded.Yard, screen == Screen.APIARIES || screen == Screen.APIARY_HIVES) { selectedApiaryName = null; screen = Screen.APIARIES }
                NavItem("Scan", Icons.Rounded.Nfc, screen == Screen.SCAN) { screen = Screen.SCAN }
                NavItem("Calendar", Icons.Rounded.CalendarMonth, screen == Screen.CALENDAR) { screen = Screen.CALENDAR }
                NavItem("More", Icons.Rounded.Settings, screen == Screen.MORE) { screen = Screen.MORE }
            }
        }
    ) { padding ->
        when (screen) {
            Screen.HOME -> HomeScreen(hives, tasks, padding, onScan = { screen = Screen.SCAN })
            Screen.APIARIES -> ApiariesScreen(apiaries, hives, padding, onAddApiary = { addApiary = true }, onOpenApiary = { selectedApiaryName = it; screen = Screen.APIARY_HIVES })
            Screen.CALENDAR -> CalendarScreen(
                tasks = tasks,
                hives = hives,
                padding = padding,
                onAddTask = { title, hiveId, dueAt, reminder, repeatEveryDays, onResult ->
                    vm.createScheduledTask(title, hiveId, dueAt, reminder, repeatEveryDays, onResult)
                },
                onComplete = { vm.completeTask(it) },
                onBuildSeasonalPlan = { ids, horizon -> vm.buildSeasonalPlan(ids, horizon) }
            )
            Screen.MORE -> MoreScreen(darkMode, onDarkModeChange, onInsights = { screen = Screen.INSIGHTS }, onTagManager = { screen = Screen.TAG_MANAGER }, onColonyHistory = { screen = Screen.COLONY_HISTORY }, deadCount = deadHives.size, activity, cloud)
            Screen.TAG_MANAGER -> TagManagementScreen(
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
            Screen.SCAN, Screen.INSIGHTS, Screen.APIARY_HIVES -> Unit
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, label) }, label = { Text(label, maxLines = 1) })
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
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
private fun HomeScreen(
    hives: List<Hive>,
    tasks: List<Task>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onScan: () -> Unit
) {
    val now = System.currentTimeMillis()
    val due = tasks.count { !it.completed && it.dueAt <= now + 24 * 60 * 60 * 1000 }
    val flagged = hives.count { it.mitePercent >= 3.0 || it.queenStatus == "Queenless" }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Good field day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("BeeKeep", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
            Text("Your operation at a glance", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Hives", hives.size.toString(), Modifier.weight(1f))
                MetricCard("Due", due.toString(), Modifier.weight(1f))
                MetricCard("Watch", flagged.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Fast field scan", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Tap a hive tag and jump straight to its record.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .88f))
                    }
                    Button(onClick = onScan, shape = RoundedCornerShape(15.dp), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Rounded.Nfc, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("SCAN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
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
    onOpenApiary: (String) -> Unit
) {
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
                    Card(onClick = { onOpenApiary(apiary.name) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
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
                            Icon(Icons.Rounded.ChevronRight, "Open apiary", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
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
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), border = if (attention) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .35f)) else null) {
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
                    Card(onClick = { onOpenHive(hive.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
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
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun InsightsScreen(
    hives: List<Hive>,
    inspections: List<Inspection>,
    feedings: List<com.beekeep.app.data.Feeding>,
    treatments: List<Treatment>,
    harvests: List<Harvest>,
    tasks: List<Task>,
    onBack: () -> Unit,
    onOpenHive: (Long) -> Unit
) {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val since = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    val now = System.currentTimeMillis()
    val recentCutoff = now - 14 * 24 * 60 * 60 * 1000
    val yearInspections = remember(inspections, since) { inspections.filter { it.createdAt >= since } }
    val yearHarvests = remember(harvests, since) { harvests.filter { it.createdAt >= since } }
    val yearFeedings = remember(feedings, since) { feedings.filter { it.createdAt >= since } }
    val activeTreatments = remember(treatments, now) { treatments.count { it.removalAt == null || it.removalAt > now } }
    val recentlyInspectedIds = remember(inspections, recentCutoff) {
        inspections.asSequence().filter { it.createdAt >= recentCutoff }.map { it.hiveId }.toSet()
    }
    val recentlyInspected = hives.count { it.id in recentlyInspectedIds }
    val averageStrength = remember(yearInspections) { yearInspections.map { it.strength }.takeIf { it.isNotEmpty() }?.average() }
    val averageMite = remember(yearInspections) { yearInspections.map { it.mitePercent }.takeIf { it.isNotEmpty() }?.average() }
    val totalHoney = yearHarvests.sumOf { if (it.dryHoneyWeight > 0) it.dryHoneyWeight else it.wetHoneyWeight }
    val honeyUnit = yearHarvests.firstOrNull()?.weightUnit ?: "lb"
    val attention = hives.filter { it.mitePercent >= 3.0 || it.queenStatus == "Queenless" }.sortedBy { it.strength }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Insights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Season ${year}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.Assessment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Inspections", yearInspections.size.toString(), Modifier.weight(1f), "$recentlyInspected hives in 14 days")
                    MetricCard("Avg strength", averageStrength?.let { String.format(Locale.US, "%.1f/10", it) } ?: "—", Modifier.weight(1f), "This season")
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Avg mites", averageMite?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—", Modifier.weight(1f), "Inspection average")
                    MetricCard("Honey", if (totalHoney > 0) String.format(Locale.US, "%.1f %s", totalHoney, honeyUnit) else "—", Modifier.weight(1f), "Recorded harvests")
                }
            }
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Season activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("${yearFeedings.size} feeding logs • $activeTreatments active treatments • ${tasks.count { !it.completed }} open tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { ApiaryHealthGraphCard(hives = hives, inspections = inspections) }
            item { AdvancedAnalyticsCard(hives = hives, inspections = inspections, harvests = yearHarvests) }
            item { Text("Hives to watch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold) }
            if (attention.isEmpty()) {
                item { EmptyState("All clear", "No hives currently meet BeeKeep's attention rules.", null, null) }
            } else {
                items(attention.take(6), key = { it.id }) { hive -> HiveRow(hive, "Review health") { onOpenHive(hive.id) } }
            }
        }
    }
}

@Composable
private fun ApiaryHealthGraphCard(
    hives: List<Hive>,
    inspections: List<Inspection>
) {
    var scopeKey by rememberSaveable { mutableStateOf("all") }
    var rangeKey by rememberSaveable { mutableStateOf(HealthAnalytics.Range.NINETY.name) }
    var showScopePicker by rememberSaveable { mutableStateOf(false) }
    var scopeQuery by rememberSaveable { mutableStateOf("") }

    val range = HealthAnalytics.Range.valueOf(rangeKey)
    val analyticsKey = arrayOf(hives, inspections, scopeKey, rangeKey)
    val now = remember(analyticsKey) { System.currentTimeMillis() }
    val apiaries = hives.map { it.apiary }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase() }
    val selectedHiveId = scopeKey.removePrefix("hive:").toLongOrNull().takeIf { scopeKey.startsWith("hive:") }
    val selectedHive = selectedHiveId?.let { id -> hives.firstOrNull { it.id == id } }
    val selectedApiaryName = scopeKey.removePrefix("apiary:").takeIf { scopeKey.startsWith("apiary:") }
    val scopeLabel = selectedHive?.let { "Hive ${it.number} • ${it.apiary}" }
        ?: selectedApiaryName
        ?: "All Apiaries"

    val scopeHiveIds = when {
        selectedHive != null -> setOf(selectedHive.id)
        selectedApiaryName != null -> hives.filter { it.apiary.equals(selectedApiaryName, ignoreCase = true) }.map { it.id }.toSet()
        else -> hives.map { it.id }.toSet()
    }
    val points = when {
        selectedHive != null -> HealthAnalytics.hivePoints(selectedHive, inspections, range, now)
        selectedApiaryName != null -> HealthAnalytics.apiaryPoints(selectedApiaryName, hives, inspections, range, now)
        else -> HealthAnalytics.allApiaryPoints(hives, inspections, range, now)
    }
    val summary = HealthAnalytics.summary(points)
    val latestAverage = HealthAnalytics.latestAverage(scopeHiveIds, inspections, range, now)
    val observedHives = HealthAnalytics.observedHiveCount(scopeHiveIds, inspections, range, now)
    val totalScopedHives = scopeHiveIds.size
    val query = scopeQuery.trim()
    val filteredApiaries = apiaries.filter { it.contains(query, ignoreCase = true) }
    val filteredHives = hives.filter {
        it.number.contains(query, ignoreCase = true) || it.apiary.contains(query, ignoreCase = true)
    }

    if (showScopePicker) {
        AlertDialog(
            onDismissRequest = { showScopePicker = false },
            title = { Text("Health scope") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = scopeQuery,
                        onValueChange = { scopeQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Find apiary or hive") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            TextButton(
                                onClick = { scopeKey = "all"; scopeQuery = ""; showScopePicker = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("All Apiaries", modifier = Modifier.weight(1f)) }
                        }
                        if (filteredApiaries.isNotEmpty()) {
                            item {
                                Text(
                                    "Apiaries",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )
                            }
                            items(filteredApiaries, key = { "apiary:$it" }) { name ->
                                TextButton(
                                    onClick = { scopeKey = "apiary:$name"; scopeQuery = ""; showScopePicker = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(name, modifier = Modifier.weight(1f)) }
                            }
                        }
                        if (filteredHives.isNotEmpty()) {
                            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                            item {
                                Text(
                                    "Hives",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(filteredHives, key = { it.id }) { hive ->
                                TextButton(
                                    onClick = { scopeKey = "hive:${hive.id}"; scopeQuery = ""; showScopePicker = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Hive ${hive.number} • ${hive.apiary}", modifier = Modifier.weight(1f)) }
                            }
                        }
                        if (filteredApiaries.isEmpty() && filteredHives.isEmpty()) {
                            item {
                                Text(
                                    "Nothing matches ‘$query’.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 18.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showScopePicker = false }) { Text("Done") } }
        )
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Health trends", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Historical inspection health, averaged fairly across hives within each time bucket.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = { scopeQuery = ""; showScopePicker = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(if (selectedHive != null) Icons.Rounded.Search else Icons.Rounded.Yard, null)
                Spacer(Modifier.width(7.dp))
                Text(scopeLabel, maxLines = 1)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HealthAnalytics.Range.values().forEach { candidate ->
                    FilterChip(
                        selected = range == candidate,
                        onClick = { rangeKey = candidate.name },
                        label = { Text(candidate.label) }
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    "Latest avg",
                    latestAverage.average?.let { "${HealthAnalytics.roundedScore(it)} / 100" } ?: "—",
                    Modifier.weight(1f),
                    if (selectedHive != null) "Latest inspection" else "Latest in range"
                )
                MetricCard(
                    "Trend",
                    summary.delta?.let { String.format(Locale.US, "%+.0f", it) } ?: "—",
                    Modifier.weight(1f),
                    "First → latest"
                )
                MetricCard(
                    "Coverage",
                    if (totalScopedHives > 0) "$observedHives/$totalScopedHives" else "—",
                    Modifier.weight(1f),
                    "Hives with data"
                )
            }
            if (points.isEmpty()) {
                EmptyState(
                    "No health data in this range",
                    "Complete an inspection inside ${range.label} to start the trend.",
                    null,
                    null
                )
            } else {
                HealthTrendGraph(points, range)
                Text(
                    "Range average ${summary.average?.let { "${HealthAnalytics.roundedScore(it)} / 100" } ?: "—"} • Latest ${summary.latest?.let { "${HealthAnalytics.roundedScore(it)} / 100" } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (points.size == 1) {
                    Text(
                        "One observation only — the trend will become more meaningful as inspections accumulate.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Health score is BeeKeep's heuristic signal, not a diagnosis. 80+ Strong • 60–79 Watch • 40–59 Needs attention • <40 High attention.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HealthTrendGraph(points: List<HealthPoint>, range: HealthAnalytics.Range) {
    val sorted = points.sortedBy { it.timestamp }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)
    val surface = MaterialTheme.colorScheme.surface
    val labels = listOf(100, 75, 50, 25, 0)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(190.dp)) {
            Column(Modifier.width(30.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(surface)
            ) {
                val left = 12f
                val right = size.width - 12f
                val top = 12f
                val bottom = size.height - 12f
                val yValues = floatArrayOf(0f, .25f, .5f, .75f, 1f)
                yValues.forEach { fraction ->
                    val y = bottom - fraction * (bottom - top)
                    drawLine(grid, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), strokeWidth = 1f)
                }
                if (sorted.isNotEmpty()) {
                    val minTime = sorted.first().timestamp.toDouble()
                    val maxTime = sorted.last().timestamp.toDouble()
                    val span = (maxTime - minTime).coerceAtLeast(1.0)
                    fun x(timestamp: Long): Float = left + (((timestamp - minTime) / span) * (right - left)).toFloat()
                    fun y(score: Double): Float = bottom - (score.coerceIn(0.0, 100.0) / 100.0 * (bottom - top)).toFloat()

                    val linePath = Path()
                    val first = sorted.first()
                    linePath.moveTo(x(first.timestamp), y(first.score))
                    sorted.drop(1).forEach { point -> linePath.lineTo(x(point.timestamp), y(point.score)) }

                    if (sorted.size > 1) {
                        val fillPath = Path()
                        fillPath.moveTo(x(first.timestamp), bottom)
                        sorted.forEach { point -> fillPath.lineTo(x(point.timestamp), y(point.score)) }
                        fillPath.lineTo(x(sorted.last().timestamp), bottom)
                        fillPath.close()
                        drawPath(fillPath, primary.copy(alpha = 0.10f))
                    }
                    drawPath(linePath, primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
                    sorted.forEach { point ->
                        val center = androidx.compose.ui.geometry.Offset(x(point.timestamp), y(point.score))
                        drawCircle(primary, radius = 7f, center = center)
                        drawCircle(surface, radius = 2.8f, center = center)
                    }
                }
            }
        }
        if (sorted.isNotEmpty()) {
            val labelIndices = when {
                sorted.size == 1 -> listOf(0)
                sorted.size == 2 -> listOf(0, 1)
                else -> listOf(0, sorted.lastIndex / 2, sorted.lastIndex).distinct()
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 38.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                labelIndices.forEach { index ->
                    Text(
                        HealthAnalytics.bucketLabel(sorted[index].timestamp, range),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
private fun CompactHiveHealthTrendCard(hive: Hive, inspections: List<Inspection>) {
    val range = HealthAnalytics.Range.NINETY
    val points = HealthAnalytics.hivePoints(hive, inspections, range)
    val summary = HealthAnalytics.summary(points)
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Health trend • 90D", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (points.isEmpty()) {
                Text("Complete inspections to build a health trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                HealthTrendGraph(points, range)
                Text(
                    "Average ${summary.average?.let { "${HealthAnalytics.roundedScore(it)}/100" } ?: "—"} • Latest ${summary.latest?.let { "${HealthAnalytics.roundedScore(it)}/100" } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    tasks: List<Task>,
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
    onScheduleRecommendation: (SmartRecommendation) -> Unit,
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
    val openTasks = tasks.filter { !it.completed && it.hiveId == hive.id }.sortedBy { it.dueAt }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
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
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
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
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(Icons.Rounded.TaskAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("INSPECT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onFeed,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("FEED", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onTreat,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("TREAT", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onHarvest,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("HARVEST", style = MaterialTheme.typography.labelMedium) }
            }
        }

        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
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
                if (openTasks.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Next task: ${openTasks.first().title}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                        Text(
                            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(openTasks.first().dueAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        val smartRecommendations = SmartInspectionEngine.recommendations(hive, inspections, tasks)
        val healthScore = SmartInspectionEngine.healthScore(hive, inspections)
        SmartHealthCard(healthScore, SmartInspectionEngine.healthLabel(healthScore))
        SmartAssistantCard(smartRecommendations, onScheduleRecommendation)
        SmartComparisonCard(SmartInspectionEngine.compare(inspections))
        CompactHiveHealthTrendCard(hive, inspections)
        AdvancedAnalyticsCard(hives = listOf(hive), inspections = inspections, harvests = harvests)

        Card(shape = RoundedCornerShape(14.dp)) {
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
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("EDIT", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Card(shape = RoundedCornerShape(14.dp)) {
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
                                Box(Modifier.width(20.dp).height(height).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
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
                        Image(bitmap.asImageBitmap(), "Inspection photo", Modifier.size(90.dp).clip(RoundedCornerShape(10.dp)))
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Records", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Feedings: ${feedings.size} • Treatments: ${treatments.size} • Harvests: ${harvests.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                feedings.firstOrNull()?.let { Text("Last feed: ${it.amount} ${it.unit} • ${it.feedType}", style = MaterialTheme.typography.bodySmall) }
                treatments.firstOrNull()?.let { Text("Last treatment: ${it.product}", style = MaterialTheme.typography.bodySmall) }
                harvests.firstOrNull()?.let { Text("Last dry harvest: ${it.dryHoneyWeight} ${it.weightUnit}", style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(shape = RoundedCornerShape(14.dp)) {
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
                            shape = RoundedCornerShape(10.dp)
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
                            shape = RoundedCornerShape(10.dp)
                        ) { Text(if (hive.tagUid == null) "WRITE TAG" else "REPLACE", style = MaterialTheme.typography.labelMedium) }
                    }
                    if (hive.tagUid != null) {
                        TextButton(onClick = onClearTag, modifier = Modifier.fillMaxWidth().height(36.dp)) { Text("REMOVE TAG", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (writeStatus.isNotBlank()) Text(writeStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Colony lifecycle", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (hive.isDead) {
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(10.dp)) { Text("RESTORE COLONY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
                } else {
                    OutlinedButton(onClick = { confirmDead = true }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(10.dp)) { Text("MARK COLONY DEAD", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
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
private fun SmartHealthCard(score: Int, label: String) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hive health", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
                    Text("• $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$score/100", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { score / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SmartAssistantCard(
    recommendations: List<SmartRecommendation>,
    onSchedule: (SmartRecommendation) -> Unit
) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("What to check next", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.TaskAlt, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (recommendations.isEmpty()) {
                Text("No priority recommendations right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                for (recommendation in recommendations.take(3)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(recommendation.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(recommendation.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onSchedule(recommendation) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+ TASK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartComparisonCard(comparison: InspectionComparison?) {
    if (comparison == null || comparison.previous == null) return
    fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
    fun signedDouble(value: Double): String = if (value > 0) "+${String.format(Locale.US, "%.2f", value)}%" else "${String.format(Locale.US, "%.2f", value)}%"
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Since last inspection", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            ComparisonRow("Strength", comparison.strengthDelta?.let(::signed) ?: "—")
            ComparisonRow("Mites", comparison.miteDelta?.let(::signedDouble) ?: "—")
            ComparisonRow("Honey stores", comparison.honeyDelta?.let(::signed) ?: "—")
            ComparisonRow("Total brood frames", comparison.broodDelta?.let(::signed) ?: "—")
            if (comparison.queenChanged) ComparisonRow("Queen status", "Changed")
        }
    }
}

@Composable
private fun ComparisonRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
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
@Composable private fun InspectionSnapshot(i:Inspection){Card(shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("${i.strength}/10 strength • ${i.queenStatus}");Text("Mites ${i.miteCount}/${i.sampleSize} = ${String.format("%.2f",i.mitePercent)}%");Text("Brood: eggs ${i.eggs}, open ${i.openBrood}, capped ${i.cappedBrood}");Text("Stores: honey ${i.honeyStores}, pollen ${i.pollen}");if(i.diseaseFlags.isNotBlank())Text("Flags: ${i.diseaseFlags}",color=MaterialTheme.colorScheme.error)}}}

@Composable
private fun InspectionScreen(
    activity: ComponentActivity,
    photoStore: PhotoStore,
    locationController: LocationController,
    hive: Hive,
    priorInspections: List<Inspection>,
    tasks: List<Task>,
    onBack: () -> Unit,
    onSave: suspend (Inspection) -> Unit,
    onScheduleRecommendation: (SmartRecommendation) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastInspection = remember(priorInspections) { priorInspections.maxByOrNull { it.createdAt } }
    val recommendations = remember(hive, priorInspections, tasks) {
        SmartInspectionEngine.recommendations(hive, priorInspections, tasks).take(4)
    }

    var strength by rememberSaveable { mutableIntStateOf(hive.strength) }
    var queen by rememberSaveable { mutableStateOf(hive.queenStatus) }
    var mites by rememberSaveable { mutableIntStateOf(lastInspection?.miteCount ?: 0) }
    var sample by rememberSaveable { mutableIntStateOf(lastInspection?.sampleSize ?: 300) }
    var notes by rememberSaveable { mutableStateOf("") }
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
    var completedChecks by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedCheckId by rememberSaveable { mutableStateOf(recommendations.firstOrNull()?.id) }

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
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            notes = if (notes.isBlank()) it else "$notes $it"
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
    val healthScore = remember(hive, priorInspections) { SmartInspectionEngine.healthScore(hive, priorInspections) }
    val previousForComparison = remember(priorInspections) {
        priorInspections.sortedByDescending { it.createdAt }.getOrNull(1)
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("Hive ${hive.number}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("FIELD INSPECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("$healthScore/100", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 6.dp, shadowElevation = 12.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                        modifier = Modifier.weight(1.15f).height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Check, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (saving) "SAVE…" else "SAVE", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WHAT SHOULD I CHECK?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                        val next = recommendations.firstOrNull { it.id == selectedCheckId && !completedChecks.contains(it.id) }
                            ?: recommendations.firstOrNull { !completedChecks.contains(it.id) }
                        if (next == null) {
                            Text("Core suggested checks complete.", fontWeight = FontWeight.SemiBold)
                        } else {
                            selectedCheckId = next.id
                            Text(next.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text(next.action, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .86f))
                            Text("Why: ${next.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .76f))
                        }
                        if (recommendations.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (rec in recommendations) {
                                    FilterChip(
                                        selected = selectedCheckId == rec.id,
                                        onClick = { selectedCheckId = rec.id },
                                        label = { Text(if (completedChecks.contains(rec.id)) "✓ ${rec.title}" else rec.title, maxLines = 1) }
                                    )
                                }
                            }
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
                ComparisonCounter("Colony strength", strength, previousForComparison?.strength, 0..10, haptic) { strength = it }
            }
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Mite wash", fontWeight = FontWeight.ExtraBold)
                                Text("${mites} / ${sample} bees", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(String.format(Locale.US, "%.2f%%", miteRate), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                style = MaterialTheme.typography.bodySmall,
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
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FIELD NOTES", fontWeight = FontWeight.ExtraBold)
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            modifier = Modifier.fillMaxWidth().height(124.dp),
                            label = { Text("What did you see?") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (voiceStatus.isNotBlank()) Text(voiceStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (locationStatus != "No GPS captured") Text(locationStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                photoPath?.let { path ->
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PHOTO", fontWeight = FontWeight.ExtraBold)
                            rememberPhotoBitmap(path, 1200)?.let { bitmap ->
                                Image(bitmap.asImageBitmap(), "Inspection photo", Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp).clip(RoundedCornerShape(14.dp)))
                            }
                            if (photoProcessing) Text("Preparing photo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (cameraStatus.isNotBlank()) Text(cameraStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (recommendations.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("FOLLOW-UP CHECKS", fontWeight = FontWeight.ExtraBold)
                            for (rec in recommendations) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (completedChecks.contains(rec.id)) "✓ ${rec.title}" else rec.title,
                                        Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TextButton(
                                        onClick = {
                                            completedChecks = completedChecks.toMutableSet().apply {
                                                if (!add(rec.id)) remove(rec.id)
                                            }
                                        }
                                    ) { Text(if (completedChecks.contains(rec.id)) "UNDO" else "DONE") }
                                    TextButton(onClick = { onScheduleRecommendation(rec) }) { Text("TASK") }
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
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold)
                previous?.let {
                    val delta = value - it
                    Text(
                        "Prev $it • ${if (delta >= 0) "+$delta" else delta}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (delta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value - 1).coerceIn(range)) }) { Text("−", style = MaterialTheme.typography.headlineMedium) }
                Text(value.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value + 1).coerceIn(range)) }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
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
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value - 1).coerceIn(range)) }) { Text("−") }
                Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange((value + 1).coerceIn(range)) }) { Text("+") }
            }
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

@Composable private fun AddHiveScreen(apiaries:List<Apiary>, initialApiary: String?, onBack:()->Unit, onCreate:(String,String,String,Int)->Unit){BackHandler{onBack()};var number by rememberSaveable{mutableStateOf("")};var apiary by rememberSaveable(initialApiary) { mutableStateOf(initialApiary ?: apiaries.firstOrNull()?.name ?: "Home Yard") };var queen by rememberSaveable{mutableStateOf("Laying")};var strength by rememberSaveable{mutableIntStateOf(5)};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Rounded.ArrowBack,"Back")};Text("Add Hive",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold)};OutlinedTextField(number,{number=it},Modifier.fillMaxWidth(),label={Text("Hive number")},singleLine=true);OutlinedTextField(apiary,{ if (initialApiary == null) apiary=it },Modifier.fillMaxWidth(),label={Text("Apiary / Yard")},singleLine=true,readOnly=initialApiary != null);Text("Queen status",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){for(status in listOf("Laying","Spotted","Unspotted","Queenless","Virgin")){FilterChip(queen==status,{queen=status},{Text(status)})}};Counter("Starting strength",strength,0..10){strength=it};Button({onCreate(number,apiary,queen,strength)},Modifier.fillMaxWidth().height(60.dp),enabled=number.isNotBlank(),shape=RoundedCornerShape(18.dp)){Text("CREATE HIVE",fontWeight=FontWeight.ExtraBold)}}}

@Composable
private fun AddApiaryScreen(
    locationController: LocationController,
    onBack: () -> Unit,
    onSave: (String, String, Double?, Double?, String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var forage by rememberSaveable { mutableStateOf("") }
    var water by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lon by rememberSaveable { mutableStateOf<Double?>(null) }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Rounded.ArrowBack,"Back")};Text("Add Apiary",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold)}
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Apiary name")},singleLine=true)
        OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),label={Text("Site notes")})
        OutlinedTextField(forage,{forage=it},Modifier.fillMaxWidth(),label={Text("Forage notes")})
        OutlinedTextField(water,{water=it},Modifier.fillMaxWidth(),label={Text("Water notes")})
        OutlinedButton(onClick={
            if(locationController.hasPermission()) locationController.current { r ->
                if(r != null){lat=r.latitude;lon=r.longitude;gpsStatus="GPS captured"} else gpsStatus="Could not get a location"
            } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        },Modifier.fillMaxWidth().height(54.dp)){Icon(Icons.Rounded.LocationOn,null);Spacer(Modifier.width(6.dp));Text(if(lat==null)"CAPTURE CURRENT GPS" else "GPS CAPTURED")}
        if(gpsStatus.isNotBlank()) Text(gpsStatus,color=if(gpsStatus.startsWith("Could"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)
        Button({onSave(name.trim(),notes.trim(),lat,lon,forage.trim(),water.trim())},Modifier.fillMaxWidth().height(60.dp),enabled=name.isNotBlank(),shape=RoundedCornerShape(18.dp)){Text("SAVE APIARY",fontWeight=FontWeight.ExtraBold)}
    }
}

@Composable
private fun ScanScreen(available:Boolean, enabled:Boolean, hives:List<Hive>, onBack:()->Unit, onScan:()->Unit){
    val context = LocalContext.current
    BackHandler{onBack()}
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
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
        Spacer(Modifier.height(12.dp))
        Button(onClick=onScan,Modifier.fillMaxWidth().height(62.dp),enabled=available && enabled){Text("START NFC SCAN",fontWeight=FontWeight.ExtraBold)}
        if (available && !enabled) {
            OutlinedButton(onClick={runCatching{context.startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))}},Modifier.fillMaxWidth().height(52.dp)){Text("OPEN NFC SETTINGS")}
        }
        OutlinedButton(onBack){Text("Back")}
    }
}

@Composable
private fun TagManagementScreen(
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
    BackHandler { onBack() }
    val assigned = hives.count { !it.tagUid.isNullOrBlank() }

    reassignPrompt?.let { (targetHiveId, tagUid) ->
        AlertDialog(
            onDismissRequest = { reassignPrompt = null },
            title = { Text("Tag already assigned", fontWeight = FontWeight.ExtraBold) },
            text = { Text("This NFC tag is assigned to another hive. Reassigning will remove it from that hive and attach it to the selected one.") },
            confirmButton = {
                TextButton(onClick = {
                    onAssignTag(targetHiveId, tagUid, true) { success, error ->
                        status = if (success) "Tag $tagUid reassigned" else (error ?: "Could not reassign tag.")
                        if (success && pendingUid != null) onPendingUidConsumed()
                    }
                    reassignPrompt = null
                }) { Text("REASSIGN", fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = { TextButton(onClick = { reassignPrompt = null }) { Text("CANCEL") } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("NFC Tags", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("$assigned of ${hives.size} hives tagged", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How BeeKeep tags work", fontWeight = FontWeight.Bold)
                Text("Each physical tag has a unique UID. BeeKeep stores that UID with the hive and writes a small BeeKeep NDEF payload to the tag. Your hive history stays in BeeKeep, not on the tag.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Tags are reusable: when a colony dies its tag is released and can be assigned to another hive. Scanning always opens the colony the tag is currently assigned to.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                Text("Use durable, weather-resistant NFC tags on the hive lid or another protected surface.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (pendingUid != null) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
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
            Card(shape = RoundedCornerShape(18.dp)) {
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
                                            verifyText = "Assigned $pendingUid"
                                            onPendingUidConsumed()
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
                                                        verifyText = "Assigned ${result.uid}"
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
                                            verifyText = if (result.uid.equals(hive.tagUid, true) && (payloadId == null || payloadId == hive.id)) "✓ Physical tag matches Hive ${hive.number}" else "⚠ Tag ${result.uid} does not match Hive ${hive.number}"
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
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onInsights: () -> Unit,
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
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)

        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Yellow + black field mode", fontWeight = FontWeight.Bold)
                    Text("High-contrast controls for bright outdoor conditions", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
            }
        }

        Card(onClick = onTagManager, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("NFC tag management", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("Scan, verify, assign, replace or remove hive tags", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Nfc, "Open NFC tag management", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }

        Card(onClick = onColonyHistory, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Colony history", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(if (deadCount == 0) "Dead colonies are archived here with their full history" else "$deadCount dead ${if (deadCount == 1) "colony" else "colonies"} preserved", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.History, "Open colony history", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }

        Card(onClick = onInsights, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Season insights", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("Strength, mites, harvests, activity and hives to watch", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Assessment, "Open insights", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
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

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Notifications", fontWeight = FontWeight.Bold)
                        Text("Treatment and inspection reminders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({
                        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                })
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Notifications, "Manage notifications")
                    }
                }
                if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    Text("Notifications are off. Enable them so scheduled tasks can alert you.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                } else {
                    Text("Notifications are enabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (android.os.Build.VERSION.SDK_INT >= 31 && !ReminderScheduler.exactAlarmAvailable(context)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Precise reminder timing", fontWeight = FontWeight.Bold)
                            Text("Android may delay alerts without Alarms & reminders access.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick={ReminderScheduler.openExactAlarmSettings(context)}) { Text("ENABLE") }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
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
