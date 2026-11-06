package com.beekeep.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.beekeep.app.data.IdGenerator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.beekeep.app.data.ActivityEvent
import com.beekeep.app.data.Apiary
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Inspection
import com.beekeep.app.data.HarvestQuantityMode
import com.beekeep.app.data.workspaceActionSettingsScopeKey
import com.beekeep.app.data.LocalHiveRepository
import com.beekeep.app.data.Treatment
import com.beekeep.app.cloud.CloudResult
import com.beekeep.app.cloud.CloudSyncScheduler
import com.beekeep.app.cloud.SupabaseGateway
import com.beekeep.app.cloud.BeeKeepAccountSetupState
import com.beekeep.app.cloud.BeeKeepWorkspaceOwnerQuotaEntitlement
import com.beekeep.app.location.LocationController
import com.beekeep.app.nfc.NfcController
import com.beekeep.app.nfc.BeeKeepNfcPayload
import com.beekeep.app.nfc.NfcResult
import com.beekeep.app.nfc.NfcTagResolution
import com.beekeep.app.nfc.NfcUid
import com.beekeep.app.nfc.NfcUidIndex
import com.beekeep.app.nfc.playNfcAssignmentSuccessFeedback
import com.beekeep.app.ui.theme.BeeKeepTheme
import com.beekeep.app.ui.theme.BeeKeepAccent
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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val nfc = NfcController()
    internal val pendingNfcResult = MutableStateFlow<NfcResult.Read?>(null)
    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    private var pendingSpeech: String? = null
    private lateinit var locationController: LocationController
    private lateinit var cloudGateway: SupabaseGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speechEngine = TextToSpeech(this, this)
        nfc.attach(this)
        handleNfcIntent(intent)
        locationController = LocationController(this)
        val repository = LocalHiveRepository(applicationContext)
        // Keep NFC in foreground-reader mode while BeeKeep is visible. This takes
        // priority over Android's normal tag dispatch, so a tag can open its hive
        // without switching to a browser or another NFC app.
        nfc.setPassiveReadListener(this) { result ->
            val deliveryToken = nfc.passiveDeliveryToken()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val shouldForward = runCatching {
                    val assignedHive = repository.findHiveByNfc(result.uid)
                    assignedHive != null || result.info.hasBeeKeepLaunchRecord ||
                        BeeKeepNfcPayload.isBeeKeepPayload(result.text) || result.launchPreparationError != null
                }.getOrDefault(false)
                if (!shouldForward || !nfc.canDispatchPassiveRead(deliveryToken, result.uid)) {
                    return@launch
                }
                // A dialog may have opened while the database lookup ran. Re-check
                // on the main thread immediately before publishing to the UI flow.
                runOnUiThread {
                    if (nfc.canDispatchPassiveRead(deliveryToken, result.uid)) {
                        pendingNfcResult.value = result
                    }
                }
            }
        }
        val cloud = SupabaseGateway(applicationContext, repository)
        cloudGateway = cloud
        cloud.handleAuthDeepLink(intent)
        val vm = ViewModelProvider(this, BeeKeepViewModel.Factory(repository, applicationContext))[BeeKeepViewModel::class.java]
        lifecycleScope.launch {
            // Startup renders the account-bound local Room database first. Do not contend for
            // the sync mutex until the gateway has published the initial local session state.
            cloud.accountInitialized.first { it }
            // Schedule background sync only after local-first session and workspace selection
            // have been published, so a worker cannot hold the shared sync mutex ahead of startup.
            CloudSyncScheduler.schedule(applicationContext)
            if (cloud.account.value.signedIn) {
                if (cloud.syncNow() is CloudResult.Success) cloud.startRealtime()
            }
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            var darkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            var largeFieldText by rememberSaveable { mutableStateOf(prefs.getBoolean("large_field_text", false)) }
            var compactHiveRows by rememberSaveable { mutableStateOf(prefs.getBoolean("compact_hive_rows", true)) }
            BeeKeepTheme(darkMode = darkMode) {
                val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density,
                        fontScale = if (largeFieldText) (baseDensity.fontScale * 1.12f).coerceAtMost(2.0f) else baseDensity.fontScale
                    )
                ) {
                    androidx.compose.runtime.SideEffect {
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        controller.isAppearanceLightStatusBars = !darkMode
                        controller.isAppearanceLightNavigationBars = false
                    }
                    val incomingNfc by pendingNfcResult.collectAsStateWithLifecycle()
                    BeeKeepApp(
                        vm, nfc, this, locationController, cloud, darkMode, incomingNfc,
                        repository = repository,
                        onSpeakHiveNumber = ::speakHiveNumber,
                        largeFieldText = largeFieldText,
                        onLargeFieldTextChange = { value ->
                            largeFieldText = value
                            prefs.edit { putBoolean("large_field_text", value) }
                        },
                        compactHiveRows = compactHiveRows,
                        onCompactHiveRowsChange = { value ->
                            compactHiveRows = value
                            prefs.edit { putBoolean("compact_hive_rows", value) }
                        },
                        onDarkModeChange = { value ->
                            darkMode = value
                            prefs.edit { putBoolean("dark_mode", value) }
                        }
                    )
                }
            }
        }
    }


    private fun isFastTagAssignmentAudioSuppressed(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getBoolean("fast_tag_assignment_mode", false) ||
            prefs.getBoolean("fast_tag_session_active", false) ||
            prefs.getBoolean("fast_tag_session_screen_active", false)
    }

    internal fun stopHiveAnnouncements() {
        // Clear queued speech as well as stopping audio already being played. This
        // keeps delayed TextToSpeech initialization from speaking during fast tagging.
        pendingSpeech = null
        speechEngine?.stop()
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
        if (isFastTagAssignmentAudioSuppressed()) {
            pendingSpeech = null
            return
        }
        pendingSpeech?.let { phrase ->
            pendingSpeech = null
            speakText(phrase)
        }
    }

    internal fun speakHiveNumber(number: String) {
        if (isFastTagAssignmentAudioSuppressed()) {
            stopHiveAnnouncements()
            return
        }
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
        if (::cloudGateway.isInitialized) cloudGateway.handleAuthDeepLink(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        // Entitlement resolution can still be in progress on a cold start. Queue the
        // NDEF read here; BeeKeepApp handles it only after the account plan is verified.
        // While fast tagging is enabled, never interpret an Android NFC launch intent
        // as a request to navigate to Inspection. ReaderMode owns the physical tag;
        // this preference guard also survives a newly-created NfcController instance.
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("fast_tag_assignment_mode", false)
        ) return

        // NFC dispatch extras are already present in the Intent; parse them immediately
        // so the foreground reader cannot deliver the same physical tap first.
        nfc.readIntent(intent)?.let { result ->
            // During fast assignment the previous tag may be dispatched by Android
            // between writer sessions. Never queue that intent for the inspection flow.
            if (nfc.shouldSuppressIncomingNfcIntent(result.uid)) return
            // Debounce Android's normal tag intents against both duplicate intents and
            // passive ReaderMode events. A tag held at the phone must produce one prompt.
            if (nfc.shouldSuppressDuplicateIncomingRead(result.uid)) return
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
    HIVES,
    PREMADE_SETUP,
    APIARIES,
    APIARY_HIVES,
    MAP,
    WORKSPACE,
    SCAN,
    MORE,
    SUPPORT_ADMIN,
    SETTINGS,
    TAG_MANAGER,
    COLONY_HISTORY,
    PURCHASES
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
    locationController: LocationController,
    cloud: SupabaseGateway,
    darkMode: Boolean,
    incomingNfc: NfcResult.Read?,
    repository: LocalHiveRepository,
    onSpeakHiveNumber: (String) -> Unit,
    largeFieldText: Boolean,
    onLargeFieldTextChange: (Boolean) -> Unit,
    compactHiveRows: Boolean,
    onCompactHiveRowsChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit
) {
    val account by cloud.account.collectAsStateWithLifecycle()
    val accountInitialized by cloud.accountInitialized.collectAsStateWithLifecycle()
    val passwordRecoveryRequired by cloud.passwordRecoveryRequired.collectAsStateWithLifecycle()
    val accountSetupState by cloud.accountSetupState.collectAsStateWithLifecycle()
    var startupWatchdogElapsed by rememberSaveable { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(accountInitialized) {
        if (!accountInitialized) {
            startupWatchdogElapsed = false
            kotlinx.coroutines.delay(15_000L)
            if (!cloud.accountInitialized.value) startupWatchdogElapsed = true
        } else {
            startupWatchdogElapsed = false
        }
    }

    when {
        !accountInitialized && startupWatchdogElapsed -> BeeKeepStartupRecoveryScreen(cloud)
        !accountInitialized -> BeeKeepAuthLoadingScreen()
        passwordRecoveryRequired -> BeeKeepAuthScreen(cloud)
        !account.signedIn -> BeeKeepAuthScreen(cloud)
        accountSetupState is BeeKeepAccountSetupState.UsernameRequired -> {
            BeeKeepAccountSetupScreen(
                cloud = cloud,
                setup = accountSetupState as BeeKeepAccountSetupState.UsernameRequired
            )
        }
        accountSetupState is BeeKeepAccountSetupState.Unavailable -> {
            BeeKeepAccountSetupUnavailableScreen(
                cloud = cloud,
                message = (accountSetupState as BeeKeepAccountSetupState.Unavailable).message
            )
        }
        accountSetupState is BeeKeepAccountSetupState.Ready ||
            accountSetupState is BeeKeepAccountSetupState.Offline -> BeeKeepAuthenticatedApp(
            vm = vm,
            nfc = nfc,
            activity = activity,
            locationController = locationController,
            cloud = cloud,
            darkMode = darkMode,
            incomingNfc = incomingNfc,
            repository = repository,
            onSpeakHiveNumber = onSpeakHiveNumber,
            largeFieldText = largeFieldText,
            onLargeFieldTextChange = onLargeFieldTextChange,
            compactHiveRows = compactHiveRows,
            onCompactHiveRowsChange = onCompactHiveRowsChange,
            onDarkModeChange = onDarkModeChange
        )
        else -> BeeKeepAuthLoadingScreen()
    }
}

@Composable
private fun BeeKeepAuthenticatedApp(
    vm: BeeKeepViewModel,
    nfc: NfcController,
    activity: ComponentActivity,
    locationController: LocationController,
    cloud: SupabaseGateway,
    darkMode: Boolean,
    incomingNfc: NfcResult.Read?,
    repository: LocalHiveRepository,
    onSpeakHiveNumber: (String) -> Unit,
    largeFieldText: Boolean,
    onLargeFieldTextChange: (Boolean) -> Unit,
    compactHiveRows: Boolean,
    onCompactHiveRowsChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val cloudHiveLimitOverride by cloud.hiveLimitOverride.collectAsStateWithLifecycle()
    val serverVerifiedPlan by cloud.verifiedPlan.collectAsStateWithLifecycle()
    val cloudAccountInitialized by cloud.accountInitialized.collectAsStateWithLifecycle()
    val commercialEntitlementResolved = BuildConfig.DEBUG || cloudAccountInitialized
    val autoVoicePrefs = remember(context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }
    var autoVoiceEnabled by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getBoolean("auto_voice_on_nfc", true))
    }
    var fastTagAssignmentMode by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getBoolean("fast_tag_assignment_mode", false))
    }
    // This state represents the entire multi-hive assignment run, not just the
    // currently active NFC reader. It gates UI navigation even if a late NFC event
    // arrives after the controller has handed off between readers.
    // Persist the active run: Android can deliver an NFC NDEF intent while a tag is
    // being written, which can recreate the activity on some devices. The tagging
    // screen and its current hive must survive that lifecycle handoff.
    var fastAssignmentSessionActive by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getBoolean("fast_tag_session_active", false))
    }
    var fastAssignmentScreenActive by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getBoolean("fast_tag_session_screen_active", false))
    }
    var fastAssignmentCurrentHiveId by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getLong("fast_tag_session_current_hive_id", -1L).takeIf { it > 0L })
    }
    var fastAssignmentApiaryName by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getString("fast_tag_session_apiary", null))
    }
    var fastAssignmentIgnoredUid by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getString("fast_tag_session_ignored_uid", null))
    }
    var fastAssignmentAssignedHiveIds by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getStringSet("fast_tag_session_assigned_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet())
    }
    // Bundle restoration can contain a pre-scan value if Android recreates the
    // activity while handling the tag's NDEF launch. Reconcile against the durable
    // session marker before persisting any Compose state back to preferences.
    var fastAssignmentStateHydrated by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val persistedModeEnabled = autoVoicePrefs.getBoolean("fast_tag_assignment_mode", false)
        fastTagAssignmentMode = persistedModeEnabled
        fastAssignmentSessionActive = persistedModeEnabled &&
            autoVoicePrefs.getBoolean("fast_tag_session_active", false)
        fastAssignmentScreenActive = persistedModeEnabled &&
            autoVoicePrefs.getBoolean("fast_tag_session_screen_active", false)
        fastAssignmentCurrentHiveId = autoVoicePrefs
            .getLong("fast_tag_session_current_hive_id", -1L)
            .takeIf { it > 0L && fastAssignmentScreenActive }
        fastAssignmentApiaryName = autoVoicePrefs.getString("fast_tag_session_apiary", null)
            .takeIf { fastAssignmentScreenActive }
        fastAssignmentIgnoredUid = autoVoicePrefs.getString("fast_tag_session_ignored_uid", null)
            .takeIf { fastAssignmentScreenActive }
        fastAssignmentAssignedHiveIds = autoVoicePrefs
            .getStringSet("fast_tag_session_assigned_ids", emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
            .takeIf { fastAssignmentScreenActive }
            .orEmpty()
        fastAssignmentStateHydrated = true
    }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var purchasePlanName by rememberSaveable {
        mutableStateOf(BeeKeepEntitlements.currentPlan(context).name)
    }
    val localPurchasePlan = BeeKeepPlan.fromStoredValue(purchasePlanName)
    // A server-granted Commercial entitlement must override the debug purchase simulator too.
    // Otherwise a Hobbyist debug profile would still see upgrade prompts despite a valid grant.
    val purchasePlan = BeeKeepEntitlements.planForAppFeatures(
        isDebugBuild = BuildConfig.DEBUG,
        localPlan = localPurchasePlan,
        serverVerifiedPlan = serverVerifiedPlan
    )
    var lockedHivePromptVisible by rememberSaveable { mutableStateOf(false) }
    var lockedHivePromptName by rememberSaveable { mutableStateOf<String?>(null) }
    var lockedHivePromptHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var purchaseBackScreen by rememberSaveable { mutableStateOf(Screen.MORE) }
    var purchaseSuccessScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedHiveOpen by rememberSaveable { mutableStateOf(false) }
    var selectedApiaryName by rememberSaveable { mutableStateOf<String?>(null) }
    // Preserve scroll/search state when a hive detail screen temporarily replaces a list.
    val screenSaveableStateHolder = rememberSaveableStateHolder()
    var mapOrigin by rememberSaveable { mutableStateOf(Screen.HOME) }
    var mapFocusApiaryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var inspecting by rememberSaveable { mutableStateOf(false) }
    var addHive by rememberSaveable { mutableStateOf(false) }
    var hiveLimitPromptVisible by rememberSaveable { mutableStateOf(false) }
    var logType by rememberSaveable { mutableStateOf<HiveLogType?>(null) }
    var addApiary by rememberSaveable { mutableStateOf(false) }
    var apiaryLimitPromptVisible by rememberSaveable { mutableStateOf(false) }
    var addApiaryOptionsVisible by rememberSaveable { mutableStateOf(false) }
    var addingApiariesWithHives by rememberSaveable { mutableStateOf(false) }
    var editingApiary by remember { mutableStateOf<Apiary?>(null) }
    var unassignedTagUid by rememberSaveable { mutableStateOf<String?>(null) }
    var nfcWriteRetryMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var lastHiveTagSuccessHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastHiveTagSuccessUid by rememberSaveable { mutableStateOf<String?>(null) }
    var unassignedTagOrigin by rememberSaveable { mutableStateOf(Screen.HOME) }
    var tagManagerReturnScreen by rememberSaveable { mutableStateOf(Screen.MORE) }
    var pendingTagUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingInspectionHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingAutoStartVoiceHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingAutoTagWriteHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingAutoTagWriteIgnoredUid by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var pendingResetWarning by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLocationHive by remember { mutableStateOf<Hive?>(null) }
    var pendingApiaryMove by remember { mutableStateOf<Pair<Hive, Apiary>?>(null) }
    var apiaryBatchTargetPickerVisible by rememberSaveable { mutableStateOf(false) }
    var apiaryBatchActionTarget by remember { mutableStateOf<ApiaryBatchActionTarget?>(null) }
    var apiaryBatchActionType by remember { mutableStateOf<HiveLogType?>(null) }
    var apiaryBatchConfirmationMessage by rememberSaveable { mutableStateOf("") }
    var apiaryBatchSubmitting by remember { mutableStateOf(false) }
    var pendingApiaryBatchCommit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var locationCheckCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = androidx.compose.material3.SnackbarHostState()

    fun clearApiaryBatchAction() {
        apiaryBatchTargetPickerVisible = false
        apiaryBatchActionTarget = null
        apiaryBatchActionType = null
        apiaryBatchConfirmationMessage = ""
        apiaryBatchSubmitting = false
        pendingApiaryBatchCommit = null
    }

    fun requestApiaryBatchConfirmation(message: String, save: () -> Unit) {
        if (apiaryBatchSubmitting) return
        apiaryBatchSubmitting = true
        apiaryBatchConfirmationMessage = message
        pendingApiaryBatchCommit = save
    }

    fun cancelApiaryBatchConfirmation() {
        apiaryBatchConfirmationMessage = ""
        pendingApiaryBatchCommit = null
        apiaryBatchSubmitting = false
    }

    fun showUnassignedTagPrompt(uid: String, origin: Screen, tag: android.nfc.Tag?) {
        // Lock NFC before updating Compose state, so queued intents and IO callbacks
        // cannot race the dialog into opening a second time.
        nfc.beginUnassignedTagPrompt(activity, uid, tag)
        unassignedTagOrigin = origin
        unassignedTagUid = uid
    }

    fun dismissUnassignedTagPrompt() {
        nfc.finishUnassignedTagPrompt()
        unassignedTagUid = null
    }

    val hives by vm.hives.collectAsStateWithLifecycle()
    val deadHives by vm.deadHives.collectAsStateWithLifecycle()
    val account by cloud.account.collectAsStateWithLifecycle()
    val activeWorkspaceId by vm.activeWorkspaceId.collectAsStateWithLifecycle()
    val activeWorkspaceReadOnly by vm.activeWorkspaceReadOnly.collectAsStateWithLifecycle()
    val readOnlyWriteAttempt by vm.readOnlyWriteAttempt.collectAsStateWithLifecycle()
    var teamQuotaSnapshot by remember {
        mutableStateOf<Pair<String, BeeKeepWorkspaceOwnerQuotaEntitlement>?>(null)
    }
    var teamQuotaError by remember { mutableStateOf<String?>(null) }
    var teamQuotaRefreshSignal by remember { mutableIntStateOf(0) }
    val personalQuotaLimits = remember(purchasePlan, cloudHiveLimitOverride) {
        BeeKeepQuotaLimits.forPlan(purchasePlan, cloudHiveLimitOverride)
    }
    val selectedTeamQuota = teamQuotaSnapshot?.takeIf { it.first == activeWorkspaceId }?.second
    val isPersonalDataset = activeWorkspaceId.isNullOrBlank()
    val quotaContextKey = if (isPersonalDataset) {
        "personal:${purchasePlan.name}:${personalQuotaLimits.hiveLimit}:${personalQuotaLimits.apiaryLimit}"
    } else {
        "team:${activeWorkspaceId}:${selectedTeamQuota?.active}:${selectedTeamQuota?.effectiveHiveLimit}:" +
            "${selectedTeamQuota?.effectiveApiaryLimit}:${selectedTeamQuota?.memberRole}:$activeWorkspaceReadOnly"
    }
    var appliedQuotaContextKey by remember { mutableStateOf<String?>(null) }
    val quotaEnforcementReady = BeeKeepQuotaAccessPolicy.canWrite(
        isPersonalDataset = isPersonalDataset,
        quotaContextApplied = appliedQuotaContextKey == quotaContextKey,
        ownerCommercialActive = selectedTeamQuota?.active,
        memberRole = selectedTeamQuota?.memberRole,
        workspaceReadOnly = activeWorkspaceReadOnly
    )
    val activeQuotaLimits = if (isPersonalDataset) {
        personalQuotaLimits
    } else {
        selectedTeamQuota?.let {
            BeeKeepQuotaLimits.forWorkspaceOwner(it.active, it.effectiveHiveLimit, it.effectiveApiaryLimit)
        } ?: BeeKeepQuotaLimits.forPlan(BeeKeepPlan.HOBBYIST)
    }
    val effectiveHiveLimit = activeQuotaLimits.hiveLimit
    val quotaSnapshotVerified = appliedQuotaContextKey == quotaContextKey &&
        (if (isPersonalDataset) commercialEntitlementResolved else selectedTeamQuota != null)
    val quotaOrderedHives = remember(hives, deadHives) {
        (hives + deadHives)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<Hive> { it.isDead }
                    .thenBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.number.lowercase(Locale.ROOT) }
                    .thenBy { it.apiary.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
    }
    val lockedHiveIds = remember(quotaOrderedHives, effectiveHiveLimit, quotaSnapshotVerified) {
        if (!quotaSnapshotVerified) {
            emptySet()
        } else {
            BeeKeepEntitlements.hiveIdsBeyondLimit(
                quotaOrderedHives.map { it.id },
                effectiveHiveLimit
            )
        }
    }

    // A selected team uses the owner's server-resolved limits, never the invitee's personal plan.
    // Until that lookup succeeds, use a restrictive fallback and block quota-dependent writes.
    androidx.compose.runtime.LaunchedEffect(
        activeWorkspaceId,
        account.signedIn,
        account.userId,
        teamQuotaRefreshSignal
    ) {
        val workspaceId = activeWorkspaceId?.trim()?.takeIf { it.isNotEmpty() }
        if (workspaceId == null) {
            teamQuotaSnapshot = null
            teamQuotaError = null
            return@LaunchedEffect
        }

        // Keep the last verified quota for this workspace while offline. A failed refresh is not
        // evidence that the owner's plan changed or that the selected team should use another cap.
        teamQuotaError = null
        if (!account.signedIn) {
            teamQuotaError = "Sign in to verify this workspace's subscription limits."
            return@LaunchedEffect
        }

        while (true) {
            val result = cloud.workspaceOwnerQuotaEntitlement(workspaceId)
            if (result.isSuccess && result.value != null) {
                teamQuotaSnapshot = workspaceId to result.value!!
                teamQuotaError = null
            } else {
                teamQuotaError = result.error
                    ?: "Couldn't verify the workspace owner's subscription limits. Check your connection and retry."
            }
            delay(60_000)
        }
    }
    // Keep the selected Room dataset's write gate aligned with the live owner entitlement,
    // even when the beekeeper is on another screen and WorkspaceScreen is not mounted.
    androidx.compose.runtime.LaunchedEffect(
        activeWorkspaceId,
        selectedTeamQuota?.active,
        selectedTeamQuota?.memberRole,
        activeWorkspaceReadOnly
    ) {
        val workspaceId = activeWorkspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val quota = selectedTeamQuota ?: return@LaunchedEffect
        val expectedReadOnly = !quota.active || quota.memberRole !in setOf("owner", "admin", "member")
        if (activeWorkspaceReadOnly != expectedReadOnly) {
            val result = cloud.activateWorkspace(workspaceId)
            if (!result.isSuccess) {
                teamQuotaSnapshot = null
                teamQuotaError = result.error
                    ?: "Workspace access could not be refreshed. Check your connection and retry."
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(quotaContextKey) {
        // Install the quota before marking this data context writable. This also covers
        // personal<->team switching, where the same Room repository changes databases.
        vm.updateQuotaLimits(activeQuotaLimits)
        appliedQuotaContextKey = quotaContextKey
    }

    fun showQuotaVerificationMessage() {
        val message = when {
            activeWorkspaceId.isNullOrBlank() ->
                "BeeKeep could not verify the active plan limits. Try again."
            selectedTeamQuota == null ->
                teamQuotaError ?: "Checking the workspace owner's subscription limits…"
            !selectedTeamQuota.active ->
                "This workspace is locked because the owner's Commercial subscription is inactive."
            selectedTeamQuota.memberRole == "viewer" || activeWorkspaceReadOnly ->
                "This workspace is view-only. Ask the owner to check your access."
            else -> "Workspace access could not be verified. Check your connection and try again."
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    var hasJoinedTeamWorkspace by rememberSaveable {
        mutableStateOf(autoVoicePrefs.getString("selected_team_workspace_id", "").orEmpty().isNotBlank())
    }
    val isCommercialPlan = purchasePlan == BeeKeepPlan.COMMERCIAL
    val nfcAccessScopeKey = if (isPersonalDataset) {
        "personal:${account.userId.orEmpty()}"
    } else {
        "workspace:${activeWorkspaceId.orEmpty()}"
    }
    val nfcAccessResolved = if (isPersonalDataset) {
        commercialEntitlementResolved
    } else {
        // Do not trust a quota snapshot from the previously selected team while
        // the new workspace entitlement is still being loaded.
        quotaSnapshotVerified
    }
    val resolvedNfcAccess = BeeKeepNfcAccessPolicy.resolve(
        isPersonalDataset = isPersonalDataset,
        personalCommercialActive = commercialEntitlementResolved && isCommercialPlan,
        workspaceOwnerCommercialActive = selectedTeamQuota?.active,
        workspaceMemberRole = selectedTeamQuota?.memberRole,
        workspaceWritable = !activeWorkspaceReadOnly
    )
    val nfcCanScan = nfcAccessResolved && resolvedNfcAccess.canScan
    val nfcCanManage = nfcAccessResolved && resolvedNfcAccess.canManageTags
    var hasServerCommercialEntitlement by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(account.signedIn, purchasePlan) {
        if (!account.signedIn) {
            hasServerCommercialEntitlement = false
        } else {
            val entitlement = cloud.myActiveCommercialEntitlement()
            // Preserve a previously verified entitlement during transient network failures.
            if (entitlement.isSuccess) {
                hasServerCommercialEntitlement = entitlement.value == true
            }
        }
    }
    val hasCommercialWorkspaceAccess = isCommercialPlan || hasServerCommercialEntitlement
    var workspaceMembershipResolvedForUser by rememberSaveable { mutableStateOf(false) }

    // Discover team access from the signed-in account on every device. A saved local workspace
    // selection is only a convenience and must not decide whether a real member can open the page.
    androidx.compose.runtime.LaunchedEffect(account.signedIn, account.email) {
        if (!account.signedIn) {
            workspaceMembershipResolvedForUser = false
        } else {
            workspaceMembershipResolvedForUser = false
            val membership = cloud.listWorkspaces()
            if (membership.isSuccess) {
                val hasTeamMembership = membership.value.orEmpty().any { workspace -> !workspace.is_personal }
                hasJoinedTeamWorkspace = hasTeamMembership
                workspaceMembershipResolvedForUser = true
                if (!hasTeamMembership) {
                    autoVoicePrefs.edit { remove("selected_team_workspace_id") }
                }
            } else {
                // Do not trap a valid member outside the Workspace page during a transient
                // membership lookup failure; the page can surface the error and allow a retry.
                hasJoinedTeamWorkspace = true
                workspaceMembershipResolvedForUser = true
            }
        }
    }

    val showWorkspaceDestination = hasCommercialWorkspaceAccess ||
        (account.signedIn && (hasJoinedTeamWorkspace || !workspaceMembershipResolvedForUser)) ||
        !activeWorkspaceId.isNullOrBlank()
    androidx.compose.runtime.LaunchedEffect(activeWorkspaceId) {
        if (!activeWorkspaceId.isNullOrBlank()) hasJoinedTeamWorkspace = true
    }
    val apiaries by vm.apiaries.collectAsStateWithLifecycle()
    val harvestSettingsScopeKey = remember(activeWorkspaceId, account.userId) {
        workspaceActionSettingsScopeKey(activeWorkspaceId, account.userId)
    }
    val savedHarvestActionSettings by repository
        .observeWorkspaceActionSettings(harvestSettingsScopeKey)
        .collectAsStateWithLifecycle(initialValue = null)
    val currentHarvestQuantityMode = HarvestQuantityMode.fromStorage(
        savedHarvestActionSettings?.harvestQuantityMode
    )
    androidx.compose.runtime.LaunchedEffect(activeWorkspaceId) {
        clearApiaryBatchAction()
    }
    val apiaryHiveCounts by vm.apiaryHiveCounts.collectAsStateWithLifecycle()
    var showViewOnlyDialog by rememberSaveable { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(readOnlyWriteAttempt) {
        if (readOnlyWriteAttempt > 0L) showViewOnlyDialog = true
    }
    androidx.compose.runtime.LaunchedEffect(snackbarHostState.currentSnackbarData?.visuals?.message) {
        val message = snackbarHostState.currentSnackbarData?.visuals?.message
        if (message?.contains("view-only", ignoreCase = true) == true) {
            showViewOnlyDialog = true
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }
    if (showViewOnlyDialog) {
        AlertDialog(
            onDismissRequest = { showViewOnlyDialog = false },
            title = { Text("VIEW ONLY", fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold) },
            text = {
                Text(
                    "Your role in this team is view-only. You can view shared records, but you cannot save inspections or make changes. Ask a workspace owner to update your role if you need editing access."
                )
            },
            confirmButton = {
                TextButton(onClick = { showViewOnlyDialog = false }) { Text("OK") }
            }
        )
    }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val feedings by vm.feedings.collectAsStateWithLifecycle()
    val feedingsLoading by vm.feedingsLoading.collectAsStateWithLifecycle()
    val feedingsError by vm.feedingsError.collectAsStateWithLifecycle()
    val treatments by vm.treatments.collectAsStateWithLifecycle()
    val treatmentsLoading by vm.treatmentsLoading.collectAsStateWithLifecycle()
    val treatmentsError by vm.treatmentsError.collectAsStateWithLifecycle()
    val harvests by vm.harvests.collectAsStateWithLifecycle()
    val harvestsLoading by vm.harvestsLoading.collectAsStateWithLifecycle()
    val harvestsError by vm.harvestsError.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()

    // Keep the Commercial test subscription's expiry live while BeeKeep stays open, not only on app resume.
    androidx.compose.runtime.LaunchedEffect(purchasePlanName) {
        if (purchasePlan.isPaidSubscription) {
            val expiresAt = BeeKeepEntitlements.debugCommercialExpiresAt(context)
            if (expiresAt != null) {
                val remainingMillis = expiresAt - System.currentTimeMillis()
                if (remainingMillis > 0L) kotlinx.coroutines.delay(remainingMillis)
            }
            // Re-check even when the expiry getter returns null, since it may have
            // noticed and normalized an expiry at the exact time this effect started.
            purchasePlanName = BeeKeepEntitlements.currentPlan(context).name
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        screen,
        nfcAccessResolved,
        nfcCanScan,
        isPersonalDataset
    ) {
        if (!nfcAccessResolved || nfcCanScan ||
            (screen != Screen.TAG_MANAGER && screen != Screen.SCAN)
        ) return@LaunchedEffect

        nfc.stop(activity)
        if (isPersonalDataset) {
            purchaseBackScreen = Screen.MORE
            purchaseSuccessScreen = Screen.MORE
            screen = Screen.PURCHASES
        } else {
            screen = Screen.MORE
            scope.launch {
                snackbarHostState.showSnackbar(
                    "NFC is locked in this workspace until its owner has active Commercial access."
                )
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        nfcAccessScopeKey,
        nfcAccessResolved,
        nfcCanScan,
        nfcCanManage,
        fastTagAssignmentMode,
        fastAssignmentSessionActive,
        fastAssignmentScreenActive
    ) {
        // Keep the NFC controller aligned with the entitlement for the selected
        // personal/team database. It starts denied and is enabled only after a
        // trusted personal or membership-scoped workspace check succeeds.
        nfc.setEntitlementAccess(
            accessScopeKey = nfcAccessScopeKey,
            canScan = nfcAccessResolved && nfcCanScan,
            canManageTags = nfcAccessResolved && nfcCanManage
        )
        if (!nfcAccessResolved) return@LaunchedEffect

        if (!nfcCanScan || !nfcCanManage) {
            nfc.stop(activity)
            nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
            fastTagAssignmentMode = false
            fastAssignmentSessionActive = false
            fastAssignmentScreenActive = false
            fastAssignmentCurrentHiveId = null
            fastAssignmentApiaryName = null
            fastAssignmentIgnoredUid = null
            fastAssignmentAssignedHiveIds = emptySet()
            pendingAutoTagWriteHiveId = null
            pendingAutoTagWriteIgnoredUid = null
            pendingInspectionHiveId = null
            pendingAutoStartVoiceHiveId = null
            autoVoicePrefs.edit {
                putBoolean("fast_tag_assignment_mode", false)
                remove("fast_tag_session_active")
                remove("fast_tag_session_screen_active")
                remove("fast_tag_session_current_hive_id")
                remove("fast_tag_session_apiary")
                remove("fast_tag_session_ignored_uid")
                remove("fast_tag_session_assigned_ids")
            }
        }

        if (nfcCanScan) {
            // Viewers may still scan and verify tags. Only writable roles retain
            // Fast Tag Assignment and other tag-changing operations.
            val allowPassiveReads = !fastTagAssignmentMode &&
                !fastAssignmentSessionActive && !fastAssignmentScreenActive &&
                screen != Screen.SCAN && screen != Screen.TAG_MANAGER
            nfc.setPassiveReadingEnabled(activity, allowPassiveReads)
        } else {
            nfc.setPassiveReadingEnabled(activity, false)
            unassignedTagUid = null
            pendingTagUid = null
            (activity as? MainActivity)?.pendingNfcResult?.value = null
        }
    }

    fun requestHiveAccess(hive: Hive): Boolean {
        val latestPlan = BeeKeepEntitlements.currentPlan(context)
        if (isPersonalDataset && purchasePlanName != latestPlan.name) {
            purchasePlanName = latestPlan.name
        }
        val latestHiveLimit = if (isPersonalDataset) {
            BeeKeepEntitlements.hiveLimit(latestPlan, cloudHiveLimitOverride)
        } else {
            effectiveHiveLimit
        }
        val latestLockedHiveIds = if (quotaSnapshotVerified) {
            BeeKeepEntitlements.hiveIdsBeyondLimit(
                quotaOrderedHives.map { it.id },
                latestHiveLimit
            )
        } else {
            emptySet()
        }
        if (hive.id !in latestLockedHiveIds) return true
        lockedHivePromptName = hiveDisplayName(hive.number)
        lockedHivePromptHiveId = hive.id
        lockedHivePromptVisible = true
        return false
    }

    fun requestHiveAccessById(hiveId: Long): Boolean {
        val hive = (hives + deadHives).firstOrNull { it.id == hiveId }
        return hive?.let { requestHiveAccess(it) } ?: true
    }

    fun openLockedHiveUpgrade() {
        lockedHivePromptVisible = false
        lockedHivePromptName = null
        lockedHivePromptHiveId = null
        purchaseBackScreen = screen
        purchaseSuccessScreen = screen
        screen = Screen.PURCHASES
    }

    fun canUpgradeLockedHive(): Boolean =
        isPersonalDataset || selectedTeamQuota?.memberRole == "owner"

    fun lockedHiveAccessMessage(hiveName: String): String {
        val limit = activeQuotaLimits.hiveLimit
        return if (canUpgradeLockedHive()) {
            "$hiveName is above the current ${activeQuotaLimits.plan.displayName} plan's ${limit}-hive limit. Your hive and its records are safe. Upgrade to Commercial to unlock and edit it."
        } else {
            "$hiveName is above the workspace owner's current ${activeQuotaLimits.plan.displayName} plan's ${limit}-hive limit. Your hive and its records are safe. Ask the workspace owner to renew Commercial to unlock and edit it."
        }
    }

    fun stopFastAssignmentForLockedHive() {
        nfc.stop(activity)
        nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
        fastAssignmentSessionActive = false
        fastAssignmentScreenActive = false
        fastAssignmentCurrentHiveId = null
        fastAssignmentApiaryName = null
        fastAssignmentIgnoredUid = null
        fastAssignmentAssignedHiveIds = emptySet()
        pendingAutoTagWriteHiveId = null
        pendingAutoTagWriteIgnoredUid = null
        pendingAutoStartVoiceHiveId = null
        pendingInspectionHiveId = null
        inspecting = false
        selectedHiveOpen = false
        vm.clearHive()
    }

    // If a plan renewal unlocks the hive behind an already-open alert, dismiss that
    // stale prompt immediately. The currently displayed hive/list can continue in place.
    androidx.compose.runtime.LaunchedEffect(lockedHiveIds, lockedHivePromptVisible, lockedHivePromptHiveId) {
        val promptHiveId = lockedHivePromptHiveId
        if (lockedHivePromptVisible && promptHiveId != null && promptHiveId !in lockedHiveIds) {
            lockedHivePromptVisible = false
            lockedHivePromptName = null
            lockedHivePromptHiveId = null
        }
    }

    // Stop any in-flight hardware operation as soon as the currently open hive
    // becomes locked, while leaving the lock screen available to explain the expiry.
    androidx.compose.runtime.LaunchedEffect(lockedHiveIds, selected?.id, fastAssignmentCurrentHiveId) {
        val selectedHiveLocked = selected?.id?.let { it in lockedHiveIds } == true
        val fastTargetLocked = fastAssignmentCurrentHiveId?.let { it in lockedHiveIds } == true
        if (selectedHiveLocked || fastTargetLocked) {
            nfc.stop(activity)
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            pendingAutoTagWriteHiveId = null
            pendingAutoTagWriteIgnoredUid = null
            pendingAutoStartVoiceHiveId = null
            pendingInspectionHiveId = null
            inspecting = false
        }
    }

    // Show a themed prompt when the active plan's hive cap is reached.
    androidx.compose.runtime.LaunchedEffect(
        addHive,
        hives.size,
        deadHives.size,
        lockedHiveIds,
        purchasePlan,
        effectiveHiveLimit,
        quotaEnforcementReady
    ) {
        val availablePlanHiveCount = BeeKeepEntitlements.countHivesSubjectToFreeLimit(
            (hives + deadHives).map { it.id },
            lockedHiveIds
        )
        if (quotaEnforcementReady && addHive && availablePlanHiveCount >= effectiveHiveLimit) {
            hiveLimitPromptVisible = true
        }
    }

    fun skipFastAssignmentHive(skippedHiveId: Long, reason: String) {
        // Ignore stale or duplicate skip callbacks after another hive becomes current.
        if (fastAssignmentCurrentHiveId != skippedHiveId ||
            (!fastAssignmentSessionActive && !fastAssignmentScreenActive)
        ) return

        nfc.beginFastAssignmentFlow(activity)
        selectedHiveOpen = false
        pendingAutoTagWriteHiveId = null
        pendingAutoTagWriteIgnoredUid = null
        vm.clearHive()
        scope.launch {
            val latestHives = runCatching { vm.getHivesSnapshot() }.getOrNull()
            if (latestHives == null) {
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = true)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                snackbarHostState.showSnackbar(
                    "Fast Tag Assignment stopped. BeeKeep could not refresh hive assignments, so no tag was written."
                )
                return@launch
            }

            // Re-check this skip after the suspend point before changing the target.
            if (fastAssignmentCurrentHiveId != skippedHiveId ||
                (!fastAssignmentSessionActive && !fastAssignmentScreenActive)
            ) return@launch

            val latestAssignments = runCatching { vm.getAllKnownNfcTagAssignments() }.getOrNull()
            if (latestAssignments == null) {
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = true)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                snackbarHostState.showSnackbar(
                    "Fast Tag Assignment stopped. BeeKeep could not verify the NFC assignment ledger, so no further tag will be written."
                )
                return@launch
            }
            val alreadyAssignedHiveIds = latestAssignments
                .filter { !it.first.isNullOrBlank() }
                .map { it.second }
                .toSet()

            val skippedHive = latestHives.firstOrNull { it.id == skippedHiveId }
            val apiaryName = fastAssignmentApiaryName
                ?: skippedHive?.apiary
                ?: autoVoicePrefs.getString("fast_tag_session_apiary", null)
                ?: ""
            val nextHive = FastTagTargetSelection.nextUntaggedHive(
                hives = latestHives,
                apiaryId = skippedHive?.apiaryId,
                apiaryName = apiaryName,
                startHiveId = skippedHiveId,
                includeStart = false,
                excludedHiveIds = fastAssignmentAssignedHiveIds + alreadyAssignedHiveIds + skippedHiveId,
                lockedHiveIds = lockedHiveIds
            )
            val skippedLabel = skippedHive?.let { hiveReferenceName(it.number) }
                ?: ("Hive " + skippedHiveId)

            if (nextHive == null) {
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = true)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                snackbarHostState.showSnackbar(
                    "Skipped $skippedLabel: $reason. No untagged hives remain in $apiaryName."
                )
                return@launch
            }

            // Do not play the success ding for a skip. Keep the last successfully
            // assigned UID suppressed as the next hive's writer is prepared.
            lastHiveTagSuccessHiveId = null
            lastHiveTagSuccessUid = null
            fastAssignmentCurrentHiveId = nextHive.id
            fastAssignmentScreenActive = true
            snackbarHostState.showSnackbar("Skipped $skippedLabel: $reason.")
        }
    }

    fun advanceToNextUntaggedHiveForFastAssignment(currentHive: Hive, justAssignedUid: String) {
        // Use live session state and durable session markers. A callback can arrive
        // after a recomposition/activity handoff, so captured UI values alone must not
        // decide that an active assignment run has ended.
        val persistedSessionActive = autoVoicePrefs.getBoolean("fast_tag_session_active", false) ||
            autoVoicePrefs.getBoolean("fast_tag_session_screen_active", false)
        if (!fastAssignmentSessionActive && !fastAssignmentScreenActive && !persistedSessionActive) {
            fastAssignmentSessionActive = false
            return
        }
        fastAssignmentSessionActive = true

        (activity as? MainActivity)?.pendingNfcResult?.value = null
        pendingInspectionHiveId = null
        pendingAutoStartVoiceHiveId = null
        inspecting = false

        val apiaryName = fastAssignmentApiaryName
            ?: autoVoicePrefs.getString("fast_tag_session_apiary", null)
            ?: currentHive.apiary
        fastAssignmentApiaryName = apiaryName

        // Mark this hive complete before refreshing Room so a delayed emission cannot
        // make the same hive eligible again.
        val assignedInThisRun = fastAssignmentAssignedHiveIds + currentHive.id
        fastAssignmentAssignedHiveIds = assignedInThisRun

        selectedHiveOpen = false
        pendingAutoTagWriteHiveId = null
        pendingAutoTagWriteIgnoredUid = null
        vm.clearHive()
        nfc.rememberHandledUid(justAssignedUid)
        nfc.suppressFastAssignmentUid(justAssignedUid)
        // Keep Fast Tag Assignment as the exclusive NFC owner while resolving the next hive.
        nfc.beginFastAssignmentFlow(activity)

        scope.launch {
            // Room is authoritative. The StateFlow list can lag behind the assignment
            // transaction, and a long-lived NFC callback can otherwise use an old snapshot.
            val latestHives = runCatching { vm.getHivesSnapshot() }.getOrNull()
            val latestAssignments = runCatching { vm.getAllKnownNfcTagAssignments() }.getOrNull()
            if (latestHives == null || latestAssignments == null) {
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = true)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                snackbarHostState.showSnackbar(
                    "Hive tag was saved, but Fast Tag Assignment stopped because BeeKeep could not verify the latest hive/tag records. No further tag will be written."
                )
                return@launch
            }
            val alreadyAssignedHiveIds = latestAssignments
                .filter { !it.first.isNullOrBlank() }
                .map { it.second }
                .toSet()
            val currentLockedHiveIds = BeeKeepEntitlements.hiveIdsBeyondLimit(
                (latestHives + deadHives)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareBy<Hive> { it.isDead }
                            .thenBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                            .thenBy { it.number.lowercase(Locale.ROOT) }
                            .thenBy { it.apiary.lowercase(Locale.ROOT) }
                            .thenBy { it.id }
                    )
                    .map { it.id },
                effectiveHiveLimit
            )
            val nextUntaggedHive = FastTagTargetSelection.nextUntaggedHive(
                hives = latestHives,
                apiaryId = currentHive.apiaryId,
                apiaryName = apiaryName,
                startHiveId = currentHive.id,
                includeStart = false,
                excludedHiveIds = assignedInThisRun + alreadyAssignedHiveIds + currentHive.id,
                lockedHiveIds = currentLockedHiveIds
            )

            // A Stop action must win over a slow snapshot lookup; never revive a cancelled run.
            if (!fastAssignmentSessionActive && !fastAssignmentScreenActive) return@launch

            if (nextUntaggedHive == null) {
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = true)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                snackbarHostState.showSnackbar(
                    "Fast tag assignment complete for $apiaryName. No untagged hives remain."
                )
                return@launch
            }

            lastHiveTagSuccessHiveId = null
            lastHiveTagSuccessUid = null
            fastAssignmentCurrentHiveId = nextUntaggedHive.id
            fastAssignmentIgnoredUid = justAssignedUid
            fastAssignmentScreenActive = true
        }
    }

    fun startFastTagAssignmentFromHive(requestedHive: Hive) {
        if (!requestHiveAccess(requestedHive)) return
        (activity as? MainActivity)?.stopHiveAnnouncements()

        scope.launch {
            // Use Room's current hive rows and assignment ledger. A stale Hive.tagUid cache
            // must not make already-tagged targets appear eligible for a fresh NFC write.
            val latestHives = runCatching { vm.getHivesSnapshot() }.getOrNull()
            val latestAssignments = runCatching { vm.getAllKnownNfcTagAssignments() }.getOrNull()
            if (latestHives == null || latestAssignments == null) {
                snackbarHostState.showSnackbar("Fast Tag Assignment stopped. BeeKeep couldn't verify the current hive/tag records. No tag was written.")
                return@launch
            }
            val selectedHive = latestHives.firstOrNull { it.id == requestedHive.id }
            if (selectedHive == null) {
                snackbarHostState.showSnackbar("Fast Tag Assignment stopped because the selected hive no longer exists in this workspace.")
                return@launch
            }
            val alreadyAssignedHiveIds = latestAssignments
                .filter { !it.first.isNullOrBlank() }
                .map { it.second }
                .toSet()
            val firstUntaggedHive = FastTagTargetSelection.nextUntaggedHive(
                hives = latestHives,
                apiaryId = selectedHive.apiaryId,
                apiaryName = selectedHive.apiary,
                startHiveId = selectedHive.id,
                includeStart = true,
                excludedHiveIds = alreadyAssignedHiveIds,
                lockedHiveIds = lockedHiveIds
            )
            if (firstUntaggedHive == null) {
                snackbarHostState.showSnackbar(
                    "Fast Tag Assignment: no untagged hives remain in ${selectedHive.apiary}."
                )
                return@launch
            }

            // Selecting a hive starts a session scoped to that hive's apiary identity.
            // The writer only assigns tags to existing, explicitly selected hive IDs.
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            nfc.beginFastAssignmentFlow(activity)
            fastAssignmentSessionActive = true
            fastAssignmentApiaryName = selectedHive.apiary
            fastAssignmentAssignedHiveIds = emptySet()
            fastAssignmentCurrentHiveId = firstUntaggedHive.id
            fastAssignmentIgnoredUid = null
            fastAssignmentScreenActive = true
            pendingAutoTagWriteHiveId = null
            pendingAutoTagWriteIgnoredUid = null
            pendingInspectionHiveId = null
            pendingAutoStartVoiceHiveId = null
            inspecting = false
            selectedHiveOpen = false
            logType = null
            screen = Screen.HOME
            vm.clearHive()
        }
    }

    // Keep the in-progress handoff recoverable if Android recreates MainActivity
    // while dispatching a tag's newly-written NDEF record.
    androidx.compose.runtime.LaunchedEffect(
        fastAssignmentStateHydrated,
        fastAssignmentSessionActive,
        fastAssignmentScreenActive,
        fastAssignmentCurrentHiveId,
        fastAssignmentApiaryName,
        fastAssignmentIgnoredUid,
        fastAssignmentAssignedHiveIds
    ) {
        if (!fastAssignmentStateHydrated) return@LaunchedEffect
        autoVoicePrefs.edit {
            putBoolean("fast_tag_session_active", fastAssignmentSessionActive)
            putBoolean("fast_tag_session_screen_active", fastAssignmentScreenActive)
            if (fastAssignmentSessionActive || fastAssignmentScreenActive) {
                putLong("fast_tag_session_current_hive_id", fastAssignmentCurrentHiveId ?: -1L)
                putString("fast_tag_session_apiary", fastAssignmentApiaryName)
                putString("fast_tag_session_ignored_uid", fastAssignmentIgnoredUid)
                putStringSet("fast_tag_session_assigned_ids", fastAssignmentAssignedHiveIds.map(Long::toString).toSet())
            } else {
                remove("fast_tag_session_current_hive_id")
                remove("fast_tag_session_apiary")
                remove("fast_tag_session_ignored_uid")
                remove("fast_tag_session_assigned_ids")
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var nfcEnabledForIndicator by remember(nfc) {
        mutableStateOf(nfc.isAvailable() && nfc.isEnabled())
    }
    DisposableEffect(lifecycleOwner, nfc, context) {
        // NFC state broadcasts keep the Home indicator in sync while Settings is
        // open; ON_RESUME also refreshes it if the app returns from system settings.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: android.content.Context?, intent: Intent?) {
                if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    nfcEnabledForIndicator = nfc.isAvailable() && nfc.isEnabled()
                }
            }
        }
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcEnabledForIndicator = nfc.isAvailable() && nfc.isEnabled()
                purchasePlanName = BeeKeepEntitlements.currentPlan(context).name
                // Refresh subscriptions that may have been renewed outside the open screen.
                scope.launch {
                    cloud.refreshAccount()
                    teamQuotaRefreshSignal += 1
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        nfcEnabledForIndicator = nfc.isAvailable() && nfc.isEnabled()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Show cloud-reset warnings as soon as the user returns to Home. This no longer
    // depends on the removed first-launch setup prompt.
    androidx.compose.runtime.LaunchedEffect(pendingResetWarning, screen) {
        val warning = pendingResetWarning ?: return@LaunchedEffect
        if (screen == Screen.HOME) {
            snackbarHostState.showSnackbar(warning)
            pendingResetWarning = null
        }
    }

    // Wait until the ViewModel has loaded the scanned hive before presenting Inspect.
    // Fast assignment is the exception: a selected-hive change is a writer handoff,
    // never an implicit request to open Inspection.
    androidx.compose.runtime.LaunchedEffect(
        selected?.id,
        pendingInspectionHiveId,
        fastTagAssignmentMode,
        fastAssignmentSessionActive,
        fastAssignmentScreenActive,
        fastAssignmentStateHydrated
    ) {
        if (!fastAssignmentStateHydrated) return@LaunchedEffect
        // The preference is the authoritative gate: a stale or queued scan must not
        // enter Inspection while Fast Tag Assignment is switched on.
        if (fastTagAssignmentMode || fastAssignmentSessionActive || fastAssignmentScreenActive) {
            if (pendingInspectionHiveId != null) pendingInspectionHiveId = null
            if (pendingAutoStartVoiceHiveId != null) pendingAutoStartVoiceHiveId = null
            inspecting = false
            return@LaunchedEffect
        }
        val targetHiveId = pendingInspectionHiveId ?: return@LaunchedEffect
        if (selected?.id == targetHiveId) {
            inspecting = true
            pendingInspectionHiveId = null
        }
    }

    // Keep the NFC writer's UID protection and released-tag history synchronized with Room.
    androidx.compose.runtime.LaunchedEffect(ready, hives, deadHives) {
        if (!ready) {
            nfc.updateKnownHiveTags(emptyMap(), ready)
            return@LaunchedEffect
        }
        // Blend the assignment ledger with legacy hive cache values. Any disagreement
        // or duplicate UID stays ambiguous; do not choose a hive by query order.
        val assignments = vm.getAllKnownNfcTagAssignments()
        val index = NfcUidIndex.fromAssignments(assignments)
        nfc.updateKnownHiveTags(index.uniqueHiveIdByUid, ready, index.ambiguousUids)
    }

    // Scan and tag-management screens control when another NFC read happens.
    // In particular, do not retry a failed write passively while the Retry dialog is open.
    androidx.compose.runtime.LaunchedEffect(screen, nfcWriteRetryMessage, nfc, unassignedTagUid, addHive) {
        val passiveAllowed = screen != Screen.SCAN &&
            screen != Screen.TAG_MANAGER &&
            nfcWriteRetryMessage == null &&
            unassignedTagUid == null &&
            !addHive
        nfc.setPassiveReadingEnabled(activity, passiveAllowed)
    }

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
        if (!requestHiveAccess(hive)) {
            pendingLocationHive = null
            locationCheckCallback = null
            return
        }
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
                // A different yard is the only reason to propose a move. Do not
                // early-return merely because yards are close together: the phone can
                // legitimately be within 100 m of both the current and target apiaries.
                val nearestOther = apiaries
                    .filter { apiary -> currentApiary == null || apiary.id != currentApiary.id }
                    .mapNotNull { apiary ->
                        val latitude = apiary.latitude ?: return@mapNotNull null
                        val longitude = apiary.longitude ?: return@mapNotNull null
                        apiary to distanceMeters(
                            phoneLocation.latitude, phoneLocation.longitude,
                            latitude, longitude
                        )
                    }
                    .filter { it.second <= 100f }
                    .minByOrNull { it.second }
                if (nearestOther != null) {
                    val targetApiary = nearestOther.first
                    if (autoVoicePrefs.getBoolean("auto_move_hive_apiary", false)) {
                        vm.moveHiveToApiary(hive.id, targetApiary.name) { success, error ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (success) "${hiveReferenceName(hive.number)} moved from ${hive.apiary} to ${targetApiary.name}"
                                    else error ?: "Could not move ${hiveReferenceName(hive.number)} to ${targetApiary.name}."
                                )
                            }
                        }
                    } else {
                        pendingApiaryMove = hive to targetApiary
                    }
                } else if (currentApiary != null && assignedDistance > 100f) {
                    snackbarHostState.showSnackbar("${hiveReferenceName(hive.number)} is far from ${currentApiary.name}, but no other saved apiary GPS location is nearby. No changes made.")
                } else if (currentApiary == null) {
                    snackbarHostState.showSnackbar("No saved apiary GPS location is close enough to identify a destination. No changes made.")
                }
            }
        }
    }

    fun handleManualNfcScanResult(result: NfcResult) {
        scanning = false
        if (fastTagAssignmentMode || fastAssignmentSessionActive || fastAssignmentScreenActive) {
            nfc.stop(activity)
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Fast Tag Assignment is on. Turn it off before opening a hive inspection."
                )
            }
            return
        }
        when (result) {
            is NfcResult.Read -> {
                if (result.launchPreparationRetryable) {
                    unassignedTagUid = null
                    nfcWriteRetryMessage = result.launchPreparationError
                        ?: "BeeKeep could not write the tag. Try scanning it again."
                    return
                }
                result.launchPreparationError?.let { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
                scope.launch {
                    val normalizedUid = NfcUid.normalize(result.uid)
                    if (normalizedUid == null) {
                        snackbarHostState.showSnackbar("This tag has an invalid NFC UID. No hive was opened or assigned.")
                        return@launch
                    }
                    if (vm.isNfcTagAmbiguous(normalizedUid)) {
                        snackbarHostState.showSnackbar("NFC tag identity conflict: this UID is linked to multiple hives. No hive was opened. Repair it in NFC tag management.")
                        return@launch
                    }
                    val assignedHive = vm.findHiveByTag(normalizedUid)
                    val resolvedHiveId = NfcTagResolution.resolveHiveId(assignedHive?.id)
                    val resolvedHive = assignedHive?.takeIf { it.id == resolvedHiveId }
                    if (resolvedHive != null) {
                        if (!requestHiveAccess(resolvedHive)) {
                            screen = Screen.HOME
                            pendingAutoStartVoiceHiveId = null
                            pendingInspectionHiveId = null
                            inspecting = false
                        } else {
                            checkHiveLocation(resolvedHive)
                            if (autoVoicePrefs.getBoolean("speak_hive_number_on_scan", true)) onSpeakHiveNumber(resolvedHive.number)
                            vm.openHive(resolvedHive.id)
                            selectedHiveOpen = true
                            screen = Screen.HOME
                            pendingAutoStartVoiceHiveId = resolvedHive.id
                            pendingInspectionHiveId = resolvedHive.id
                            snackbarHostState.showSnackbar("${hiveReferenceName(resolvedHive.number)} recognized • inspection ready")
                        }
                    } else if (nfcCanManage) {
                        showUnassignedTagPrompt(normalizedUid, Screen.HOME, result.platformTag)
                    } else {
                        snackbarHostState.showSnackbar(
                            "This tag is not assigned in the selected workspace. Ask an Owner, Admin or Member to assign it."
                        )
                    }
                }
            }
            is NfcResult.Error -> scope.launch { snackbarHostState.showSnackbar(result.message) }
            is NfcResult.Written -> Unit
        }
    }

    fun startManualNfcScan() {
        if (!nfcAccessResolved) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (isPersonalDataset) "Checking your BeeKeep plan…" else "Checking the workspace owner's Commercial access…"
                )
            }
            return
        }
        if (!nfcCanScan) {
            if (isPersonalDataset) {
                purchaseBackScreen = screen
                purchaseSuccessScreen = screen
                screen = Screen.PURCHASES
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "NFC is locked in this workspace until its owner has active Commercial access."
                    )
                }
            }
            return
        }
        // A deliberate tap on Scan is an explicit retry, even if this UID was just dismissed.
        nfc.clearUnassignedPromptSuppressionForExplicitScan()
        val onResult: (NfcResult) -> Unit = ::handleManualNfcScanResult
        if (nfcCanManage) {
            nfc.startReadAndPrepareForBeeKeep(activity, onResult)
        } else {
            // Viewers can read existing assignments, but a scan must never write
            // launch data to an unassigned physical tag.
            nfc.startRead(activity, onResult)
        }
        scanning = nfc.isScanning()
    }

    androidx.compose.runtime.LaunchedEffect(
        ready,
        incomingNfc?.uid,
        screen,
        unassignedTagUid,
        fastTagAssignmentMode,
        fastAssignmentSessionActive,
        fastAssignmentScreenActive,
        fastAssignmentStateHydrated,
        nfcCanScan,
        nfcAccessResolved,
        nfcCanManage
    ) {
        val result = incomingNfc ?: return@LaunchedEffect
        if (!nfcAccessResolved) return@LaunchedEffect
        if (!nfcCanScan) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }
        if (!ready || !fastAssignmentStateHydrated) return@LaunchedEffect
        // The toggle itself—not only the current write transaction—owns NFC routing.
        // Ignore normal scan navigation until Fast Tag Assignment is switched off.
        if (fastTagAssignmentMode || fastAssignmentSessionActive || fastAssignmentScreenActive) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }
        // Once the unassigned-tag choice is visible, discard any queued reads until
        // the beekeeper chooses an action or cancels. This prevents popup re-entry.
        if (unassignedTagUid != null) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }
        // Discard an intent that was already queued when fast assignment took
        // exclusive ownership, or that refers to the tag just written.
        if (nfc.shouldSuppressIncomingNfcIntent(result.uid)) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }
        // Manual NFC management and scan screens own their tag reads. Ignore
        // passive-reader notifications there, otherwise a just-assigned tag can
        // navigate away to its hive before the write prompt becomes visible.
        if (screen == Screen.TAG_MANAGER || screen == Screen.SCAN) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }

        if (result.launchPreparationRetryable) {
            // A readable tag whose automatic launch-record write failed is not an
            // assignment decision. Offer a retry instead of sending it to the
            // Unassigned NFC tag flow.
            nfcWriteRetryMessage = result.launchPreparationError
                ?: "BeeKeep could not write the tag. Try scanning it again."
            unassignedTagUid = null
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            return@LaunchedEffect
        }
        result.launchPreparationError?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }

        val normalizedUid = NfcUid.normalize(result.uid)
        if (normalizedUid == null) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            scope.launch { snackbarHostState.showSnackbar("This tag has an invalid NFC UID. No hive was opened or assigned.") }
            return@LaunchedEffect
        }
        if (vm.isNfcTagAmbiguous(normalizedUid)) {
            (activity as? MainActivity)?.pendingNfcResult?.value = null
            scope.launch {
                snackbarHostState.showSnackbar(
                    "NFC tag identity conflict: this UID is linked to multiple hive records. No hive was opened or assigned. Repair the duplicate in More > NFC tag management."
                )
            }
            return@LaunchedEffect
        }

        val assignedHive = vm.findHiveByTag(normalizedUid)
        val resolvedHiveId = NfcTagResolution.resolveHiveId(assignedHive?.id)
        val resolvedHive = assignedHive?.takeIf { it.id == resolvedHiveId }
        if (resolvedHive != null) {
            if (!requestHiveAccess(resolvedHive)) {
                screen = Screen.HOME
                pendingAutoStartVoiceHiveId = null
                pendingInspectionHiveId = null
                inspecting = false
            } else {
                checkHiveLocation(resolvedHive)
                if (autoVoicePrefs.getBoolean("speak_hive_number_on_scan", true)) onSpeakHiveNumber(resolvedHive.number)
                val alreadyOpen = selectedHiveOpen && selected?.id == resolvedHive.id
                val alreadyInspectingThisHive = inspecting && selected?.id == resolvedHive.id
                if (!alreadyInspectingThisHive) pendingAutoStartVoiceHiveId = resolvedHive.id
                if (!alreadyOpen) {
                    vm.openHive(resolvedHive.id)
                    selectedHiveOpen = true
                }
                screen = Screen.HOME
                if (selected?.id == resolvedHive.id) {
                    inspecting = true
                } else {
                    pendingInspectionHiveId = resolvedHive.id
                }
                if (!alreadyOpen) scope.launch { snackbarHostState.showSnackbar("${hiveReferenceName(resolvedHive.number)} recognized • inspection ready") }
            }
        } else if (nfcCanManage) {
            // Even when a hive is open, an unassigned physical tag must go through
            // an explicit assignment choice. Never infer ownership from current screen.
            showUnassignedTagPrompt(normalizedUid, Screen.HOME, result.platformTag)
            screen = Screen.SCAN
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "This tag is not assigned in the selected workspace. Ask an Owner, Admin or Member to assign it."
                )
            }
        }
        (activity as? MainActivity)?.pendingNfcResult?.value = null
    }

    nfcWriteRetryMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {
                nfcWriteRetryMessage = null
                scanning = nfc.isScanning()
            },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Could not write NFC tag", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "$message\n\nTap Try Again, then present the same tag to your phone when scanning starts."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nfcWriteRetryMessage = null
                        unassignedTagUid = null
                        screen = Screen.SCAN
                        startManualNfcScan()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("TRY AGAIN", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nfcWriteRetryMessage = null
                        nfc.stop(activity)
                        scanning = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("CANCEL", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    unassignedTagUid?.let { uid ->
        AlertDialog(
            // Keep outside taps from dismissing this dialog and revealing the
            // scan screen underneath. Back and the explicit Cancel action exit safely.
            onDismissRequest = {
                dismissUnassignedTagPrompt()
                nfc.stop(activity)
                scanning = false
                if (unassignedTagOrigin != Screen.TAG_MANAGER) screen = Screen.HOME
            },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Unassigned NFC tag", fontWeight = FontWeight.ExtraBold) },
            text = { Text("Tag $uid is not assigned to a colony. What would you like to do with it?") },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(
                        onClick = {
                            dismissUnassignedTagPrompt()
                            pendingTagUid = uid
                            if (unassignedTagOrigin != Screen.TAG_MANAGER) {
                                tagManagerReturnScreen = unassignedTagOrigin
                            }
                            screen = Screen.TAG_MANAGER
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text("ASSIGN TO EXISTING HIVE", fontWeight = FontWeight.ExtraBold)
                    }
                    TextButton(
                        onClick = {
                            dismissUnassignedTagPrompt()
                            pendingTagUid = uid
                            // Return to Home after creating a hive from the scanner prompt.
                            if (unassignedTagOrigin != Screen.TAG_MANAGER) screen = Screen.HOME
                            addHive = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text("CREATE NEW HIVE", fontWeight = FontWeight.ExtraBold)
                    }
                    TextButton(
                        onClick = {
                            dismissUnassignedTagPrompt()
                            nfc.stop(activity)
                            scanning = false
                            if (unassignedTagOrigin != Screen.TAG_MANAGER) screen = Screen.HOME
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.ExtraBold)
                    }
                }
            },
            dismissButton = {}
        )
    }

    pendingApiaryMove?.let { (hive, targetApiary) ->
        AlertDialog(
            onDismissRequest = { pendingApiaryMove = null },
            title = { Text("Hive scanned at another apiary?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("${hiveReferenceName(hive.number)} is assigned to ${hive.apiary}, but your phone's GPS is near ${targetApiary.name}. Would you like to move this hive to ${targetApiary.name}?")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingApiaryMove = null
                    if (requestHiveAccess(hive)) {
                        vm.moveHiveToApiary(hive.id, targetApiary.name) { success, error ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (success) "${hiveReferenceName(hive.number)} moved to ${targetApiary.name}"
                                    else error ?: "Could not move the hive."
                                )
                            }
                        }
                    }
                }) { Text("MOVE HIVE", fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingApiaryMove = null }) { Text("KEEP CURRENT APIARY") }
            }
        )
    }

    if (lockedHivePromptVisible) {
        AlertDialog(
            onDismissRequest = {
                lockedHivePromptVisible = false
                lockedHivePromptName = null
                lockedHivePromptHiveId = null
            },
            shape = RoundedCornerShape(26.dp),
            containerColor = Color(0xFFFFFBEB),
            titleContentColor = Color(0xFF78350F),
            textContentColor = Color(0xFF654321),
            title = { Text("Upgrade BeeKeep", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${lockedHivePromptName ?: "This hive"} is above the current ${activeQuotaLimits.plan.displayName} plan's ${activeQuotaLimits.hiveLimit}-hive limit.")
                    if (isPersonalDataset || selectedTeamQuota?.memberRole == "owner") {
                        Text("Your hive and its existing records are safe. Upgrade to Commercial to unlock and edit it.")
                    } else {
                        Text("Your hive and its existing records are safe. Ask the workspace owner to renew Commercial to unlock and edit it.")
                    }
                }
            },
            confirmButton = {
                if (isPersonalDataset || selectedTeamQuota?.memberRole == "owner") {
                    Button(onClick = { openLockedHiveUpgrade() }, shape = RoundedCornerShape(14.dp)) {
                        Text("UPGRADE TO COMMERCIAL", fontWeight = FontWeight.ExtraBold)
                    }
                } else {
                    TextButton(onClick = {
                        lockedHivePromptVisible = false
                        lockedHivePromptName = null
                        lockedHivePromptHiveId = null
                    }) { Text("OK") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    lockedHivePromptVisible = false
                    lockedHivePromptName = null
                    lockedHivePromptHiveId = null
                }) {
                    Text("NOT NOW", color = Color(0xFF78350F))
                }
            }
        )
    }

    if (screen == Screen.PURCHASES) {
        PurchaseScreen(
            padding = PaddingValues(0.dp),
            currentPlan = purchasePlan,
            onBack = { screen = purchaseBackScreen },
            onChoosePlan = { plan ->
                if (BuildConfig.DEBUG) {
                    scope.launch {
                        val previousPlan = BeeKeepEntitlements.currentPlan(context)
                        val serverSync = cloud.syncDebugPlanToStaging(plan)
                        if (!serverSync.isSuccess) {
                            snackbarHostState.showSnackbar(
                                "Staging entitlement was NOT updated: ${serverSync.error ?: "unknown error"}. " +
                                    "The device plan was left unchanged so it stays in sync with Workspace access."
                            )
                            return@launch
                        }

                        BeeKeepEntitlements.setDebugPlan(context, plan)
                        purchasePlanName = plan.name

                        // The staging entitlement is authoritative now. Refresh both the account
                        // plan and selected team's owner access, then refetch that team's quota.
                        // The new locked-hive set is derived from this state, so the current screen
                        // unlocks immediately without requiring the beekeeper to close/reopen it.
                        cloud.refreshAccount()
                        teamQuotaRefreshSignal += 1

                        if (plan != BeeKeepPlan.HOBBYIST) {
                            screen = purchaseSuccessScreen
                        }

                        val localMessage = when {
                            plan == BeeKeepPlan.HOBBYIST && previousPlan.isPaidSubscription ->
                                "Debug plan reset to Hobbyist. All existing records are preserved."
                            plan == BeeKeepPlan.HOBBYIST ->
                                "Debug test plan set to Hobbyist."
                            previousPlan == BeeKeepPlan.HOBBYIST ->
                                "BeeKeep Commercial activated. Existing records were preserved."
                            else -> "Debug test plan activated: BeeKeep ${plan.displayName}."
                        }
                        snackbarHostState.showSnackbar("${localMessage} Staging cloud entitlement updated.")
                    }
                }
            }
        )
        return
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
        AddHiveScreen(
            apiaries = apiaries,
            initialApiary = selectedApiaryName,
            currentPlan = activeQuotaLimits.plan,
            hiveLimit = activeQuotaLimits.hiveLimit,
            defaultQueenStatus = autoVoicePrefs.getString("default_queen_status", "Laying")?.takeIf { it in setOf("Laying", "Queenless", "Virgin") } ?: "Laying",
            defaultStrength = autoVoicePrefs.getInt("default_starting_strength", 3).coerceIn(1, 5),
            hiveLimitPromptVisible = hiveLimitPromptVisible,
            onDismissHiveLimitPrompt = { hiveLimitPromptVisible = false },
            onOpenUpgrade = {
                hiveLimitPromptVisible = false
                addHive = false
                pendingTagUid = null
                purchaseBackScreen = screen
                purchaseSuccessScreen = Screen.HOME
                screen = Screen.PURCHASES
            },
            onBack = {
                addHive = false
                pendingTagUid = null
                hiveLimitPromptVisible = false
            }
        ) { number, apiary, queen, strength ->
            if (!quotaEnforcementReady) {
                showQuotaVerificationMessage()
            } else vm.createHive(number, apiary, queen, strength, tagUid = pendingTagUid) { success, error ->
                if (!success && error?.contains("Hive limit reached", ignoreCase = true) == true) {
                    // A cap rejection must open the themed prompt, not disappear into a snackbar.
                    hiveLimitPromptVisible = true
                } else {
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Hive ${number.trim()} created") }
                }
                if (success) {
                    addHive = false
                    pendingTagUid = null
                    hiveLimitPromptVisible = false
                }
            }
        }
        return
    }
    val apiaryToEdit = editingApiary
    if (apiaryToEdit != null) {
        AddApiaryScreen(
            locationController = locationController,
            existingApiary = apiaryToEdit,
            onBack = { editingApiary = null },
            onDelete = { apiaryId, onResult ->
                vm.deleteApiaryPermanently(apiaryId) { success, error ->
                    scope.launch {
                        snackbarHostState.showSnackbar(error ?: "Apiary and its hives deleted")
                    }
                    if (success) {
                        // Remove unfinished drafts for every visible hive deleted with this apiary.
                        val deletedHiveIds = (hives + deadHives)
                            .asSequence()
                            .filter { it.apiary.equals(apiaryToEdit.name, ignoreCase = true) }
                            .map { it.id }
                            .distinct()
                            .toList()
                        clearInspectionDraftsForHives(activity, deletedHiveIds)
                        if (selectedApiaryName?.equals(apiaryToEdit.name, ignoreCase = true) == true) {
                            selectedApiaryName = null
                            if (screen == Screen.APIARY_HIVES) screen = Screen.APIARIES
                        }
                        editingApiary = null
                    }
                    onResult(success, error)
                }
            },
            onSave = { id, name, notes, lat, lon, forage, water ->
                vm.updateApiary(id ?: apiaryToEdit.id, name, notes, lat, lon, forage, water) { success, error ->
                    scope.launch { snackbarHostState.showSnackbar(error ?: "Apiary updated") }
                    if (success) editingApiary = null
                }
            }
        )
        return
    }
    if (addApiary) {
        AddApiaryScreen(
            locationController = locationController,
            existingApiary = null,
            onBack = {
                addApiary = false
                apiaryLimitPromptVisible = false
            },
            apiaryLimitPromptVisible = apiaryLimitPromptVisible,
            currentPlan = activeQuotaLimits.plan,
            onDismissApiaryLimitPrompt = { apiaryLimitPromptVisible = false },
            onOpenUpgrade = {
                apiaryLimitPromptVisible = false
                addApiary = false
                purchaseBackScreen = screen
                purchaseSuccessScreen = Screen.HOME
                screen = Screen.PURCHASES
            },
            onSave = { _, name, notes, lat, lon, forage, water ->
                if (!quotaEnforcementReady) {
                    showQuotaVerificationMessage()
                } else vm.saveApiary(name, notes, lat, lon, forage, water) { success, error ->
                    if (!success && error?.contains("Apiary limit reached", ignoreCase = true) == true) {
                        apiaryLimitPromptVisible = true
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(error ?: "Apiary saved") }
                    }
                    if (success) {
                        addApiary = false
                        apiaryLimitPromptVisible = false
                    }
                }
            }
        )
        return
    }
    if (fastAssignmentScreenActive) {
        val targetHive = hives.firstOrNull { it.id == fastAssignmentCurrentHiveId }
        if (targetHive != null && targetHive.id in lockedHiveIds) {
            LockedHiveScreen(
                hiveName = hiveDisplayName(targetHive.number),
                message = lockedHiveAccessMessage(hiveDisplayName(targetHive.number)),
                canUpgrade = canUpgradeLockedHive(),
                onBack = {
                    stopFastAssignmentForLockedHive()
                    screen = Screen.HOME
                },
                onUpgrade = {
                    stopFastAssignmentForLockedHive()
                    openLockedHiveUpgrade()
                }
            )
            return
        }
        if (targetHive == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Preparing the next hive…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FastTagAssignmentScreen(
                activity = activity,
                hive = targetHive,
                nfc = nfc,
                ignoredUid = fastAssignmentIgnoredUid,
                isTagAssignedElsewhere = { targetHiveId, uid ->
                    nfc.isTagAssignedElsewhere(targetHiveId, uid)
                },
                checkHiveSkipReason = { hiveId ->
                    val latestHive = vm.getHivesSnapshot().firstOrNull { it.id == hiveId }
                    // Refresh the writer's ownership cache from Room immediately before
                    // each write attempt. Do not let an older in-memory hive/tag snapshot
                    // classify a released UID as already assigned.
                    val knownAssignments = vm.getAllKnownNfcTagAssignments()
                    val currentIndex = NfcUidIndex.fromAssignments(knownAssignments)
                    nfc.updateKnownHiveTags(
                        hiveIdByTagUid = currentIndex.uniqueHiveIdByUid,
                        ready = ready,
                        ambiguousTagUids = currentIndex.ambiguousUids
                    )
                    val assignedHiveIds = knownAssignments
                        .filter { !it.first.isNullOrBlank() }
                        .map { it.second }
                        .toSet()
                    when {
                        latestHive == null -> "it no longer exists in the active hive records"
                        latestHive.isDead -> "the colony is marked dead"
                        !latestHive.tagUid.isNullOrBlank() || hiveId in assignedHiveIds ->
                            "it already has an NFC tag assigned"
                        else -> null
                    }
                },
                onHiveSkipped = { hiveId, reason -> skipFastAssignmentHive(hiveId, reason) },
                onAssignTag = { hiveId, uid, onResult ->
                    vm.assignTagToHive(hiveId, uid) { saved, error ->
                        if (!saved) {
                            // A failed database assignment must never trigger the success ding.
                            onResult(false, error)
                        } else {
                            // Verify the saved UID has one current owner in Room before the
                            // screen is allowed to announce success or play confirmation feedback.
                            scope.launch {
                                val normalizedUid = NfcUid.normalize(uid)
                                val assignments = runCatching {
                                    vm.getAllKnownNfcTagAssignments()
                                }.getOrNull()
                                if (normalizedUid == null || assignments == null) {
                                    onResult(
                                        false,
                                        "BeeKeep could not verify that the tag assignment was saved. Check the hive’s tag status before trying again."
                                    )
                                } else {
                                    val index = NfcUidIndex.fromAssignments(assignments)
                                    nfc.updateKnownHiveTags(
                                        hiveIdByTagUid = index.uniqueHiveIdByUid,
                                        ready = ready,
                                        ambiguousTagUids = index.ambiguousUids
                                    )
                                    val verifiedOwner = index.uniqueHiveIdByUid[normalizedUid]
                                    if (normalizedUid in index.ambiguousUids || verifiedOwner != hiveId) {
                                        onResult(
                                            false,
                                            "BeeKeep could not confirm that this tag is assigned to ${hiveReferenceName(hives.firstOrNull { it.id == hiveId }?.number ?: hiveId.toString())}. Check the hive’s tag status before scanning again."
                                        )
                                    } else {
                                        onResult(true, null)
                                    }
                                }
                            }
                        }
                    }
                },
                onAssignmentSaved = { assignedUid ->
                    advanceToNextUntaggedHiveForFastAssignment(targetHive, assignedUid)
                },
                onCancel = {
                    nfc.stop(activity)
                    nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
                    (activity as? MainActivity)?.pendingNfcResult?.value = null
                    fastAssignmentSessionActive = false
                    fastAssignmentScreenActive = false
                    fastAssignmentCurrentHiveId = null
                    fastAssignmentApiaryName = null
                    fastAssignmentIgnoredUid = null
                    fastAssignmentAssignedHiveIds = emptySet()
                    pendingAutoTagWriteHiveId = null
                    pendingAutoTagWriteIgnoredUid = null
                    pendingInspectionHiveId = null
                    pendingAutoStartVoiceHiveId = null
                    selectedHiveOpen = false
                    inspecting = false
                    vm.clearHive()
                    scope.launch { snackbarHostState.showSnackbar("Fast tag assignment stopped.") }
                }
            )
        }
        return
    }

    // Defense in depth: no callback or already-pending state may render Inspection
    // while the Fast Tag Assignment preference is enabled.
    if (inspecting && !fastTagAssignmentMode && fastAssignmentStateHydrated) {
        val hiveForInspection = selected ?: run { inspecting = false; return }
        if (hiveForInspection.id in lockedHiveIds) {
            LockedHiveScreen(
                hiveName = hiveDisplayName(hiveForInspection.number),
                message = lockedHiveAccessMessage(hiveDisplayName(hiveForInspection.number)),
                canUpgrade = canUpgradeLockedHive(),
                onBack = {
                    inspecting = false
                    selectedHiveOpen = false
                    pendingAutoStartVoiceHiveId = null
                    pendingInspectionHiveId = null
                    vm.clearHive()
                },
                onUpgrade = { openLockedHiveUpgrade() }
            )
            return
        }
        InspectionScreen(
            activity, locationController, hiveForInspection,
            viewModel = vm,
            workspaceId = activeWorkspaceId,
            defaultMiteSampleSize = autoVoicePrefs.getInt("default_mite_sample_size", 300).coerceIn(1, 10000),
            defaultStartingStrength = autoVoicePrefs.getInt("default_starting_strength", 3).coerceIn(1, 5),
            rememberLastInspectionValues = autoVoicePrefs.getBoolean("remember_last_inspection_values", true),
            voiceReadyBeepEnabled = autoVoicePrefs.getBoolean("voice_ready_beep", true),
            autoStartVoice = pendingAutoStartVoiceHiveId == hiveForInspection.id,
            autoVoiceEnabled = autoVoiceEnabled,
            isReadOnlyWorkspace = activeWorkspaceReadOnly,
            onViewOnlyAttempt = {
                if (requestHiveAccess(hiveForInspection)) showViewOnlyDialog = true
            },
            onVoiceAutoStartConsumed = {
                if (pendingAutoStartVoiceHiveId == hiveForInspection.id) pendingAutoStartVoiceHiveId = null
            },
            onSaveHiveLocation = { hiveId, latitude, longitude, onResult ->
                if (requestHiveAccessById(hiveId)) {
                    vm.updateHiveLocation(hiveId, latitude, longitude, onResult)
                } else {
                    onResult("This hive is locked because its paid subscription has ended. Renew a paid plan to edit it.")
                }
            },
            onRecordBeeCountChange = { hiveId, previousFrames, newFrames ->
                if (requestHiveAccessById(hiveId)) {
                    vm.recordBeeCountChange(hiveId, previousFrames, newFrames)
                }
            },
            onBack = { pendingAutoStartVoiceHiveId = null; inspecting = false },
            onHome = {
                pendingAutoStartVoiceHiveId = null
                pendingInspectionHiveId = null
                inspecting = false
                selectedHiveOpen = false
                selectedApiaryName = null
                screen = Screen.HOME
                vm.clearHive()
            },
            onSave = { inspection ->
                if (requestHiveAccess(hiveForInspection)) {
                    val saved = vm.saveInspection(inspection)
                    if (saved) {
                        scope.launch { snackbarHostState.showSnackbar("Inspection saved") }
                    } else {
                        if (activeWorkspaceReadOnly) {
                            showViewOnlyDialog = true
                        } else {
                            scope.launch { snackbarHostState.showSnackbar("Could not save inspection. Your field screen is still open; try again.") }
                        }
                    }
                    saved
                } else {
                    false
                }
            },
            onSaved = {
                if (vm.selected.value?.id == hiveForInspection.id) {
                    if (pendingAutoStartVoiceHiveId == hiveForInspection.id) pendingAutoStartVoiceHiveId = null
                    inspecting = false
                }
            }
        )
        return
    }
    val currentLogType = logType
    val currentSelected = selected
    if (currentLogType != null && currentSelected != null) {
        if (currentSelected.id in lockedHiveIds) {
            LockedHiveScreen(
                hiveName = hiveDisplayName(currentSelected.number),
                message = lockedHiveAccessMessage(hiveDisplayName(currentSelected.number)),
                canUpgrade = canUpgradeLockedHive(),
                onBack = {
                    logType = null
                    selectedHiveOpen = false
                    inspecting = false
                    vm.clearHive()
                },
                onUpgrade = {
                    logType = null
                    openLockedHiveUpgrade()
                }
            )
            return
        }
        when (currentLogType) {
            HiveLogType.FEED -> FeedDialog(
                onDismiss = { logType = null },
                hiveName = hiveDisplayName(currentSelected.number),
                records = feedings,
                recordsLoading = feedingsLoading,
                recordsError = feedingsError
            ) { type, ratio, amount, unit, notes ->
                if (requestHiveAccess(currentSelected)) {
                    vm.saveFeeding(type, ratio, amount, unit, notes) { success, error ->
                        if (success) logType = null
                        scope.launch { snackbarHostState.showSnackbar(error ?: "Feeding logged") }
                    }
                } else {
                    logType = null
                }
            }
            HiveLogType.TREAT -> TreatmentDialog(
                onDismiss = { logType = null },
                hiveName = hiveDisplayName(currentSelected.number),
                records = treatments,
                recordsLoading = treatmentsLoading,
                recordsError = treatmentsError
            ) { type, product, days, withdrawal, notes ->
                if (requestHiveAccess(currentSelected)) {
                    vm.saveTreatment(type, product, days, withdrawal, notes) { success, error ->
                        if (success) logType = null
                        scope.launch { snackbarHostState.showSnackbar(error ?: "Treatment logged") }
                    }
                } else {
                    logType = null
                }
            }
            HiveLogType.HARVEST -> HarvestDialog(
                onDismiss = { logType = null },
                hiveName = hiveDisplayName(currentSelected.number),
                records = harvests,
                recordsLoading = harvestsLoading,
                recordsError = harvestsError
            ) { supers, wet, dry, unit, wax, propolis, notes ->
                if (requestHiveAccess(currentSelected)) {
                    vm.saveHarvest(supers, wet, dry, unit, wax, propolis, notes) { success, error ->
                        if (success) logType = null
                        scope.launch { snackbarHostState.showSnackbar(error ?: "Harvest logged") }
                    }
                } else {
                    logType = null
                }
            }
        }
        return
    }
    // Keep fast assignment semantics for the entire run, even if the preference
    // changes or recomposes while a tag write is already in flight.
    val fastTagAssignmentActive = fastTagAssignmentMode || fastAssignmentSessionActive

    if (selectedHiveOpen) {
        val hiveForDetail = selected
        if (hiveForDetail == null) {
            HiveDetailLoadingScreen()
            return
        }
        if (hiveForDetail.id in lockedHiveIds) {
            LockedHiveScreen(
                hiveName = hiveDisplayName(hiveForDetail.number),
                message = lockedHiveAccessMessage(hiveDisplayName(hiveForDetail.number)),
                canUpgrade = canUpgradeLockedHive(),
                onBack = {
                    selectedHiveOpen = false
                    pendingAutoStartVoiceHiveId = null
                    pendingInspectionHiveId = null
                    pendingAutoTagWriteHiveId = null
                    pendingAutoTagWriteIgnoredUid = null
                    inspecting = false
                    vm.clearHive()
                },
                onUpgrade = { openLockedHiveUpgrade() }
            )
            return
        }
        val hivesInThisApiary = hives.filter {
            it.apiary.equals(hiveForDetail.apiary, ignoreCase = true) && !it.isDead
        }
        // Use the same list order as the Apiary Hives screen, so NEXT follows the
        // order the beekeeper sees in that apiary instead of jumping to another yard.
        val currentApiaryHiveIndex = hivesInThisApiary.indexOfFirst { it.id == hiveForDetail.id }
        val nextHiveInApiary = currentApiaryHiveIndex
            .takeIf { it >= 0 }
            ?.let { hivesInThisApiary.getOrNull(it + 1) }
        HiveDetailScreen(
            hive = hiveForDetail,
            viewModel = vm,
            fastTagAssignmentMode = fastTagAssignmentActive,
            nfcEntitled = nfcCanScan,
            nfcCanManage = nfcCanManage,
            onUpgradeNfc = {
                if (!nfcAccessResolved) {
                    scope.launch { snackbarHostState.showSnackbar("Checking NFC access for the selected data scope…") }
                } else if (isPersonalDataset) {
                    purchaseBackScreen = Screen.HOME
                    purchaseSuccessScreen = Screen.HOME
                    screen = Screen.PURCHASES
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "The workspace owner must renew Commercial to unlock NFC for this workspace."
                        )
                    }
                }
            },
            autoStartTagWriting = pendingAutoTagWriteHiveId == hiveForDetail.id,
            autoStartTagWritingIgnoreUid = if (pendingAutoTagWriteHiveId == hiveForDetail.id) pendingAutoTagWriteIgnoredUid else null,
            onAutoTagWritingStarted = {
                if (pendingAutoTagWriteHiveId == hiveForDetail.id) {
                    pendingAutoTagWriteHiveId = null
                    pendingAutoTagWriteIgnoredUid = null
                }
            },
            onFastTagAssignmentStarted = {
                (activity as? MainActivity)?.stopHiveAnnouncements()
                // The initial write starts on the selected hive. Once it succeeds,
                // subsequent hives are handled by FastTagAssignmentScreen without
                // reopening their detail pages.
                fastAssignmentSessionActive = true
                fastAssignmentApiaryName = hiveForDetail.apiary
                fastAssignmentAssignedHiveIds = emptySet()
                fastAssignmentCurrentHiveId = hiveForDetail.id
                fastAssignmentIgnoredUid = null
                fastAssignmentScreenActive = false
                (activity as? MainActivity)?.pendingNfcResult?.value = null
                pendingInspectionHiveId = null
                pendingAutoStartVoiceHiveId = null
                inspecting = false
            },
            onFastTagAssignmentSuccess = { assignedUid ->
                advanceToNextUntaggedHiveForFastAssignment(hiveForDetail, assignedUid)
            },
            onFastTagAssignmentSkip = { skippedHiveId, reason ->
                skipFastAssignmentHive(skippedHiveId, reason)
            },
            nextHiveNumber = nextHiveInApiary?.number,
            onNextHive = {
                nextHiveInApiary?.let { nextHive ->
                    if (requestHiveAccess(nextHive)) {
                        lastHiveTagSuccessHiveId = null
                        lastHiveTagSuccessUid = null
                        vm.openHive(nextHive.id)
                    }
                }
            },
            nfc = nfc,
            activity = activity,
            onBack = {
                nfc.stop(activity)
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                pendingAutoTagWriteHiveId = null
                pendingAutoTagWriteIgnoredUid = null
                pendingInspectionHiveId = null
                pendingAutoStartVoiceHiveId = null
                selectedHiveOpen = false
                vm.clearHive()
            },
            onHome = {
                nfc.stop(activity)
                nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
                fastAssignmentSessionActive = false
                fastAssignmentScreenActive = false
                fastAssignmentCurrentHiveId = null
                fastAssignmentApiaryName = null
                fastAssignmentIgnoredUid = null
                fastAssignmentAssignedHiveIds = emptySet()
                pendingAutoTagWriteHiveId = null
                pendingAutoTagWriteIgnoredUid = null
                pendingAutoStartVoiceHiveId = null
                pendingInspectionHiveId = null
                inspecting = false
                selectedHiveOpen = false
                selectedApiaryName = null
                screen = Screen.HOME
                vm.clearHive()
            },
            onInspect = {
                if (fastTagAssignmentMode) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Turn off Fast Tag Assignment in Settings before opening Inspection."
                        )
                    }
                } else {
                    // A deliberate Inspect tap ends the automatic assignment sequence.
                    nfc.stop(activity)
                    nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
                    fastAssignmentSessionActive = false
                    fastAssignmentScreenActive = false
                    fastAssignmentCurrentHiveId = null
                    fastAssignmentApiaryName = null
                    fastAssignmentIgnoredUid = null
                    fastAssignmentAssignedHiveIds = emptySet()
                    pendingAutoTagWriteHiveId = null
                    pendingAutoTagWriteIgnoredUid = null
                    pendingAutoStartVoiceHiveId = null
                    pendingInspectionHiveId = null
                    (activity as? MainActivity)?.pendingNfcResult?.value = null
                    inspecting = true
                }
            },
            onFeed = { if (requestHiveAccess(hiveForDetail)) logType = HiveLogType.FEED },
            onTreat = { if (requestHiveAccess(hiveForDetail)) logType = HiveLogType.TREAT },
            onHarvest = { if (requestHiveAccess(hiveForDetail)) logType = HiveLogType.HARVEST },
            onTag = { uid, onResult ->
                when {
                    !nfcCanManage -> onResult(
                        false,
                        if (activeWorkspaceId.isNullOrBlank()) {
                            "NFC tag assignment requires BeeKeep Commercial."
                        } else {
                            "Your workspace role does not allow NFC tag changes, or the workspace owner's Commercial access could not be verified."
                        }
                    )
                    requestHiveAccess(hiveForDetail) -> vm.assignTag(uid, onResult)
                    else -> onResult(false, "This hive is locked because its paid subscription has ended. Renew a paid plan to manage it.")
                }
            },
            isTagAssignedElsewhere = { targetHiveId, uid ->
                nfc.isTagAssignedElsewhere(targetHiveId, uid)
            },
            onClearTag = { hiveId, onResult ->
                when {
                    !nfcCanManage -> onResult(
                        false,
                        if (activeWorkspaceId.isNullOrBlank()) {
                            "NFC tag management requires BeeKeep Commercial."
                        } else {
                            "Your workspace role does not allow NFC tag changes, or the workspace owner's Commercial access could not be verified."
                        }
                    )
                    requestHiveAccessById(hiveId) -> vm.clearTagForHive(hiveId, onResult)
                    else -> onResult(false, "This hive is locked because its paid subscription has ended. Renew a paid plan to manage its NFC tag.")
                }
            },
            confirmBeforeTagRemoval = autoVoicePrefs.getBoolean("confirm_tag_removal", true),
            tagAssignmentSuccessUid = if (lastHiveTagSuccessHiveId == hiveForDetail.id) lastHiveTagSuccessUid else null,
            onVerifyTag = {
                if (!nfcAccessResolved) {
                    scope.launch { snackbarHostState.showSnackbar("Checking NFC access for the selected data scope…") }
                } else if (!nfcCanScan) {
                    if (isPersonalDataset) {
                        purchaseBackScreen = Screen.HOME
                        purchaseSuccessScreen = Screen.HOME
                        screen = Screen.PURCHASES
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "The workspace owner must renew Commercial to unlock NFC in this workspace."
                            )
                        }
                    }
                } else nfc.startRead(activity) { result ->
                    when (result) {
                        is NfcResult.Read -> {
                            val matchesUid = NfcUid.matches(result.uid, hiveForDetail.tagUid)
                            val currentMarker = BeeKeepNfcPayload.isTagPayload(result.text) && result.info.hasBeeKeepLaunchRecord
                            scope.launch {
                                val ambiguous = vm.isNfcTagAmbiguous(result.uid)
                                snackbarHostState.showSnackbar(
                                    when {
                                        ambiguous -> "IDENTITY CONFLICT • this NFC UID is linked to multiple hives. It cannot be verified until the duplicate is repaired."
                                        !matchesUid -> "Tag read • ${result.uid} • does not match this hive"
                                        currentMarker -> "NFC tag verified • UID matches this hive and the BeeKeep launch marker is present"
                                        BeeKeepNfcPayload.hiveId(result.text) != null -> "UID matches, but this tag still has old hive data. Scan it in BeeKeep to upgrade it."
                                        else -> "UID matches, but the BeeKeep app-launch marker is missing. Use WRITE TAG to repair it."
                                    }
                                )
                            }
                        }
                        is NfcResult.Error -> scope.launch { snackbarHostState.showSnackbar(result.message) }
                        is NfcResult.Written -> Unit
                    }
                }
            },
            onMarkDead = {
                if (requestHiveAccess(hiveForDetail)) {
                    vm.markHiveDead(hiveForDetail.id) { _, error ->
                        scope.launch { snackbarHostState.showSnackbar(error ?: "${hiveReferenceName(hiveForDetail.number)} marked dead. History preserved.") }
                    }
                }
            },
            onRestore = {
                if (requestHiveAccess(hiveForDetail)) {
                    vm.restoreHive(hiveForDetail.id) { _, error ->
                        scope.launch { snackbarHostState.showSnackbar(error ?: "${hiveReferenceName(hiveForDetail.number)} restored to active.") }
                    }
                }
            },
            onDelete = {
                if (requestHiveAccess(hiveForDetail)) {
                    vm.deleteHivePermanently(hiveForDetail.id) { success, error ->
                        if (success) {
                            clearInspectionDraftsForHive(activity, hiveForDetail.id)
                            selectedHiveOpen = false
                            vm.clearHive()
                        }
                        scope.launch { snackbarHostState.showSnackbar(error ?: "${hiveReferenceName(hiveForDetail.number)} permanently deleted.") }
                    }
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
            onScan = { startManualNfcScan() }
        )
        return
    }
    if (screen == Screen.PREMADE_SETUP) {
        val isAddingToExistingWorkspace = addingApiariesWithHives
        PreMadeHiveSetupScreen(
            vm = vm,
            existingApiaryNames = if (isAddingToExistingWorkspace) apiaries.map { it.name } else emptyList(),
            existingHiveCount = if (isAddingToExistingWorkspace) {
                BeeKeepEntitlements.countHivesSubjectToFreeLimit(
                    (hives + deadHives).map { it.id },
                    lockedHiveIds
                )
            } else 0,
            addToExistingWorkspace = isAddingToExistingWorkspace,
            hiveLimit = activeQuotaLimits.hiveLimit,
            apiaryLimit = activeQuotaLimits.apiaryLimit,
            currentPlan = activeQuotaLimits.plan,
            quotaEnforcementReady = quotaEnforcementReady,
            onBack = {
                if (isAddingToExistingWorkspace) {
                    addingApiariesWithHives = false
                    screen = Screen.APIARIES
                } else {
                    screen = Screen.HOME
                }
            },
            onUpgrade = {
                purchaseBackScreen = Screen.PREMADE_SETUP
                purchaseSuccessScreen = Screen.PREMADE_SETUP
                screen = Screen.PURCHASES
            },
            onComplete = { createdApiaries, createdHives ->
                addingApiariesWithHives = false
                screen = if (isAddingToExistingWorkspace) Screen.APIARIES else Screen.HOME
                scope.launch {
                    val message = if (isAddingToExistingWorkspace) {
                        "Added $createdApiaries apiaries and $createdHives hives."
                    } else {
                        "Created $createdApiaries apiaries and $createdHives hives."
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }
        )
        return
    }
    if (screen == Screen.APIARY_HIVES) {
        val apiaryName = selectedApiaryName
        if (apiaryName == null) {
            clearApiaryBatchAction()
            screen = Screen.APIARIES
            return
        }
        val apiaryHives = hives.filter { it.apiary.equals(apiaryName, ignoreCase = true) }
        Box(Modifier.fillMaxSize()) {
            screenSaveableStateHolder.SaveableStateProvider("apiary-hives:$apiaryName") {
                ApiaryHivesScreen(
                    apiaryName = apiaryName,
                    hives = apiaryHives,
                    compactHiveRows = compactHiveRows,
                    lockedHiveIds = lockedHiveIds,
                    padding = androidx.compose.foundation.layout.PaddingValues(),
                    onBack = {
                        clearApiaryBatchAction()
                        selectedApiaryName = null
                        screen = Screen.APIARIES
                    },
                    onAddHive = { addHive = true },
                    onOpenHive = { hiveId ->
                        if (requestHiveAccessById(hiveId)) {
                            val targetHive = hives.firstOrNull { it.id == hiveId }
                            if (fastTagAssignmentMode && targetHive != null) {
                                startFastTagAssignmentFromHive(targetHive)
                            } else {
                                vm.openHive(hiveId)
                                selectedHiveOpen = true
                            }
                        }
                    },
                    onApplyToApiary = {
                        when {
                            activeWorkspaceReadOnly -> showViewOnlyDialog = true
                            !quotaEnforcementReady -> showQuotaVerificationMessage()
                            else -> apiaryBatchTargetPickerVisible = true
                        }
                    }
                )
            }

            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (apiaryBatchTargetPickerVisible) {
                ApiaryBatchActionTargetDialog(
                    apiaryName = apiaryName,
                    hives = apiaryHives,
                    lockedHiveIds = lockedHiveIds,
                    onDismiss = { apiaryBatchTargetPickerVisible = false },
                    onContinue = { actionType, selectedHiveIds ->
                        val apiary = apiaries.firstOrNull {
                            it.name.equals(apiaryName, ignoreCase = true)
                        }
                        if (apiary == null) {
                            apiaryBatchTargetPickerVisible = false
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "This apiary could not be found. Refresh and try again."
                                )
                            }
                        } else if (activeWorkspaceReadOnly) {
                            apiaryBatchTargetPickerVisible = false
                            showViewOnlyDialog = true
                        } else if (!quotaEnforcementReady) {
                            apiaryBatchTargetPickerVisible = false
                            showQuotaVerificationMessage()
                        } else if (selectedHiveIds.any { !requestHiveAccessById(it) }) {
                            apiaryBatchTargetPickerVisible = false
                        } else {
                            apiaryBatchActionTarget = ApiaryBatchActionTarget(
                                apiaryId = apiary.id,
                                apiaryName = apiary.name,
                                hiveIds = selectedHiveIds
                            )
                            apiaryBatchActionType = actionType
                            apiaryBatchTargetPickerVisible = false
                        }
                    }
                )
            }

            val batchTarget = apiaryBatchActionTarget
            if (batchTarget != null) {
                val targetSummary = "${batchTarget.apiaryName} • ${batchTarget.hiveIds.size} hives"
                when (apiaryBatchActionType) {
                    HiveLogType.FEED -> FeedDialog(
                        onDismiss = { clearApiaryBatchAction() },
                        hiveName = targetSummary,
                        showHistory = false,
                        entryInfoMessage = "The amount entered will be recorded separately for every selected hive."
                    ) { feedType, ratio, amount, unit, notes ->
                        if (!apiaryBatchSubmitting) {
                            requestApiaryBatchConfirmation(
                                "Apply this feeding to ${batchTarget.hiveIds.size} selected hives in ${batchTarget.apiaryName}? Each hive will receive the full amount entered."
                            ) {
                                if (activeWorkspaceReadOnly) {
                                    cancelApiaryBatchConfirmation()
                                    showViewOnlyDialog = true
                                } else if (!quotaEnforcementReady) {
                                    cancelApiaryBatchConfirmation()
                                    showQuotaVerificationMessage()
                                } else if (batchTarget.hiveIds.any { !requestHiveAccessById(it) }) {
                                    cancelApiaryBatchConfirmation()
                                } else {
vm.saveFeedingToApiary(
                                    batchTarget.apiaryId,
                                    batchTarget.apiaryName,
                                    batchTarget.hiveIds,
                                    feedType,
                                    ratio,
                                    amount,
                                    unit,
                                    notes
                                ) { success, message ->
                                    apiaryBatchSubmitting = false
                                    if (success) clearApiaryBatchAction()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message ?: "Feeding saved.")
                                    }
                                }
                                }
                            }
                        }
                    }

                    HiveLogType.TREAT -> TreatmentDialog(
                        onDismiss = { clearApiaryBatchAction() },
                        hiveName = targetSummary,
                        showHistory = false,
                        entryInfoMessage = "Use removal and withdrawal timing from the product label. BeeKeep records the dates; it does not determine a safe treatment schedule or create treatment alerts. The same treatment and label-based timing will be recorded on every selected hive."
                    ) { treatmentType, product, days, withdrawalDays, notes ->
                        if (!apiaryBatchSubmitting) {
                            requestApiaryBatchConfirmation(
                                "Apply this treatment to ${batchTarget.hiveIds.size} selected hives in ${batchTarget.apiaryName}? A separate treatment record will be created for each hive."
                            ) {
                                if (activeWorkspaceReadOnly) {
                                    cancelApiaryBatchConfirmation()
                                    showViewOnlyDialog = true
                                } else if (!quotaEnforcementReady) {
                                    cancelApiaryBatchConfirmation()
                                    showQuotaVerificationMessage()
                                } else if (batchTarget.hiveIds.any { !requestHiveAccessById(it) }) {
                                    cancelApiaryBatchConfirmation()
                                } else {
vm.saveTreatmentToApiary(
                                    batchTarget.apiaryId,
                                    batchTarget.apiaryName,
                                    batchTarget.hiveIds,
                                    treatmentType,
                                    product,
                                    days,
                                    withdrawalDays,
                                    notes
                                ) { success, message ->
                                    apiaryBatchSubmitting = false
                                    if (success) clearApiaryBatchAction()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(message ?: "Treatment saved.")
                                    }
                                }
                                }
                            }
                        }
                    }

                    HiveLogType.HARVEST -> {
                        val quantityDescription = when (currentHarvestQuantityMode) {
                            HarvestQuantityMode.PER_HIVE ->
                                "The full quantities entered will be recorded on each selected hive."
                            HarvestQuantityMode.APIARY_TOTAL ->
                                "These are apiary totals for the selected hives. BeeKeep splits supers and each weight as evenly as possible; any remainder goes to earlier hives in hive-number order."
                        }
                        HarvestDialog(
                            onDismiss = { clearApiaryBatchAction() },
                            hiveName = targetSummary,
                            showHistory = false,
                            entryInfoMessage = quantityDescription
                        ) { supers, wet, dry, unit, wax, propolis, notes ->
                            if (!apiaryBatchSubmitting) {
                                val confirmText = when (currentHarvestQuantityMode) {
                                    HarvestQuantityMode.PER_HIVE ->
                                        "Apply this harvest to ${batchTarget.hiveIds.size} selected hives in ${batchTarget.apiaryName}? Each hive will receive the full quantities entered."
                                    HarvestQuantityMode.APIARY_TOTAL ->
                                        "Apply these apiary-total quantities across ${batchTarget.hiveIds.size} selected hives? Supers are split as whole numbers; weight remainders go to earlier hives in hive-number order."
                                }
                                requestApiaryBatchConfirmation(confirmText) {
                                    if (activeWorkspaceReadOnly) {
                                    cancelApiaryBatchConfirmation()
                                    showViewOnlyDialog = true
                                } else if (!quotaEnforcementReady) {
                                    cancelApiaryBatchConfirmation()
                                    showQuotaVerificationMessage()
                                } else if (batchTarget.hiveIds.any { !requestHiveAccessById(it) }) {
                                    cancelApiaryBatchConfirmation()
                                } else {
vm.saveHarvestToApiary(
                                        batchTarget.apiaryId,
                                        batchTarget.apiaryName,
                                        batchTarget.hiveIds,
                                        supers,
                                        wet,
                                        dry,
                                        unit,
                                        wax,
                                        propolis,
                                        notes,
                                        currentHarvestQuantityMode
                                    ) { success, message ->
                                        apiaryBatchSubmitting = false
                                        if (success) clearApiaryBatchAction()
                                        scope.launch {
                                            snackbarHostState.showSnackbar(message ?: "Harvest saved.")
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }

                    null -> Unit
                }
            }

            if (apiaryBatchSubmitting && pendingApiaryBatchCommit != null) {
                AlertDialog(
                    onDismissRequest = { cancelApiaryBatchConfirmation() },
                    title = { Text("Apply to selected hives?", fontWeight = FontWeight.ExtraBold) },
                    text = { Text(apiaryBatchConfirmationMessage) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val commit = pendingApiaryBatchCommit
                                pendingApiaryBatchCommit = null
                                apiaryBatchConfirmationMessage = ""
                                commit?.invoke()
                            }
                        ) { Text("APPLY", fontWeight = FontWeight.ExtraBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { cancelApiaryBatchConfirmation() }) { Text("CANCEL") }
                    }
                )
            }
        }
        return
    }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
            NavigationBar(
                modifier = Modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                containerColor = if (darkMode) NavBarDark else NavBarLight,
                contentColor = OnNavBar,
                tonalElevation = 0.dp
            ) {
                NavItem("Home", Icons.Rounded.Home, screen == Screen.HOME || screen == Screen.HIVES) { screen = Screen.HOME }
                NavItem("Apiaries", Icons.Rounded.Yard, screen == Screen.APIARIES || screen == Screen.APIARY_HIVES) { selectedApiaryName = null; screen = Screen.APIARIES }
                NavItem("Map", Icons.Rounded.Map, screen == Screen.MAP) {
                    mapOrigin = Screen.HOME
                    mapFocusApiaryId = null
                    screen = Screen.MAP
                }
                if (showWorkspaceDestination) {
                    NavItem("Workspace", Icons.Rounded.Groups, screen == Screen.WORKSPACE) {
                        screen = Screen.WORKSPACE
                    }
                }
                NavItem("Settings", Icons.Rounded.Settings, screen == Screen.MORE || screen == Screen.SETTINGS) { screen = Screen.SETTINGS }
            }
            }
        }
    ) { padding ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                hiveCount = hives.size,
                apiaryCount = apiaries.size,
                padding = padding,
                nfcEnabled = nfcEnabledForIndicator,
                currentPlan = purchasePlan,
                onOpenApiaries = {
                    selectedApiaryName = null
                    screen = Screen.APIARIES
                }
            )
            Screen.HIVES -> screenSaveableStateHolder.SaveableStateProvider("all-hives") {
                AllHivesScreen(
                hives = hives,
                compactHiveRows = compactHiveRows,
                lockedHiveIds = lockedHiveIds,
                padding = padding,
                onBack = { screen = Screen.HOME },
                onAddHive = { selectedApiaryName = null; addHive = true },
                onOpenHive = { hiveId ->
                    if (requestHiveAccessById(hiveId)) {
                        val targetHive = hives.firstOrNull { it.id == hiveId }
                        if (fastTagAssignmentMode && targetHive != null) {
                            startFastTagAssignmentFromHive(targetHive)
                        } else {
                            vm.openHive(hiveId)
                            selectedHiveOpen = true
                        }
                    }
                }
                )
            }
            Screen.APIARIES -> ApiariesScreen(
                apiaries, apiaryHiveCounts, padding,
                onAddApiary = { addApiaryOptionsVisible = true },
                onEditApiary = { editingApiary = it },
                onOpenMap = { apiary ->
                    mapOrigin = Screen.APIARIES
                    mapFocusApiaryId = apiary.id
                    screen = Screen.MAP
                },
                onOpenApiary = { selectedApiaryName = it; screen = Screen.APIARY_HIVES }
            )
            Screen.MAP -> ApiaryMapScreen(
                apiaries = apiaries,
                padding = padding,
                initialFocusApiaryId = mapFocusApiaryId,
                locationController = locationController,
                onBack = { screen = mapOrigin; mapFocusApiaryId = null }
            )
            Screen.WORKSPACE -> WorkspaceScreen(
                padding = padding,
                activity = activity,
                cloud = cloud,
                currentPlan = purchasePlan,
                hasCommercialAccess = hasCommercialWorkspaceAccess,
                activeWorkspaceId = activeWorkspaceId,
                hiveCount = hives.size,
                apiaryCount = apiaries.size,
                onUpgrade = {
                    purchaseBackScreen = Screen.WORKSPACE
                    purchaseSuccessScreen = Screen.WORKSPACE
                    screen = Screen.PURCHASES
                },
                onWorkspaceMembershipChanged = { joined ->
                    hasJoinedTeamWorkspace = joined
                    if (!joined && activeWorkspaceId.isNullOrBlank()) {
                        autoVoicePrefs.edit { remove("selected_team_workspace_id") }
                        if (!hasCommercialWorkspaceAccess && screen == Screen.WORKSPACE) {
                            screen = Screen.MORE
                        }
                    }
                }
            )
            Screen.MORE -> MoreScreen(
                padding = padding,
                onBack = { screen = Screen.SETTINGS },
                onWorkspaceJoined = { joinedMessage ->
                    hasJoinedTeamWorkspace = true
                    screen = Screen.WORKSPACE
                    scope.launch { snackbarHostState.showSnackbar(joinedMessage) }
                },
                onTagManager = {
                    if (!nfcAccessResolved) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (isPersonalDataset) "Checking your BeeKeep plan…" else "Checking the workspace owner's Commercial access…"
                            )
                        }
                    } else if (nfcCanScan) {
                        tagManagerReturnScreen = Screen.MORE
                        screen = Screen.TAG_MANAGER
                    } else if (isPersonalDataset) {
                        purchaseBackScreen = Screen.MORE
                        purchaseSuccessScreen = Screen.MORE
                        screen = Screen.PURCHASES
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "NFC is locked in this workspace until its owner has active Commercial access."
                            )
                        }
                    }
                },
                onColonyHistory = { screen = Screen.COLONY_HISTORY },
                onUpgrade = {
                    purchaseBackScreen = Screen.MORE
                    purchaseSuccessScreen = Screen.HOME
                    screen = Screen.PURCHASES
                },
                currentPlan = purchasePlan,
                deadCount = deadHives.size,
                onOpenSupportAdmin = { screen = Screen.SUPPORT_ADMIN },
                activity = activity,
                cloud = cloud
            )
            Screen.SUPPORT_ADMIN -> AdminHiveLimitScreen(
                padding = padding,
                cloud = cloud,
                onBack = { screen = Screen.MORE }
            )
            Screen.SETTINGS -> SettingsScreen(
                padding = padding,
                activity = activity,
                repository = repository,
                preferences = autoVoicePrefs,
                darkMode = darkMode,
                onDarkModeChange = onDarkModeChange,
                autoVoiceEnabled = autoVoiceEnabled,
                onAutoVoiceEnabledChange = { enabled ->
                    autoVoiceEnabled = enabled
                    autoVoicePrefs.edit { putBoolean("auto_voice_on_nfc", enabled) }
                },
                fastTagAssignmentMode = fastTagAssignmentMode,
                nfcEntitled = nfcCanScan,
                nfcCanManage = nfcCanManage,
                hiveLimit = activeQuotaLimits.hiveLimit,
                apiaryLimit = activeQuotaLimits.apiaryLimit,
                planName = activeQuotaLimits.plan.displayName,
                quotaEnforcementReady = quotaEnforcementReady,
                onFastTagAssignmentModeChange = { enabled ->
                    if (enabled && !nfcAccessResolved) {
                        scope.launch { snackbarHostState.showSnackbar("Checking NFC access for the selected data scope…") }
                    } else if (enabled && !nfcCanScan) {
                        if (isPersonalDataset) {
                            purchaseBackScreen = Screen.SETTINGS
                            purchaseSuccessScreen = Screen.SETTINGS
                            screen = Screen.PURCHASES
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "NFC is locked until the workspace owner has active Commercial access."
                                )
                            }
                        }
                    } else if (enabled && !nfcCanManage) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Fast Tag Assignment requires an Owner, Admin or Member workspace role."
                            )
                        }
                    } else {
                    fastTagAssignmentMode = enabled
                    autoVoicePrefs.edit { putBoolean("fast_tag_assignment_mode", enabled) }
                    if (enabled) {
                        (activity as? MainActivity)?.stopHiveAnnouncements()
                        nfc.setPassiveReadingEnabled(activity, false)
                    }
                    if (!enabled) {
                        fastAssignmentSessionActive = false
                        fastAssignmentScreenActive = false
                        fastAssignmentCurrentHiveId = null
                        fastAssignmentApiaryName = null
                        fastAssignmentIgnoredUid = null
                        fastAssignmentAssignedHiveIds = emptySet()
                        pendingAutoTagWriteHiveId = null
                        pendingAutoTagWriteIgnoredUid = null
                        pendingInspectionHiveId = null
                        pendingAutoStartVoiceHiveId = null
                        (activity as? MainActivity)?.pendingNfcResult?.value = null
                        nfc.finishFastAssignmentFlow(activity, preserveLastUid = false)
                    }
                    }
                },
                largeFieldText = largeFieldText,
                onLargeFieldTextChange = onLargeFieldTextChange,
                compactHiveRows = compactHiveRows,
                onCompactHiveRowsChange = onCompactHiveRowsChange,
                cloud = cloud,
                onBack = { screen = Screen.HOME },
                onOpenMore = { screen = Screen.MORE },
                onOpenPlans = {
                    if (!commercialEntitlementResolved) {
                        scope.launch { snackbarHostState.showSnackbar("Checking your BeeKeep plan…") }
                    } else {
                        purchaseBackScreen = Screen.SETTINGS
                        purchaseSuccessScreen = Screen.SETTINGS
                        screen = Screen.PURCHASES
                    }
                },
                onResetApiaries = { warning ->
                    vm.clearHive()
                    nfc.stop(activity)
                    scanning = false
                    inspecting = false
                    selectedHiveOpen = false
                    selectedApiaryName = null
                    addApiary = false
                    addHive = false
                    editingApiary = null
                    pendingApiaryMove = null
                    pendingLocationHive = null
                    pendingTagUid = null
                    unassignedTagUid = null
                    pendingInspectionHiveId = null
                    pendingAutoStartVoiceHiveId = null
                    pendingResetWarning = warning
                    screen = Screen.HOME
                }
            )
            Screen.TAG_MANAGER -> TagManagementScreen(
                padding = padding,
                hives = hives,
                deadHives = deadHives,
                lockedHiveIds = lockedHiveIds,
                canManageTags = nfcCanManage,
                nfc = nfc,
                activity = activity,
                pendingUid = pendingTagUid,
                confirmBeforeTagRemoval = autoVoicePrefs.getBoolean("confirm_tag_removal", true),
                onPendingUidConsumed = { pendingTagUid = null },
                onBack = { screen = tagManagerReturnScreen },
                onOpenHive = { hiveId ->
                    if (requestHiveAccessById(hiveId)) {
                        vm.openHive(hiveId)
                        selectedHiveOpen = true
                        screen = Screen.HOME
                    }
                },
                onScanOpenHive = { hiveId ->
                    val targetHive = (hives + deadHives).firstOrNull { it.id == hiveId }
                    if (targetHive != null && !requestHiveAccess(targetHive)) {
                        screen = Screen.HOME
                        pendingAutoStartVoiceHiveId = null
                        pendingInspectionHiveId = null
                        inspecting = false
                    } else {
                        if (autoVoicePrefs.getBoolean("speak_hive_number_on_scan", true)) onSpeakHiveNumber(targetHive?.number.orEmpty())
                        vm.openHive(hiveId)
                        selectedHiveOpen = true
                        screen = Screen.HOME
                        pendingAutoStartVoiceHiveId = hiveId
                        pendingInspectionHiveId = hiveId
                    }
                },
                onAssignTag = { hiveId, uid, reassign, onResult ->
                    if (!nfcCanManage) {
                        onResult(false, "Your workspace role does not allow NFC tag changes.")
                    } else if (requestHiveAccessById(hiveId)) {
                        vm.assignTagToHive(hiveId, uid, reassign, onResult)
                    } else {
                        onResult(false, "This hive is locked because its paid subscription has ended. Renew a paid plan to manage its NFC tag.")
                    }
                },
                onClearTag = { hiveId, onResult ->
                    if (!nfcCanManage) {
                        onResult(false, "Your workspace role does not allow NFC tag changes.")
                    } else if (requestHiveAccessById(hiveId)) {
                        vm.clearTagForHive(hiveId, onResult)
                    } else {
                        onResult(false, "This hive is locked because its paid subscription has ended. Renew a paid plan to manage its NFC tag.")
                    }
                },
                onUnassignedTag = { uid ->
                    unassignedTagOrigin = Screen.TAG_MANAGER
                    unassignedTagUid = uid
                },
                onIsTagUnassigned = { uid -> vm.isNfcTagUnassigned(uid) },
                onIsTagAmbiguous = { uid -> vm.isNfcTagAmbiguous(uid) },
                onFindHiveForTag = { uid -> vm.findHiveByTag(uid) }
            )
            Screen.PURCHASES -> Unit
            Screen.COLONY_HISTORY -> ColonyHistoryScreen(
                deadHives = deadHives,
                lockedHiveIds = lockedHiveIds,
                padding = padding,
                onBack = { screen = Screen.MORE },
                onOpenHive = { hiveId ->
                    if (requestHiveAccessById(hiveId)) {
                        vm.openHive(hiveId)
                        selectedHiveOpen = true
                    }
                }
            )
            Screen.SCAN, Screen.APIARY_HIVES, Screen.PREMADE_SETUP -> Unit
        }
    }

    if (addApiaryOptionsVisible && screen == Screen.APIARIES) {
        AlertDialog(
            onDismissRequest = { addApiaryOptionsVisible = false },
            title = { Text("Add an Apiary", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How would you like to add your next apiary?")
                    Button(
                        onClick = {
                            addApiaryOptionsVisible = false
                            addingApiariesWithHives = true
                            screen = Screen.PREMADE_SETUP
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Add Apiary with Pre-made Hives") }
                    OutlinedButton(
                        onClick = {
                            addApiaryOptionsVisible = false
                            addApiary = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Create Empty Apiary") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addApiaryOptionsVisible = false }) { Text("Cancel") }
            }
        )
    }

}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = {
            Text(
                label,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (label == "Workspace") 9.sp else 10.sp,
                    letterSpacing = if (label == "Workspace") (-0.35).sp else 0.sp
                )
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Trail.Bark,
            selectedTextColor = OnNavBar,
            indicatorColor = NavIndicator,
            unselectedIconColor = OnNavBarMuted,
            unselectedTextColor = OnNavBarMuted
        )
    )
}

private data class HiveListSearchKey(
    val id: Long,
    val number: String,
    val apiary: String
)

@Composable
private fun AllHivesScreen(
    hives: List<Hive>,
    compactHiveRows: Boolean,
    lockedHiveIds: Set<Long>,
    padding: PaddingValues,
    onBack: () -> Unit,
    onAddHive: () -> Unit,
    onOpenHive: (Long) -> Unit
) {
    BackHandler { onBack() }
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    // Only these fields affect search or ordering. A change to queen status,
    // strength, or bee count can refresh its row without rerunning list filtering/sort.
    val searchKeys = remember(hives) {
        hives.map { HiveListSearchKey(it.id, it.number, it.apiary) }
    }
    val orderedSearchKeys = remember(searchKeys) {
        searchKeys.sortedWith(
            compareBy<HiveListSearchKey>(
                { it.apiary.lowercase(Locale.ROOT) },
                { it.number.toIntOrNull() ?: Int.MAX_VALUE },
                { it.number }
            )
        )
    }
    val filteredHiveIds = remember(orderedSearchKeys, query) {
        orderedSearchKeys.filter { entry ->
            query.isBlank() ||
                entry.number.contains(query, ignoreCase = true) ||
                hiveDisplayName(entry.number).contains(query, ignoreCase = true) ||
                entry.apiary.contains(query, ignoreCase = true)
        }.map { it.id }
    }
    val hivesById = remember(hives) { hives.associateBy { it.id } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InspectionHoneycombBackground(Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to home") }
                Column(Modifier.weight(1f)) {
                    Text("Hives", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${hives.size} ${if (hives.size == 1) "hive" else "hives"} across all apiaries",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onAddHive, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Add, "Add hive")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search hives or apiaries", color = Color(0xFF78350F)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color(0xFF78350F)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = Color(0xFFD97706),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = Color(0xFFD97706)
                )
            )
            Spacer(Modifier.height(10.dp))
            if (filteredHiveIds.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        if (hives.isEmpty()) "No hives yet" else "No matching hives",
                        if (hives.isEmpty()) "Create an apiary and add hives to see them here." else "Try another hive number or apiary name.",
                        if (hives.isEmpty()) "ADD HIVE" else null,
                        if (hives.isEmpty()) onAddHive else null
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredHiveIds, key = { it }) { hiveId ->
                        val hive = hivesById[hiveId]
                        if (hive != null) {
                            HiveRow(
                                number = hive.number,
                                apiary = hive.apiary,
                                queenStatus = hive.queenStatus,
                                strength = hive.strength,
                                beeCountFrames = hive.beeCountFrames,
                                compact = compactHiveRows,
                                locked = hive.id in lockedHiveIds
                            ) { onOpenHive(hive.id) }
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
    compactHiveRows: Boolean,
    lockedHiveIds: Set<Long>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onAddHive: () -> Unit,
    onOpenHive: (Long) -> Unit,
    onApplyToApiary: () -> Unit
) {
    BackHandler { onBack() }
    val listState = rememberLazyListState()
    var query by rememberSaveable(apiaryName) { mutableStateOf("") }
    // Keep filtering keyed to the fields that determine membership in this list.
    val searchKeys = remember(hives) {
        hives.map { HiveListSearchKey(it.id, it.number, it.apiary) }
    }
    val filteredHiveIds = remember(searchKeys, query) {
        searchKeys.filter { it.number.contains(query, ignoreCase = true) }.map { it.id }
    }
    val hivesById = remember(hives) { hives.associateBy { it.id } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InspectionHoneycombBackground(Modifier.matchParentSize())
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(padding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to apiaries") }
            Column(Modifier.weight(1f)) {
                Text(apiaryName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("${hives.size} ${if (hives.size == 1) "hive" else "hives"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(
                onClick = onApplyToApiary,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) { Text("Apply") }
            IconButton(onClick = onAddHive, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Add, "Add hive") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search hive number", color = Color(0xFF78350F)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color(0xFF78350F)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = Color(0xFFD97706),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = Color(0xFFD97706)
            )
        )
        Spacer(Modifier.height(10.dp))
        if (filteredHiveIds.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (hives.isEmpty()) "No hives in this apiary" else "No matching hives",
                    if (hives.isEmpty()) "Add a hive to $apiaryName to start its record." else "Try a different hive number.",
                    if (hives.isEmpty()) "ADD HIVE" else null,
                    if (hives.isEmpty()) onAddHive else null
                )
            }
        } else {
            LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredHiveIds, key = { it }) { hiveId ->
                    val hive = hivesById[hiveId]
                    if (hive != null) {
                        HiveRow(
                            number = hive.number,
                            apiary = hive.apiary,
                            queenStatus = hive.queenStatus,
                            strength = hive.strength,
                            beeCountFrames = hive.beeCountFrames,
                            compact = compactHiveRows,
                            locked = hive.id in lockedHiveIds
                        ) { onOpenHive(hive.id) }
                    }
                }
            }
        }
    }
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
    deadHives: List<Hive>,
    lockedHiveIds: Set<Long>,
    canManageTags: Boolean = true,
    nfc: NfcController,
    activity: ComponentActivity,
    pendingUid: String?,
    confirmBeforeTagRemoval: Boolean = true,
    onPendingUidConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenHive: (Long) -> Unit,
    onScanOpenHive: (Long) -> Unit,
    onAssignTag: (Long, String, Boolean, (Boolean, String?) -> Unit) -> Unit,
    onClearTag: (Long, (Boolean, String?) -> Unit) -> Unit,
    onUnassignedTag: (String) -> Unit,
    onIsTagUnassigned: suspend (String) -> Boolean,
    onIsTagAmbiguous: suspend (String) -> Boolean,
    onFindHiveForTag: suspend (String) -> Hive?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf("") }
    var assigningHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reassignPrompt by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }
    var pendingRemoveHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var launchWriteHiveId by rememberSaveable { mutableStateOf<Long?>(null) }
    var launchWriteUid by rememberSaveable { mutableStateOf<String?>(null) }
    var launchWriteStatusHandler by remember { mutableStateOf<((String) -> Unit)?>(null) }
    BackHandler { onBack() }
    val assigned = hives.count { !it.tagUid.isNullOrBlank() }

    fun startTagScan(onResult: (NfcResult) -> Unit) {
        if (canManageTags) {
            nfc.startReadAndPrepareForBeeKeep(activity, onResult)
        } else {
            // Viewers must never trigger automatic writes to unassigned physical tags.
            nfc.startRead(activity, onResult)
        }
    }

    androidx.compose.runtime.LaunchedEffect(lockedHiveIds, canManageTags) {
        if (!canManageTags) {
            nfc.stop(activity)
            assigningHiveId = null
            pendingRemoveHiveId = null
            reassignPrompt = null
            launchWriteHiveId = null
            launchWriteUid = null
            launchWriteStatusHandler = null
            status = "View-only access: you can scan and verify tags, but cannot change assignments."
            return@LaunchedEffect
        }
        val activeLockedOperation = sequenceOf(
            assigningHiveId,
            pendingRemoveHiveId,
            reassignPrompt?.first,
            launchWriteHiveId
        ).filterNotNull().firstOrNull { it in lockedHiveIds }
        if (activeLockedOperation != null) {
            nfc.stop(activity)
            assigningHiveId = null
            pendingRemoveHiveId = null
            reassignPrompt = null
            launchWriteHiveId = null
            launchWriteUid = null
            launchWriteStatusHandler = null
            status = "This hive is locked because its paid subscription has ended. Renew a paid plan to manage its NFC tag."
        }
    }

    fun writeLaunchDataAfterAssignment(
        hiveId: Long,
        uid: String,
        updateStatus: (String) -> Unit
    ) {
        // This fallback is retained for confirmed reassignment and legacy pending-tag flows.
        updateStatus("Tag $uid assigned. Tap the tag again to finish writing BeeKeep launch data.")
        launchWriteHiveId = hiveId
        launchWriteUid = uid
        launchWriteStatusHandler = updateStatus
    }

    fun assignAndWriteOnScan(
        hiveId: Long,
        expectedUid: String? = null,
        consumePending: Boolean = false,
        updateStatus: (String) -> Unit
    ) {
        nfc.startAssignAndWrite(
            activity = activity,
            text = BeeKeepNfcPayload.TAG_PAYLOAD,
            targetHiveId = hiveId,
            expectedUid = expectedUid,
            isAssignedElsewhere = { uid ->
                (hives + deadHives).any { it.id != hiveId && NfcUid.matches(it.tagUid, uid) }
            },
            onResult = { result ->
                when (result) {
                    is NfcResult.Written -> {
                        onAssignTag(hiveId, result.uid, false) { success, error ->
                            if (success) {
                                playNfcAssignmentSuccessFeedback(context)
                                if (consumePending) onPendingUidConsumed()
                                val hiveNumber = hives.firstOrNull { it.id == hiveId }?.number ?: hiveId.toString()
                                updateStatus("Tag assigned and launch data written. With BeeKeep closed, tapping this tag should open Hive ${hiveNumber}.")
                            } else {
                                updateStatus(error ?: "Payload was written, but BeeKeep could not save the tag assignment.")
                            }
                        }
                    }
                    is NfcResult.Read -> scope.launch {
                        val normalizedUid = NfcUid.normalize(result.uid)
                        if (normalizedUid == null) {
                            updateStatus("This tag has an invalid NFC UID. Nothing was assigned.")
                            return@launch
                        }
                        val ambiguous = onIsTagAmbiguous(normalizedUid)
                        val owner = (hives + deadHives).firstOrNull { NfcUid.matches(it.tagUid, normalizedUid) }
                        when {
                            ambiguous -> {
                                // Never pick one owner from a conflict. The user explicitly
                                // chooses this hive, then confirms a full ledger repair.
                                reassignPrompt = hiveId to normalizedUid
                                updateStatus("Identity conflict: tag $normalizedUid is linked to multiple hive records. Confirm REASSIGN to make this hive the sole owner.")
                            }
                            owner != null && owner.id != hiveId -> {
                                reassignPrompt = hiveId to normalizedUid
                                updateStatus("Tag $normalizedUid is assigned to ${hiveReferenceName(owner.number)}. Confirm REASSIGN before changing it.")
                            }
                            else -> updateStatus("Could not write this tag automatically. Use WRITE TAG from the hive details to retry.")
                        }
                    }
                    is NfcResult.Error -> updateStatus(result.message)
                }
            },
            isAmbiguousAssignment = { uid ->
                val normalized = NfcUid.normalize(uid)
                normalized != null && NfcUidIndex.fromAssignments((hives + deadHives).map { it.tagUid to it.id })
                    .ambiguousUids.contains(normalized)
            }
        )
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
                    val updateStatus = launchWriteStatusHandler
                    launchWriteHiveId = null
                    launchWriteUid = null
                    launchWriteStatusHandler = null
                    if (hiveId != null && uid != null) {
                        nfc.startWrite(
                            activity = activity,
                            text = BeeKeepNfcPayload.TAG_PAYLOAD,
                            targetHiveId = hiveId,
                            expectedUid = uid,
                            // The UID is locked to the tag that was just explicitly assigned;
                            // re-check current cache state in case sync changed ownership meanwhile.
                            isAssignedElsewhere = { candidateUid ->
                                (hives + deadHives).any { it.id != hiveId && NfcUid.matches(it.tagUid, candidateUid) }
                            },
                            // The DB assignment was just explicitly confirmed. This only writes
                            // generic launch metadata, never a hive ID; expectedUid locks the tag.
                            allowConfirmedAssignment = true,
                            onResult = { result ->
                                when (result) {
                                    is NfcResult.Written -> {
                                        playNfcAssignmentSuccessFeedback(context)
                                        updateStatus?.invoke("Tag assigned and written. Tapping it with BeeKeep closed can now open Hive $targetNumber.")
                                    }
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

    pendingRemoveHiveId?.let { hiveId ->
        val hiveNumber = hives.firstOrNull { it.id == hiveId }?.number ?: hiveId.toString()
        AlertDialog(
            onDismissRequest = { pendingRemoveHiveId = null },
            title = { Text("Remove NFC tag?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("Release the tag assignment from Hive $hiveNumber? The physical tag is not erased; you can assign it to another hive later.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoveHiveId = null
                    status = "Removing NFC tag from Hive $hiveNumber…"
                    onClearTag(hiveId) { success, error ->
                        status = if (success) "Tag removed from Hive $hiveNumber • available for reuse" else (error ?: "Could not remove the tag from Hive $hiveNumber.")
                    }
                }) { Text("REMOVE TAG", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveHiveId = null }) { Text("CANCEL") }
            }
        )
    }

    reassignPrompt?.let { (targetHiveId, tagUid) ->
        AlertDialog(
            onDismissRequest = { reassignPrompt = null },
            title = { Text("Confirm tag reassignment", fontWeight = FontWeight.ExtraBold) },
            text = { Text("This tag is already assigned or has conflicting ownership records. Reassigning will close all existing assignments and make the selected hive the sole owner.") },
            confirmButton = {
                TextButton(onClick = {
                    onAssignTag(targetHiveId, tagUid, true) { success, error ->
                        if (success) {
                            if (pendingUid != null) onPendingUidConsumed()
                            writeLaunchDataAfterAssignment(targetHiveId, tagUid) { status = it }
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
                Text("Choose a hive and tap ASSIGN/REPLACE. BeeKeep links the tag's UID in its database and writes a generic marker plus Android app-launch record. No hive ID or hive history is stored on the physical tag.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Tags are reusable: when a colony dies its tag is released and can be assigned to another hive. Scanning always opens the colony the tag is currently assigned to.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                Text("Use durable, weather-resistant NFC tags on the hive lid or another protected surface.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (pendingUid != null) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tag ready to assign", fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (canManageTags) {
                            "Tag $pendingUid is not linked to a colony. Tap ASSIGN on the hive it belongs to."
                        } else {
                            "Tag $pendingUid is not linked to a colony. An Owner, Admin or Member must assign it."
                        },
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    TextButton(onClick = onPendingUidConsumed) { Text("CANCEL PENDING ASSIGNMENT") }
                }
            }
        }

        Button(
            onClick = {
                assigningHiveId = null
                startTagScan { result ->
                    when (result) {
                        is NfcResult.Read -> scope.launch {
                            val normalizedUid = NfcUid.normalize(result.uid)
                            if (normalizedUid == null) {
                                status = "This tag has an invalid NFC UID. No hive was opened or assigned."
                                return@launch
                            }
                            if (onIsTagAmbiguous(normalizedUid)) {
                                status = "Identity conflict: this NFC UID is linked to multiple hives. Nothing was opened or assigned. Repair the duplicate assignment first."
                                return@launch
                            }

                            val resolvedHive = onFindHiveForTag(normalizedUid)
                            val preparationError = result.launchPreparationError
                            when {
                                resolvedHive != null -> {
                                    status = preparationError?.let { "${hiveReferenceName(resolvedHive.number)} identified, but launch setup needs attention: $it" }
                                        ?: "${hiveReferenceName(resolvedHive.number)} • tag verified by physical UID"
                                    onScanOpenHive(resolvedHive.id)
                                }
                                else -> {
                                    status = preparationError ?: if (onIsTagUnassigned(normalizedUid)) {
                                        "Released tag • $normalizedUid. Choose the new hive before assigning it."
                                    } else {
                                        "Unassigned tag • $normalizedUid. Choose the hive it belongs to."
                                    }
                                    if (canManageTags) {
                                        onUnassignedTag(normalizedUid)
                                    } else {
                                        status = "This tag is not assigned in the selected workspace. Ask an Owner, Admin or Member to assign it."
                                    }
                                }
                            }
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
                            Text(hiveDisplayName(hive.number), fontWeight = FontWeight.Bold)
                            Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (hive.id in lockedHiveIds) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = "Locked hive",
                                tint = Color(0xFFB45309),
                                modifier = Modifier.size(17.dp).padding(end = 2.dp)
                            )
                        }
                        Text(if (hive.tagUid.isNullOrBlank()) "UNASSIGNED" else "TAGGED", fontWeight = FontWeight.Bold, color = if (hive.tagUid.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Text(hive.tagUid ?: "No NFC tag assigned", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(
                            onClick = { onOpenHive(hive.id) },
                            modifier = Modifier.weight(1f)
                        ) { Text("OPEN") }
                        if (canManageTags) {
                            OutlinedButton(
                                onClick = {
                                    assigningHiveId = hive.id
                                    assignAndWriteOnScan(
                                        hiveId = hive.id,
                                        expectedUid = pendingUid,
                                        consumePending = pendingUid != null
                                    ) { message ->
                                        verifyText = message
                                        assigningHiveId = null
                                    }
                                },
                                enabled = nfc.isAvailable() && nfc.isEnabled() && hive.id !in lockedHiveIds,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (hive.tagUid.isNullOrBlank()) "ASSIGN" else "REPLACE") }
                        }
                    }
                    if (hive.tagUid != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(onClick = {
                                nfc.startRead(activity) { result ->
                                    when (result) {
                                        is NfcResult.Read -> scope.launch {
                                            val ambiguous = onIsTagAmbiguous(result.uid)
                                            val matchesUid = NfcUid.matches(result.uid, hive.tagUid)
                                            val currentMarker = BeeKeepNfcPayload.isTagPayload(result.text) && result.info.hasBeeKeepLaunchRecord
                                            verifyText = when {
                                                ambiguous -> "IDENTITY CONFLICT • this UID is linked to multiple hives. Do not use it until duplicate assignments are repaired."
                                                !matchesUid -> "⚠ Tag ${result.uid} does not match ${hiveReferenceName(hive.number)}"
                                                currentMarker -> "✓ Tag UID matches ${hiveReferenceName(hive.number)} and BeeKeep launch data is present"
                                                BeeKeepNfcPayload.hiveId(result.text) != null -> "UID matches, but this tag still has old hive data. Scan it in BeeKeep to upgrade it."
                                                else -> "UID matches, but the BeeKeep app-launch marker is missing. Use WRITE TAG from hive details."
                                            }
                                        }
                                        is NfcResult.Error -> verifyText = result.message
                                        is NfcResult.Written -> Unit
                                    }
                                }
                            }, enabled = hive.id !in lockedHiveIds, modifier = Modifier.weight(1f)) { Text("VERIFY") }
                            if (canManageTags) {
                                TextButton(
                                    onClick = {
                                        if (confirmBeforeTagRemoval) {
                                            pendingRemoveHiveId = hive.id
                                        } else {
                                            verifyText = "Removing NFC tag…"
                                            onClearTag(hive.id) { success, error ->
                                                verifyText = if (success) "Tag removed • available for reuse" else (error ?: "Could not remove tag.")
                                            }
                                        }
                                    },
                                    enabled = hive.id !in lockedHiveIds,
                                    modifier = Modifier.weight(1f)
                                ) { Text("REMOVE") }
                            }
                        }
                    }
                    verifyText.takeIf { it.isNotBlank() }?.let { Text(it, color = if (it.startsWith("⚠")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!canManageTags) {
                        Text(
                            "View-only: existing tags can be scanned and verified, but tag assignments cannot be changed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (assigningHiveId == hive.id) Text("Hold the physical tag to the back of the phone…", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MoreScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onWorkspaceJoined: (String) -> Unit,
    onTagManager: () -> Unit,
    onColonyHistory: () -> Unit,
    onUpgrade: () -> Unit,
    onOpenSupportAdmin: () -> Unit,
    currentPlan: BeeKeepPlan,
    deadCount: Int,
    activity: ComponentActivity,
    cloud: SupabaseGateway
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var savedUsername by rememberSaveable { mutableStateOf("") }
    var authMessage by rememberSaveable { mutableStateOf("") }
    var showSupportEmailFallback by rememberSaveable { mutableStateOf(false) }
    var supportAdmin by rememberSaveable { mutableStateOf(false) }

    fun openSupportEmail() {
        val emailBody = """
            Hello BeeKeep Support,

            Please describe your question or issue below:
            - What happened?
            - What did you expect to happen?
            - Steps to reproduce (if applicable):

            Please do not include passwords or other sensitive account information.

            ---
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            Android version: ${android.os.Build.VERSION.RELEASE}
        """.trimIndent()
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:beekeepinc@gmail.com")
            putExtra(Intent.EXTRA_SUBJECT, "BeeKeep Support Request")
            putExtra(Intent.EXTRA_TEXT, emailBody)
        }
        try {
            context.startActivity(emailIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            showSupportEmailFallback = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(account.signedIn, account.email) {
        if (account.signedIn) {
            val result = cloud.getUsername()
            if (result.isSuccess) {
                username = result.value.orEmpty()
                savedUsername = username
            }
            // This is only a navigation affordance. The server re-authorizes every operation.
            supportAdmin = cloud.checkSupportAdmin().value == true
        } else {
            username = ""
            savedUsername = ""
            supportAdmin = false
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Settings")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Additional tools and account controls", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (supportAdmin) {
            Card(
                onClick = onOpenSupportAdmin,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD97706))
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Support admin tools", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text("Grant Commercial access and manage custom hive allowances", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.Lock, "Open support admin tools", tint = Color(0xFFB45309), modifier = Modifier.size(28.dp))
                }
            }
        }

        Card(
            onClick = onUpgrade,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (currentPlan == BeeKeepPlan.HOBBYIST) Color(0xFFFFF7E6) else MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD97706))
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (currentPlan == BeeKeepPlan.HOBBYIST) "Upgrade BeeKeep" else "BeeKeep ${currentPlan.displayName} active",
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF92400E)
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Open BeeKeep plans", tint = Color(0xFFB45309), modifier = Modifier.size(28.dp))
            }
        }

        WorkspaceJoinEntry(
            cloud = cloud,
            activity = activity,
            onWorkspaceJoined = onWorkspaceJoined
        )

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
                        Text("BeeKeep Account", fontWeight = FontWeight.Bold)
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
                    OutlinedTextField(
                        value = username,
                        onValueChange = { value ->
                            username = value.filter { ch ->
                                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
                            }.take(20)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Username (new accounts)") },
                        placeholder = { Text("e.g. NorthApiary") },
                        supportingText = {
                            Text("3–20 letters, numbers or underscores. Leave blank when signing in.")
                        },
                        singleLine = true
                    )
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = email.contains("@") && password.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    authMessage = ""
                                    when (val result = cloud.signIn(email, password)) {
                                        CloudResult.Success -> {
                                            authMessage = "Signed in. Syncing…"
                                            cloud.startRealtime()
                                            CloudSyncScheduler.runNow(context)
                                        }
                                        CloudResult.NotConfigured -> authMessage = "Cloud is not configured."
                                        CloudResult.NotSignedIn -> authMessage = "Please sign in."
                                        is CloudResult.Failure -> authMessage = result.message
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("SIGN IN") }
                        OutlinedButton(
                            enabled = email.contains("@") && password.isNotEmpty() &&
                                username.matches(Regex("^[A-Za-z0-9_]{3,20}$")),
                            onClick = {
                                scope.launch {
                                    authMessage = ""
                                    val availability = cloud.isUsernameAvailable(username.trim())
                                    if (!availability.isSuccess) {
                                        authMessage = availability.error ?: "Couldn't check username availability. Please try again."
                                    } else if (availability.value != true) {
                                        authMessage = "That username is already taken. Please choose another."
                                    } else {
                                        when (val result = cloud.signUp(email, password, username.trim())) {
                                            CloudResult.Success -> {
                                                authMessage = "Account created. Check your email if verification is required."
                                                cloud.startRealtime()
                                                CloudSyncScheduler.runNow(context)
                                            }
                                            CloudResult.NotConfigured -> authMessage = "Cloud is not configured."
                                            CloudResult.NotSignedIn -> authMessage = "Account created but not signed in yet."
                                            is CloudResult.Failure -> authMessage = result.message
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("CREATE") }
                    }
                } else if (account.signedIn) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { value ->
                            username = value.filter { ch ->
                                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
                            }.take(20)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Team username") },
                        placeholder = { Text("e.g. NorthApiary") },
                        supportingText = {
                            Text("Your teammates see this name in the team member list.")
                        },
                        singleLine = true
                    )
                    Button(
                        enabled = username.matches(Regex("^[A-Za-z0-9_]{3,20}$")) &&
                            username != savedUsername && !account.syncing,
                        onClick = {
                            scope.launch {
                                val result = cloud.setUsername(username.trim())
                                if (result.isSuccess && result.value != null) {
                                    username = result.value
                                    savedUsername = result.value
                                    authMessage = "Username saved. Your team roster will show it."
                                } else {
                                    authMessage = result.error ?: "Couldn't save your username."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("SAVE USERNAME") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                authMessage = ""
                                authMessage = when (val result = cloud.signOut()) {
                                    CloudResult.Success -> "Signed out. Your phone data is still available offline."
                                    CloudResult.NotConfigured -> "Cloud is not configured."
                                    CloudResult.NotSignedIn -> "You are already signed out."
                                    is CloudResult.Failure -> result.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("SIGN OUT") }
                }

                if (authMessage.isNotBlank()) Text(authMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("Contact support", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Need help with hive limits, syncing, NFC tags, or anything else in BeeKeep? Send an email directly to the BeeKeep team.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("beekeepinc@gmail.com", fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { openSupportEmail() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("EMAIL SUPPORT")
                }
                Text(
                    "Your email opens as a draft with app and device details. Review it before sending.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Native Android • Kotlin + Compose", fontWeight = FontWeight.Bold)
                Text("Room offline database • NFC • GPS • Voice • WorkManager • cloud sync outbox", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showSupportEmailFallback) {
        AlertDialog(
            onDismissRequest = { showSupportEmailFallback = false },
            title = { Text("Email app not available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No email app was found on this device. Copy the address below and use it in your preferred email service.")
                    Text("beekeepinc@gmail.com", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "BeeKeep support email",
                                    "beekeepinc@gmail.com"
                                )
                            )
                            android.widget.Toast.makeText(context, "Support email copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        showSupportEmailFallback = false
                    }
                ) { Text("COPY EMAIL") }
            },
            dismissButton = {
                TextButton(onClick = { showSupportEmailFallback = false }) { Text("CLOSE") }
            }
        )
    }
}