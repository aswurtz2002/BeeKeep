package com.beekeep.app.cloud

import android.content.Context
import android.content.Intent
import com.beekeep.app.BuildConfig
import com.beekeep.app.BeeKeepPlan
import com.beekeep.app.BeeKeepEntitlements
import com.beekeep.app.data.LocalHiveRepository
import com.beekeep.app.data.LocalDataAttachmentSnapshotInfo
import com.beekeep.app.data.LocalDataAttachmentSnapshotStore
import com.beekeep.app.data.LocalDataAttachmentMarkerEntity
import com.beekeep.app.data.HarvestQuantityMode
import com.beekeep.app.data.WorkspaceActionSettingsEntity
import com.beekeep.app.data.workspaceActionSettingsScopeKey
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import io.ktor.client.call.body
import java.util.UUID
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface CloudResult {
    data object Success : CloudResult
    data class Failure(val message: String) : CloudResult
    data object NotConfigured : CloudResult
    data object NotSignedIn : CloudResult
}

private object WorkspaceActionLogRefreshSignal {
    private val mutableVersion = MutableStateFlow(0L)
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    fun publish() {
        mutableVersion.value = mutableVersion.value + 1L
    }
}

private const val SYNC_UPLOAD_BATCH_SIZE = 200
private const val SYNC_DOWNLOAD_PAGE_SIZE = 500
private const val SYNC_CONFLICT_LOOKUP_BATCH_SIZE = 100
private const val CLOUD_DATA_ATTACHMENT_PENDING_USER_PREF = "cloud_data_attachment_pending_user_id"
private const val PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF = "pending_password_reset_request_email"
private const val PENDING_PASSWORD_RESET_REQUEST_AT_PREF = "pending_password_reset_request_at"
private const val PASSWORD_RESET_REQUEST_MAX_AGE_MS = 60L * 60L * 1000L
private const val PASSWORD_RECOVERY_SESSION_PREF = "password_recovery_session_active"
private const val ACCOUNT_SETUP_USER_PREF = "account_setup_user_id"
private const val ACCOUNT_SETUP_USERNAME_PREF = "account_setup_username"
private const val ACCOUNT_SETUP_WORKSPACE_ID_PREF = "account_setup_personal_workspace_id"
private const val ACCOUNT_SETUP_WORKSPACE_NAME_PREF = "account_setup_personal_workspace_name"

class SupabaseGateway(
    private val context: Context,
    private val repository: LocalHiveRepository,
    private val enableRealtime: Boolean = true
) {
    private val appContext = context.applicationContext
    private val syncCursorPrefs = appContext.getSharedPreferences(
        "beekeep_cloud_sync_cursors_v1", Context.MODE_PRIVATE
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authOperationRunner = GatewayOwnedOperationRunner(scope)
    @Volatile
    private var sessionRefreshJob: Job? = null
    @Volatile
    private var accountVerificationJob: Job? = null
    private val deviceId = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        .getString("device_id", null)
        ?: UUID.randomUUID().toString().also {
            appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                .edit().putString("device_id", it).apply()
        }

    val configured: Boolean = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    // Foreground and WorkManager gateways must share one client/session refresh loop.
    private val client = BeeKeepSupabaseRuntime.client()

    @Volatile
    private var realtimeJob: Job? = null
    @Volatile
    private var realtimeScopeKey: String? = null
    private val realtimeLock = Any()
    private val syncMutex = BeeKeepSupabaseRuntime.syncMutex

    private val _account = MutableStateFlow(
        CloudAccountState(configured = configured, signedIn = false)
    )
    val account: StateFlow<CloudAccountState> = _account.asStateFlow()
    /** Emits after any configured sync attempt completes, including WorkManager syncs. */
    val workspaceActionLogRefreshVersion: StateFlow<Long> = WorkspaceActionLogRefreshSignal.version
    private val _accountInitialized = MutableStateFlow(false)
    private val _passwordRecoveryRequired = MutableStateFlow(false)
    /** True only after a password-recovery link has been returned to and processed by this app. */
    val passwordRecoveryRequired: StateFlow<Boolean> = _passwordRecoveryRequired.asStateFlow()
    /** True after Supabase has restored the stored session and the account identity is known. */
    val accountInitialized: StateFlow<Boolean> = _accountInitialized.asStateFlow()
    private val _accountSetupState = MutableStateFlow<BeeKeepAccountSetupState>(
        BeeKeepAccountSetupState.SignedOut
    )
    /** Post-login profile and personal-workspace status for the currently authenticated account. */
    val accountSetupState: StateFlow<BeeKeepAccountSetupState> = _accountSetupState.asStateFlow()

    private val _hiveLimitOverride = MutableStateFlow<Int?>(null)
    /** A verified override for the signed-in account, or null when none is granted. */
    val hiveLimitOverride: StateFlow<Int?> = _hiveLimitOverride.asStateFlow()

    private val _verifiedPlan = MutableStateFlow<BeeKeepPlan?>(null)
    /** The current account's paid plan, derived from trusted server metadata. */
    val verifiedPlan: StateFlow<BeeKeepPlan?> = _verifiedPlan.asStateFlow()
    @Volatile
    private var verifiedPlanUserId: String? = null

    init { scope.launch { refreshAccount() } }

    suspend fun refreshAccount() = syncMutex.withLock {
        refreshAccountUnlocked()
    }

    /**
     * Load the persisted session without asking the SDK to refresh it as part of initialization.
     * Supabase Auth 3.9 joins the initial refresh job and retries network failures recursively;
     * that can leave the SDK in Initializing forever. A local restore is bounded and never needs
     * network access. The actual refresh is scheduled separately with a capped retry budget.
     */
    private suspend fun ensureAuthStorageInitialized(supabase: SupabaseClient) {
        BeeKeepSupabaseRuntime.ensureAuthStorageInitialized(supabase)
    }

    private fun scheduleSessionRefresh(
        supabase: SupabaseClient,
        session: UserSession,
        cancelExisting: Boolean = true
    ) {
        if (cancelExisting) sessionRefreshJob?.cancel()
        val expectedUserId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
        val expectedExpiryMillis = session.expiresAt.toEpochMilliseconds()
        val nextJob = scope.launch {
            val refreshAtMillis = expectedExpiryMillis -
                (session.expiresIn.coerceAtLeast(0L) * 200L)
            val waitMillis = (refreshAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            if (waitMillis > 0L) delay(waitMillis)
            refreshSessionWithBoundedRetries(supabase, expectedUserId, expectedExpiryMillis)
        }
        sessionRefreshJob = nextJob
    }

    /**
     * The shared sync mutex makes auth refresh single-flight across the foreground gateway and
     * WorkManager. An older queued refresh exits when another caller has already renewed the token.
     */
    private suspend fun refreshSessionWithBoundedRetries(
        supabase: SupabaseClient,
        expectedUserId: String?,
        expectedExpiryMillis: Long
    ) {
        for (attempt in 1..SessionRefreshPolicy.MAX_ATTEMPTS) {
            var stop = false
            try {
                syncMutex.withLock {
                    val currentSession = supabase.auth.currentSessionOrNull()
                    val currentUserId = currentSession?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                    if (currentSession == null || (expectedUserId != null && currentUserId != expectedUserId) ||
                        currentSession.expiresAt.toEpochMilliseconds() > expectedExpiryMillis
                    ) {
                        stop = true
                        return@withLock
                    }

                    try {
                        withTimeout(15_000L) {
                            supabase.auth.refreshCurrentSession()
                        }
                        val refreshedSession = supabase.auth.currentSessionOrNull()
                        if (refreshedSession != null && refreshedSession.user?.id == expectedUserId) {
                            // Schedule the next refresh without cancelling the job that is completing.
                            scheduleSessionRefresh(supabase, refreshedSession, cancelExisting = false)
                        }
                        stop = true
                    } catch (timeout: TimeoutCancellationException) {
                        // Treat our own request timeout as a transient network failure.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (error is RestException &&
                            SessionRefreshPolicy.isRejectedRefreshCredential(error.statusCode)
                        ) {
                            // This clears only the invalid Supabase credential. Room records,
                            // drafts, and pending sync outbox data remain untouched.
                            if (supabase.auth.currentSessionOrNull()?.user?.id == expectedUserId) {
                                supabase.auth.clearSession()
                                _account.value = CloudAccountState(
                                    configured = true,
                                    signedIn = false,
                                    error = "Your session has expired. Sign in again. Your local hive records are still on this device."
                                )
                                _accountSetupState.value = BeeKeepAccountSetupState.SignedOut
                                _accountInitialized.value = true
                                stopRealtime()
                            }
                            stop = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A lock or client failure consumes one bounded refresh attempt.
            }

            if (stop) return
            if (attempt == SessionRefreshPolicy.MAX_ATTEMPTS) {
                _account.value = _account.value.copy(
                    error = "BeeKeep couldn't refresh your cloud session. Your local records remain available; reconnect and retry."
                )
                return
            }
            delay(SessionRefreshPolicy.retryDelayMillis(attempt) ?: return)
        }
    }

    private suspend fun restoreCachedWorkspaceForAccount(userId: String) {
        val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        val workspaceId = prefs.getString("active_team_workspace_id", "").orEmpty().trim()
        val ownerId = prefs.getString("active_team_workspace_user_id", null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (workspaceId.isBlank()) {
            if (repository.activeWorkspaceId.value != null) repository.switchWorkspace(null)
            return
        }
        if (ownerId != null && ActiveWorkspaceSessionPolicy.ownerMatches(userId, ownerId)) {
            repository.switchWorkspace(
                workspaceId = workspaceId,
                readOnly = prefs.getBoolean("active_team_workspace_read_only", true),
                ownerUserId = userId
            )
        } else {
            // A legacy selection without an account owner cannot be trusted offline.
            repository.switchWorkspace(null)
        }
    }

    private fun scheduleAccountVerification(supabase: SupabaseClient, userId: String) {
        // Replacing a pending verification is safer than stacking account/workspace RPC batches.
        accountVerificationJob?.cancel()
        accountVerificationJob = scope.launch {
            try {
                syncMutex.withLock {
                    if (supabase.auth.currentSessionOrNull()?.user?.id != userId) return@withLock
                    try {
                        withTimeout(35_000L) {
                            refreshVerifiedPlanForUser(userId)
                            refreshHiveLimitOverrideForUser(userId)
                            restoreWorkspaceForAccount(supabase, userId)
                            refreshAccountSetupState(supabase, userId)
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        if (_account.value.userId == userId) {
                            _accountSetupState.value = cachedAccountSetupState(userId)
                                ?: BeeKeepAccountSetupState.Offline
                            _account.value = _account.value.copy(
                                error = "Cloud verification is taking too long. BeeKeep is keeping your local data available."
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (_account.value.userId == userId) {
                    _accountSetupState.value = cachedAccountSetupState(userId)
                        ?: BeeKeepAccountSetupState.Offline
                    _account.value = _account.value.copy(
                        error = "Cloud verification is unavailable. Your local data is still available."
                    )
                }
            }
        }
    }

    private fun isTransientNetworkFailure(error: Throwable): Boolean =
        error is IOException ||
            error is HttpRequestException ||
            error is HttpRequestTimeoutException ||
            (error is RestException &&
                (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500))

    /**
     * Restore a selected team only for the account that selected it. A legacy selection without
     * an owner ID is restored only after a successful membership lookup; offline, the app stays
     * on private/local data until the identity can be verified.
     */
    private suspend fun refreshAccountUnlocked() {
        _accountInitialized.value = false
        val supabase = client
        if (!configured || supabase == null) {
            try {
                _accountSetupState.value = BeeKeepAccountSetupState.SignedOut
                stopRealtime()
                val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                val cachedWorkspaceId = prefs.getString("active_team_workspace_id", "").orEmpty().trim()
                val cachedOwnerId = prefs.getString("active_team_workspace_user_id", null)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                if (ActiveWorkspaceSessionPolicy.canKeepCachedWorkspace(
                        cachedWorkspaceId, cachedOwnerId, cachedOwnerId
                    )
                ) {
                    repository.switchWorkspace(
                        cachedWorkspaceId,
                        readOnly = prefs.getBoolean("active_team_workspace_read_only", false),
                        ownerUserId = cachedOwnerId
                    )
                } else {
                    repository.switchWorkspace(null)
                }
                // A build without a cloud connection can still display a previously account-bound
                // team cache, but it cannot grant writes until the owner and role are verified again.
                _account.value = CloudAccountState(configured = false, signedIn = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _accountSetupState.value = BeeKeepAccountSetupState.Unavailable(
                    "BeeKeep couldn't open its local workspace. Your records have not been deleted. " +
                        TeamWorkspaceSyncError.userFacing(error.message)
                )
                _account.value = CloudAccountState(
                    configured = false,
                    signedIn = false,
                    error = TeamWorkspaceSyncError.userFacing(error.message)
                )
            } finally {
                repository.completeStartupWorkspaceAccessResolution()
                _accountInitialized.value = true
            }
            return
        }
        try {
            // Restore the saved auth state from local storage only. Network refresh runs separately.
            ensureAuthStorageInitialized(supabase)
            val session = supabase.auth.currentSessionOrNull()
            val userId = session?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
            _passwordRecoveryRequired.value = appContext
                .getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                .getBoolean(PASSWORD_RECOVERY_SESSION_PREF, false)
            if (userId == null) {
                _accountSetupState.value = BeeKeepAccountSetupState.SignedOut
                // The auth SDK can have no current session when an expired token cannot refresh
                // offline. An explicit in-app sign-out clears the active workspace before it gets
                // here, so retain an account-bound selection as read-only instead of mistaking an
                // unavailable session for a user-requested switch to personal data.
                stopRealtime()
                val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                val cachedWorkspaceId = prefs.getString("active_team_workspace_id", "").orEmpty().trim()
                val storedOwnerId = prefs.getString("active_team_workspace_user_id", null)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val cachedUserId = prefs.getString("last_cloud_user_id", null)
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: storedOwnerId
                val keepCachedTeam = ActiveWorkspaceSessionPolicy.canKeepCachedWorkspace(
                    cachedWorkspaceId,
                    cachedUserId,
                    storedOwnerId
                )
                if (keepCachedTeam) {
                    repository.switchWorkspace(
                        workspaceId = cachedWorkspaceId,
                        // Keep the last server-verified role/plan state while offline. Any later
                        // cloud upload revalidates the actual membership and owner entitlement.
                        readOnly = prefs.getBoolean("active_team_workspace_read_only", false),
                        ownerUserId = storedOwnerId
                    )
                    BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, cachedUserId)
                    _hiveLimitOverride.value =
                        BeeKeepEntitlements.cachedCloudHiveLimitOverride(appContext, cachedUserId!!)
                    _verifiedPlan.value = BeeKeepEntitlements.currentPlan(appContext)
                    verifiedPlanUserId = cachedUserId
                    _account.value = CloudAccountState(
                        configured = true,
                        signedIn = false,
                        email = prefs.getString("last_cloud_user_email", null).orEmpty(),
                        userId = cachedUserId,
                        error = "Your cloud session isn't available. Your selected team and offline edits remain on this device; reconnect to verify access and sync."
                    )
                } else {
                    repository.switchWorkspace(null)
                    prefs.edit()
                        .remove("last_cloud_user_id")
                        .remove("last_cloud_user_email")
                        .apply()
                    BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, null)
                    _hiveLimitOverride.value = null
                    _verifiedPlan.value = null
                    verifiedPlanUserId = null
                    _account.value = CloudAccountState(configured = true, signedIn = false)
                }
            } else {
                val accountPrefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                // This dedicated marker survives explicit sign-out, which clears the active
                // session identity but must not make the same account look new on this device.
                // The device keeps its local records available and syncs them for the
                // account the user explicitly signs into. Duplicate sign-ups are rejected
                // by the Auth response before a second account can be reported as created.
                accountPrefs.edit()
                    .putString("last_cloud_user_id", userId)
                    .putString("last_cloud_user_email", session?.user?.email.orEmpty())
                    .apply()

                // Seed the first screen from this account's local entitlement/profile cache.
                // Workspace and profile discovery must not block the local Room database.
                BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, userId)
                _hiveLimitOverride.value =
                    BeeKeepEntitlements.cachedCloudHiveLimitOverride(appContext, userId)
                _verifiedPlan.value = BeeKeepEntitlements.currentPlan(appContext)
                verifiedPlanUserId = userId
                restoreCachedWorkspaceForAccount(userId)
                _accountSetupState.value = cachedAccountSetupState(userId)
                    ?: BeeKeepAccountSetupState.Offline
            }
            repository.completeStartupWorkspaceAccessResolution()
            _account.value = CloudAccountState(
                configured = true,
                signedIn = userId != null,
                email = session?.user?.email ?: "",
                userId = userId
            )
            _accountInitialized.value = true
            if (session != null && userId != null) {
                scheduleSessionRefresh(supabase, session)
                scheduleAccountVerification(supabase, userId)
                if (enableRealtime) startRealtime() else stopRealtime()
            } else {
                sessionRefreshJob?.cancel()
                stopRealtime()
            }
        } catch (cancelled: CancellationException) {
            repository.completeStartupWorkspaceAccessResolution()
            val lastKnownUserId = _account.value.userId?.trim()?.takeIf { it.isNotEmpty() }
            if (lastKnownUserId != null) {
                _accountSetupState.value = cachedAccountSetupState(lastKnownUserId)
                    ?: BeeKeepAccountSetupState.Offline
            } else {
                _accountSetupState.value = BeeKeepAccountSetupState.SignedOut
            }
            _accountInitialized.value = true
            throw cancelled
        } catch (error: Throwable) {
            // A failed auth/network refresh is not a sign-out. Keep the last account-bound team
            // and verified entitlement instead of forcing the user into their personal dataset.
            stopRealtime()
            val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            val cachedWorkspaceId = prefs.getString("active_team_workspace_id", "").orEmpty().trim()
            val storedOwnerId = prefs.getString("active_team_workspace_user_id", null)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val cachedUserId = prefs.getString("last_cloud_user_id", null)
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: _account.value.userId?.trim()?.takeIf { it.isNotEmpty() }
                ?: storedOwnerId
            val canKeepTeam = ActiveWorkspaceSessionPolicy.canKeepCachedWorkspace(
                cachedWorkspaceId,
                cachedUserId,
                storedOwnerId
            )
            if (canKeepTeam) {
                if (repository.activeWorkspaceId.value != cachedWorkspaceId) {
                    repository.switchWorkspace(
                        cachedWorkspaceId,
                        readOnly = prefs.getBoolean("active_team_workspace_read_only", true),
                        ownerUserId = storedOwnerId
                    )
                }
            } else if (storedOwnerId != null && cachedUserId != null &&
                !ActiveWorkspaceSessionPolicy.ownerMatches(cachedUserId, storedOwnerId)
            ) {
                // The new account identity is known and does not own the saved local cache.
                // Clear the selected team in this case; transient/offline failures above retain it.
                repository.switchWorkspace(null)
            }
            if (cachedUserId != null) {
                BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, cachedUserId)
                _hiveLimitOverride.value =
                    BeeKeepEntitlements.cachedCloudHiveLimitOverride(appContext, cachedUserId)
                if (verifiedPlanUserId != cachedUserId || _verifiedPlan.value == null) {
                    _verifiedPlan.value = BeeKeepEntitlements.currentPlan(appContext)
                    verifiedPlanUserId = cachedUserId
                }
            }
            repository.completeStartupWorkspaceAccessResolution()
            val lastKnownUserId = cachedUserId
            _accountSetupState.value = if (lastKnownUserId != null) {
                cachedAccountSetupState(lastKnownUserId) ?: if (isTransientNetworkFailure(error)) {
                    BeeKeepAccountSetupState.Offline
                } else {
                    BeeKeepAccountSetupState.Unavailable(
                        "BeeKeep couldn't verify your profile and personal workspace. Reconnect and retry. Your hive records have not been changed."
                    )
                }
            } else {
                BeeKeepAccountSetupState.SignedOut
            }
            _account.value = CloudAccountState(
                configured = true,
                signedIn = lastKnownUserId != null,
                email = prefs.getString("last_cloud_user_email", null).orEmpty(),
                userId = lastKnownUserId,
                error = TeamWorkspaceSyncError.userFacing(error.message)
            )
            _accountInitialized.value = true
        } finally {
            // No startup outcome, including cancellation during fallback restoration, may strand
            // the auth gate or the repository's startup write barrier.
            repository.completeStartupWorkspaceAccessResolution()
            _accountInitialized.value = true
        }
    }

    /**
     * Confirms the signed-in user has exactly one personal workspace owned by that same account,
     * then reads the server-managed username. This is read-only: creation/backfill is handled by
     * the auth.users trigger and the existing workspace migration.
     */
    private suspend fun refreshAccountSetupState(supabase: SupabaseClient, userId: String) {
        try {
            withTimeout(15_000L) {
                val workspaces = supabase.postgrest.rpc("beekeep_list_workspaces")
                    .decodeList<BeeKeepWorkspace>()
                val personalWorkspace = AccountSetupPolicy.personalWorkspaceForAccount(workspaces, userId)
                    ?: error("Your personal BeeKeep workspace couldn't be verified. Try again or contact support.")

                val username = supabase.postgrest.rpc("beekeep_get_username")
                    .decodeList<BeeKeepUsername>()
                    .firstOrNull()?.username.orEmpty().trim()

                cacheAccountSetup(userId, username, personalWorkspace.id, personalWorkspace.name)
                _accountSetupState.value = if (AccountSetupPolicy.isValidUsername(username)) {
                    BeeKeepAccountSetupState.Ready(
                        username = username,
                        personalWorkspaceId = personalWorkspace.id,
                        personalWorkspaceName = personalWorkspace.name
                    )
                } else {
                    BeeKeepAccountSetupState.UsernameRequired(
                        personalWorkspaceId = personalWorkspace.id,
                        personalWorkspaceName = personalWorkspace.name
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            _accountSetupState.value = cachedAccountSetupState(userId)
                ?: BeeKeepAccountSetupState.Offline
            _account.value = _account.value.copy(
                error = "Cloud profile and workspace checks timed out. Your local BeeKeep data is still available."
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A remote workspace/profile lookup failure must never block the local app. Keep a
            // previously verified profile when available; otherwise let the user work offline.
            _accountSetupState.value = cachedAccountSetupState(userId)
                ?: BeeKeepAccountSetupState.Offline
            _account.value = _account.value.copy(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "BeeKeep couldn't verify your cloud profile. Your local data is still available."
                )
            )
        }
    }

    private fun cacheAccountSetup(
        userId: String,
        username: String,
        personalWorkspaceId: String,
        personalWorkspaceName: String
    ) {
        appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
            .putString(ACCOUNT_SETUP_USER_PREF, userId)
            .putString(ACCOUNT_SETUP_USERNAME_PREF, username)
            .putString(ACCOUNT_SETUP_WORKSPACE_ID_PREF, personalWorkspaceId)
            .putString(ACCOUNT_SETUP_WORKSPACE_NAME_PREF, personalWorkspaceName)
            .apply()
    }

    private fun cachedAccountSetupState(userId: String): BeeKeepAccountSetupState? {
        val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        if (prefs.getString(ACCOUNT_SETUP_USER_PREF, null) != userId) return null
        val workspaceId = prefs.getString(ACCOUNT_SETUP_WORKSPACE_ID_PREF, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val workspaceName = prefs.getString(ACCOUNT_SETUP_WORKSPACE_NAME_PREF, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val username = prefs.getString(ACCOUNT_SETUP_USERNAME_PREF, "").orEmpty().trim()
        return if (AccountSetupPolicy.isValidUsername(username)) {
            BeeKeepAccountSetupState.Ready(
                username = username,
                personalWorkspaceId = workspaceId,
                personalWorkspaceName = workspaceName,
                isCached = true
            )
        } else {
            BeeKeepAccountSetupState.UsernameRequired(
                personalWorkspaceId = workspaceId,
                personalWorkspaceName = workspaceName,
                isCached = true
            )
        }
    }

    /** The offline action is available only for the matching saved account with actual local records. */
    suspend fun canContinueOffline(): Boolean {
        val supabase = client ?: return false
        return try {
            withTimeout(5_000L) {
                ensureAuthStorageInitialized(supabase)
                val userId = supabase.auth.currentSessionOrNull()?.user?.id
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: return@withTimeout false
                val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                if (prefs.getString("last_cloud_user_id", null) != userId) return@withTimeout false
                repository.hasLocalData()
            }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    /** Publish the matching local dataset without waiting for cloud discovery or initial sync. */
    suspend fun continueOffline(): Boolean {
        val supabase = client ?: return false
        return try {
            withTimeout(5_000L) {
                ensureAuthStorageInitialized(supabase)
                val session = supabase.auth.currentSessionOrNull() ?: return@withTimeout false
                val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withTimeout false
                val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                if (prefs.getString("last_cloud_user_id", null) != userId || !repository.hasLocalData()) {
                    return@withTimeout false
                }
                restoreCachedWorkspaceForAccount(userId)
                _accountSetupState.value = cachedAccountSetupState(userId)
                    ?: BeeKeepAccountSetupState.Offline
                _account.value = CloudAccountState(
                    configured = true,
                    signedIn = true,
                    email = session.user?.email.orEmpty(),
                    userId = userId,
                    error = "Offline mode. Your local hive records remain available and cloud sync will resume when connected."
                )
                repository.completeStartupWorkspaceAccessResolution()
                _accountInitialized.value = true
                scheduleSessionRefresh(supabase, session)
                scheduleAccountVerification(supabase, userId)
                true
            }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    /** Re-checks profile/workspace setup after a temporary network or database failure. */
    suspend fun retryAccountSetup() {
        val supabase = client
        if (!configured || supabase == null) {
            _accountSetupState.value = BeeKeepAccountSetupState.Unavailable(
                "BeeKeep Cloud isn't configured for this build."
            )
            return
        }
        var startupUserId: String? = null
        try {
            ensureAuthStorageInitialized(supabase)
            val userId = supabase.auth.currentSessionOrNull()?.user?.id
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (userId == null) {
                _accountSetupState.value = BeeKeepAccountSetupState.SignedOut
                return
            }
            startupUserId = userId
            _accountSetupState.value = BeeKeepAccountSetupState.Checking
            refreshAccountSetupState(supabase, userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val userId = startupUserId
                ?: supabase.auth.currentSessionOrNull()?.user?.id?.trim().orEmpty()
            _accountSetupState.value = cachedAccountSetupState(userId)
                ?: if (userId.isBlank()) BeeKeepAccountSetupState.SignedOut
                else BeeKeepAccountSetupState.Offline
            _account.value = _account.value.copy(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "BeeKeep couldn't verify your cloud profile. Your local data is still available."
                )
            )
        } finally {
            // A screen/lifecycle cancellation must not leave the global account gate in Checking.
            if (_accountSetupState.value is BeeKeepAccountSetupState.Checking) {
                val userId = startupUserId
                    ?: supabase.auth.currentSessionOrNull()?.user?.id?.trim().orEmpty()
                _accountSetupState.value = cachedAccountSetupState(userId)
                    ?: if (userId.isBlank()) {
                        BeeKeepAccountSetupState.SignedOut
                    } else {
                        BeeKeepAccountSetupState.Offline
                    }
            }
        }
    }

    /**
     * The owner of a shared workspace must have an active Commercial entitlement.
     * The decision comes from the trusted SECURITY DEFINER RPC. Offline, keep only
     * the signed-in account's last server-verified plan as a temporary fallback.
     */
    private suspend fun hasActiveCommercialEntitlementForWorkspaceOwner(
        supabase: SupabaseClient
    ): Boolean {
        return try {
            supabase.postgrest.rpc("beekeep_my_active_ultimate_entitlement")
                .decodeList<BeeKeepUltimateEntitlement>()
                .firstOrNull()?.active == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            BeeKeepEntitlements.currentPlan(appContext) == BeeKeepPlan.COMMERCIAL
        }
    }

    /**
     * Resolve the workspace owner's trusted Commercial entitlement for every team role.
     * Invited members cannot use their own plan to bypass the owner's subscription lock.
     * If the membership-scoped check fails, fail closed and keep the local team read-only.
     */
    private suspend fun hasActiveCommercialEntitlementForWorkspace(
        supabase: SupabaseClient,
        workspaceId: String,
        memberRole: String?
    ): Boolean {
        if (memberRole == "owner") {
            return hasActiveCommercialEntitlementForWorkspaceOwner(supabase)
        }
        return try {
            supabase.postgrest.rpc(
                "beekeep_workspace_owner_commercial_entitlement",
                buildJsonObject { put("target_workspace_id", workspaceId) }
            ).decodeList<BeeKeepUltimateEntitlement>().firstOrNull()?.active == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun restoreWorkspaceForAccount(supabase: SupabaseClient, userId: String) {
        val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        val workspaceId = prefs.getString("active_team_workspace_id", "")
            .orEmpty().trim()
        if (workspaceId.isBlank()) {
            if (repository.activeWorkspaceId.value != null) repository.switchWorkspace(null)
            return
        }

        val savedOwnerId = prefs.getString("active_team_workspace_user_id", null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (savedOwnerId != null &&
            !ActiveWorkspaceSessionPolicy.ownerMatches(userId, savedOwnerId)
        ) {
            repository.switchWorkspace(null)
            return
        }

        if (savedOwnerId != null) {
            // The restored Supabase session matches the account that selected this team.
            // Re-select its cached Room database before waiting on any network membership lookup.
            // Keep the last verified access mode while the server refresh is in flight.
            repository.switchWorkspace(
                workspaceId = workspaceId,
                readOnly = prefs.getBoolean("active_team_workspace_read_only", true),
                ownerUserId = userId
            )
        }

        // A known matching owner can use the cached team while offline. A pre-account-binding
        // selection must be checked against the server before it is restored.
        var membershipLookupCompleted = false
        var memberWorkspace: BeeKeepWorkspace? = null
        if (savedOwnerId == null) {
            try {
                memberWorkspace = supabase.postgrest.rpc("beekeep_list_workspaces")
                    .decodeList<BeeKeepWorkspace>()
                    .firstOrNull { it.id == workspaceId && !it.is_personal }
                membershipLookupCompleted = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                membershipLookupCompleted = false
            }
            if (!membershipLookupCompleted || memberWorkspace == null) {
                repository.switchWorkspace(null)
                return
            }
        } else {
            // Refresh the role when online. A failed request is not proof of revocation;
            // retain the account-bound read-only state for offline use.
            try {
                memberWorkspace = supabase.postgrest.rpc("beekeep_list_workspaces")
                    .decodeList<BeeKeepWorkspace>()
                    .firstOrNull { it.id == workspaceId && !it.is_personal }
                membershipLookupCompleted = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                membershipLookupCompleted = false
            }
            if (membershipLookupCompleted && memberWorkspace == null) {
                // Membership can have been revoked while this device still has a selected,
                // account-bound local copy. Keep that selection as a read-only archive instead
                // of silently moving the beekeeper to personal data.
                repository.switchWorkspace(
                    workspaceId = workspaceId,
                    readOnly = true,
                    ownerUserId = userId
                )
                return
            }
        }

        val memberRole = memberWorkspace?.member_role
        val ownerCommercialActive = if (membershipLookupCompleted && memberRole != null) {
            hasActiveCommercialEntitlementForWorkspace(supabase, workspaceId, memberRole)
        } else {
            false
        }
        val readOnly = if (membershipLookupCompleted) {
            WorkspaceAccessPolicy.isReadOnly(memberRole, ownerCommercialActive)
        } else {
            // A first-time/offline membership check must not grant local edit access.
            // Preserve an existing explicit read-only state, otherwise fail closed.
            prefs.getBoolean("active_team_workspace_read_only", true)
        }
        repository.switchWorkspace(
            workspaceId = workspaceId,
            readOnly = readOnly,
            ownerUserId = userId
        )
    }

    /**
     * Reads the signed-in account's active plan from the trusted entitlement RPC. A new account
     * never inherits the previous account's paid plan, and failed refreshes don't erase this
     * account's last verified value while it is offline.
     */
    private suspend fun refreshVerifiedPlanForUser(userId: String) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) return
        if (verifiedPlanUserId != cleanUserId) {
            verifiedPlanUserId = cleanUserId
            // Seed the UI from this account's last server-verified cache while the RPC refreshes.
            _verifiedPlan.value = BeeKeepEntitlements.currentPlan(appContext)
        }
        val supabase = client ?: return
        try {
            val response = supabase.postgrest.rpc("beekeep_my_hive_entitlement")
                .decodeList<BeeKeepServerEntitlement>()
                .firstOrNull()
            val verifiedPlan = response?.let {
                BeeKeepEntitlements.planFromServerEntitlement(
                    planCode = it.planCode,
                    baseHiveLimit = it.baseHiveLimit,
                    isActivePaidPlan = it.isActivePaidPlan
                )
            } ?: BeeKeepPlan.HOBBYIST
            _verifiedPlan.value = verifiedPlan
            BeeKeepEntitlements.updateCloudVerifiedPlan(appContext, cleanUserId, verifiedPlan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Keep this account's last verified plan while offline; a different account clears it above.
        }
    }

    /**
     * Refreshes the current account's entitlement from a SECURITY DEFINER RPC. If the network
     * is unavailable, retain only this account's previously verified cache so offline work
     * continues without ever carrying another account's override across sign-in.
     */
    private suspend fun refreshHiveLimitOverrideForUser(userId: String) {
        val supabase = client ?: return
        BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, userId)
        _hiveLimitOverride.value = BeeKeepEntitlements.cachedCloudHiveLimitOverride(appContext, userId)
        try {
            val response = supabase.postgrest.rpc("beekeep_my_hive_limit_override")
                .decodeList<BeeKeepHiveLimitOverride>()
                .firstOrNull()
            val limit = response?.hive_limit_override?.takeIf {
                it in BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT..BeeKeepEntitlements.MAX_CUSTOM_HIVE_LIMIT
            }
            BeeKeepEntitlements.updateCloudHiveLimitOverride(appContext, userId, limit)
            _hiveLimitOverride.value = limit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The entitlement RPC is additive to sync; a transient failure must not sign users
            // out or discard their last verified, account-scoped quota cache.
        }
    }

    suspend fun signIn(email: String, password: String): CloudResult {
        val result = authCall(restoreWorkspaceId = repository.activeWorkspaceId.value) {
            val supabase = client ?: return@authCall
            supabase.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
        }
        if (result == CloudResult.Success) clearPendingPasswordResetRequestUnlessEmailMatches(email)
        return result
    }

    /**
     * Exchanges a Google Credential Manager ID token for a normal Supabase session.
     * The raw nonce is supplied so the SDK can verify the SHA-256 nonce claim in Google's token.
     */
    suspend fun signInWithGoogle(idToken: String, nonce: String): CloudResult {
        if (idToken.isBlank() || nonce.isBlank()) {
            return CloudResult.Failure("Google sign-in could not be verified. Please try again.")
        }
        return authCall(restoreWorkspaceId = repository.activeWorkspaceId.value) {
            val supabase = client ?: return@authCall
            supabase.auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                this.nonce = nonce
            }
        }
    }

    suspend fun signUp(email: String, password: String, username: String = ""): CloudResult {
        val result = authCall {
            val supabase = client ?: return@authCall
            val signupUser = supabase.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
                data = buildJsonObject {
                    if (username.isNotBlank()) put("username", username.trim())
                }
            }
            if (SignupResponsePolicy.isExistingEmail(signupUser?.identities?.size)) {
                throw IllegalStateException(
                    "An account with this email already exists. Please sign in instead."
                )
            }
        }
        if (result == CloudResult.Success) clearPendingPasswordResetRequest()
        return result
    }

    /** Resends the confirmation email for a signup that has not yet been verified. */
    suspend fun resendSignupConfirmation(email: String): CloudResult = authEmailRequest { supabase ->
        supabase.auth.resendEmail(
            OtpType.Email.SIGNUP,
            email.trim(),
            redirectUrl = authRedirectUrl()
        )
    }

    /**
     * Requests a recovery link without revealing whether the email address belongs to an account.
     * The recovery-pending marker is only used to recognize a PKCE callback that has no type field.
     */
    suspend fun requestPasswordReset(email: String): CloudResult {
        val result = authEmailRequest { supabase ->
            supabase.auth.resetPasswordForEmail(
                email = email.trim(),
                redirectUrl = authRedirectUrl()
            )
        }
        if (result == CloudResult.Success) {
            appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
                .putString(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF, email.trim())
                .putLong(PENDING_PASSWORD_RESET_REQUEST_AT_PREF, System.currentTimeMillis())
                .apply()
        }
        return result
    }

    /** Updates the password only after a recovery callback has established an authenticated session. */
    suspend fun updatePassword(newPassword: String): CloudResult {
        if (!_passwordRecoveryRequired.value) {
            return CloudResult.Failure("Open the password-reset link from your email before choosing a new password.")
        }
        if (newPassword.length < 8) {
            return CloudResult.Failure("Use a password with at least 8 characters.")
        }
        val result = authEmailRequest { supabase ->
            supabase.auth.updateUser {
                password = newPassword
            }
        }
        if (result == CloudResult.Success) {
            // Keep the recovery screen active until the user acknowledges success.
            clearPendingPasswordResetRequest()
        }
        return result
    }

    private fun clearPendingPasswordResetRequestUnlessEmailMatches(email: String) {
        val pendingEmail = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            .getString(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF, null)
        if (!pendingEmail.equals(email.trim(), ignoreCase = true)) {
            clearPendingPasswordResetRequest()
        }
    }

    private fun clearPendingPasswordResetRequest() {
        appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
            .remove(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF)
            .remove(PENDING_PASSWORD_RESET_REQUEST_AT_PREF)
            .apply()
    }

    /** Leaves the recovery-only screen after the user acknowledges the successful password change. */
    suspend fun finishPasswordRecovery() {
        appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
            .remove(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF)
            .remove(PENDING_PASSWORD_RESET_REQUEST_AT_PREF)
            .remove(PASSWORD_RECOVERY_SESSION_PREF)
            .apply()
        _passwordRecoveryRequired.value = false
        refreshAccount()
    }

    private fun authRedirectUrl(): String =
        BuildConfig.AUTH_REDIRECT_SCHEME + "://" + BuildConfig.AUTH_REDIRECT_HOST
    private suspend fun authEmailRequest(
        block: suspend (SupabaseClient) -> Unit
    ): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured
        return try {
            ensureAuthStorageInitialized(client)
            block(client)
            CloudResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val rawMessage = error.message.orEmpty().trim()
            val message = if (rawMessage.isBlank() || rawMessage.length > 240) {
                "BeeKeep couldn't complete that email request. Check your connection and try again."
            } else {
                TeamWorkspaceSyncError.userFacing(rawMessage)
            }
            CloudResult.Failure(message)
        }
    }

    suspend fun signOut(): CloudResult = syncMutex.withLock { signOutUnlocked() }

    private suspend fun signOutUnlocked(): CloudResult = try {
        val supabase = client ?: return CloudResult.NotConfigured
        stopRealtime()
        appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE).edit()
            .remove(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF)
            .remove(PENDING_PASSWORD_RESET_REQUEST_AT_PREF)
            .remove(PASSWORD_RECOVERY_SESSION_PREF)
            .apply()
        _passwordRecoveryRequired.value = false
        // Never leave another account viewing the previously selected team's local cache.
        repository.switchWorkspace(null)
        supabase.auth.signOut()
        refreshAccountUnlocked()
        CloudResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        val message = TeamWorkspaceSyncError.userFacing(t.message ?: "Sign out failed")
        _account.value = _account.value.copy(syncing = false, error = message)
        CloudResult.Failure(message)
    }

    /**
     * Permanently clears this signed-in workspace in cloud sync. Deletion tombstones are
     * retained instead of simply dropping rows, so another device won't restore old records.
     */
    suspend fun resetWorkspace(): CloudResult = syncMutex.withLock {
        val supabase = client ?: return@withLock CloudResult.NotConfigured
        try {
            ensureAuthStorageInitialized(supabase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withLock CloudResult.Failure(
                TeamWorkspaceSyncError.userFacing(error.message ?: "Could not restore your cloud session")
            )
        }
        val session = supabase.auth.currentSessionOrNull() ?: run {
            refreshAccountUnlocked()
            return@withLock CloudResult.NotSignedIn
        }
        val userId = session.user?.id ?: return@withLock CloudResult.NotSignedIn
        _account.value = _account.value.copy(syncing = true, error = null)
        try {
            val workspacePrefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            val activeTeamWorkspaceId = workspacePrefs
                .getString("active_team_workspace_id", "")
                .orEmpty().trim()
            if (activeTeamWorkspaceId.isNotEmpty()) {
                val selectedOwnerId = workspacePrefs.getString("active_team_workspace_user_id", null)
                if (!ActiveWorkspaceSessionPolicy.ownerMatches(userId, selectedOwnerId)) {
                    repository.switchWorkspace(null)
                    val message = "Select this team workspace again before resetting it."
                    _account.value = _account.value.copy(syncing = false, error = message)
                    return@withLock CloudResult.Failure(message)
                }
                val workspace = supabase.postgrest.rpc("beekeep_list_workspaces")
                    .decodeList<BeeKeepWorkspace>()
                    .firstOrNull { it.id == activeTeamWorkspaceId && !it.is_personal }
                if (workspace == null) {
                    val message = "You no longer have access to this team workspace."
                    _account.value = _account.value.copy(syncing = false, error = message)
                    return@withLock CloudResult.Failure(message)
                }
                if (workspace.member_role == "viewer") {
                    val message = "This team workspace is view-only. Your data was not reset."
                    _account.value = _account.value.copy(syncing = false, error = message)
                    return@withLock CloudResult.Failure(message)
                }
                val remoteRows = loadAllWorkspaceDocuments(activeTeamWorkspaceId)
                val resetAt = System.currentTimeMillis()
                val tombstones = remoteRows.map { row ->
                    val payload = JSONObject()
                        .put("id", row.entity_id)
                        .put("updated_at", resetAt)
                        .put("deleted", true)
                    row.copy(
                        payload = payload.toString(),
                        updated_at = resetAt,
                        deleted = true,
                        device_id = deviceId,
                        updated_by = userId
                    )
                }
                tombstones.chunked(100).forEach { batch ->
                    supabase.from("beekeep_workspace_documents").upsert(batch) {
                        onConflict = "workspace_id,entity_type,entity_id"
                    }
                }
                _account.value = _account.value.copy(
                    syncing = false,
                    lastSyncAt = resetAt,
                    error = null,
                    signedIn = true,
                    email = session.user?.email ?: ""
                )
                return@withLock CloudResult.Success
            }

            // RLS limits this query to the signed-in user's personal cloud documents.
            val remoteRows = loadAllPersonalDocuments().filter { it.user_id == userId }
            val resetAt = System.currentTimeMillis()
            val tombstones = remoteRows.map { row ->
                val existingPayload = runCatching { JSONObject(row.payload) }.getOrDefault(JSONObject())
                val payload = JSONObject()
                    .put("id", row.entity_id)
                    .put("updated_at", resetAt)
                    .put("deleted", true)

                row.copy(
                    payload = payload.toString(),
                    updated_at = resetAt,
                    deleted = true,
                    device_id = deviceId
                )
            }

            tombstones.chunked(100).forEach { batch ->
                supabase.from("beekeep_documents").upsert(batch) {
                    onConflict = "user_id,entity_type,entity_id"
                }
            }

            _account.value = _account.value.copy(
                syncing = false,
                lastSyncAt = resetAt,
                error = null,
                signedIn = true,
                email = session.user?.email ?: ""
            )
            CloudResult.Success
        } catch (cancelled: CancellationException) {
            _account.value = _account.value.copy(syncing = false)
            throw cancelled
        } catch (error: Throwable) {
            val message = TeamWorkspaceSyncError.userFacing(error.message ?: "Cloud workspace reset failed")
            _account.value = _account.value.copy(syncing = false, error = message)
            CloudResult.Failure(message)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isLocalDataAttachmentPending(userId: String): Boolean = false

    private fun selectedRealtimeWorkspaceId(): String =
        appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            .getString("active_team_workspace_id", "")
            .orEmpty().trim()

    /**
     * Subscribe only to the document scope this device is currently showing. A personal
     * account does not need to wake up for team writes (or other users' personal writes),
     * and a team member does not need events from other workspaces they belong to.
     *
     * The sync cursor remains the source of truth; Realtime is only a scoped wake-up signal.
     */
    fun startRealtime() {
        val supabase = client
        if (!configured || supabase == null) return
        val session = supabase.auth.currentSessionOrNull() ?: return
        val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val workspaceId = selectedRealtimeWorkspaceId()
        val scopeKey = if (workspaceId.isBlank()) {
            "personal:$userId"
        } else {
            "workspace:$userId:$workspaceId"
        }
        val actionSettingsScopeKey = if (workspaceId.isBlank()) {
            "personal:$userId"
        } else {
            "workspace:$workspaceId"
        }

        synchronized(realtimeLock) {
            if (realtimeJob?.isActive == true && realtimeScopeKey == scopeKey) return
            val previousJob = realtimeJob
            realtimeScopeKey = scopeKey
            // Stop the previous scope immediately; the replacement waits for its finally block.
            previousJob?.cancel()
            realtimeJob = scope.launch {
                // Wait until the previous listener has unsubscribed before opening this channel.
                // That prevents an old listener from removing the new one during a workspace switch.
                previousJob?.join()
                if (previousJob != null) {
                    runCatching { supabase.realtime.removeAllChannels() }
                }

                var backoffMs = 1_000L
                while (true) {
                    try {
                        val currentUserId = supabase.auth.currentSessionOrNull()?.user?.id
                        if (currentUserId != userId) return@launch
                        if (selectedRealtimeWorkspaceId() != workspaceId) return@launch

                        val channel = supabase.channel("beekeep-doc-sync")
                        try {
                            val documentChanges = if (workspaceId.isBlank()) {
                                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                    table = "beekeep_documents"
                                    filter { eq("user_id", userId) }
                                }
                            } else {
                                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                    table = "beekeep_workspace_documents"
                                    filter { eq("workspace_id", workspaceId) }
                                }
                            }
                            val actionSettingsChanges =
                                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                    table = "beekeep_action_settings"
                                    filter { eq("scope_key", actionSettingsScopeKey) }
                                }

                            supabase.realtime.connect()
                            channel.subscribe()
                            _account.value = _account.value.copy(error = null)
                            backoffMs = 1_000L
                            merge(documentChanges, actionSettingsChanges).debounce(500).collect {
                                syncNow()
                            }
                        } finally {
                            runCatching { supabase.realtime.removeAllChannels() }
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        val friendlyMessage = TeamWorkspaceSyncError.userFacing(t.message)
                        val displayMessage = if (
                            friendlyMessage == TeamWorkspaceSyncError.CONNECTION_UNAVAILABLE_MESSAGE
                        ) friendlyMessage else "Live sync unavailable: $friendlyMessage"
                        _account.value = _account.value.copy(error = displayMessage)
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                    }
                }
            }
        }
    }

    fun stopRealtime() {
        synchronized(realtimeLock) {
            // The listener's finally block owns channel removal. Keep its Job reference so a
            // quick sign-in/reconnect can wait for that cleanup before opening a new channel.
            realtimeScopeKey = null
            realtimeJob?.cancel()
        }
    }

    /**
     * Personal workspaces can contain thousands of hive and history records. PostgREST
     * responses are bounded, so always page with a deterministic order instead of silently
     * treating a single response page as the entire local/cloud dataset.
     */
    private suspend fun loadAllPersonalDocuments(): List<CloudDocumentRow> {
        val supabase = client ?: return emptyList()
        val pageSize = 500L
        var offset = 0L
        val documents = mutableListOf<CloudDocumentRow>()
        while (true) {
            val page = supabase.from("beekeep_documents")
                .select {
                    order(column = "entity_type", order = Order.ASCENDING)
                    order(column = "entity_id", order = Order.ASCENDING)
                    range(offset..(offset + pageSize - 1L))
                }
                .decodeList<CloudDocumentRow>()
            documents += page
            if (page.size < pageSize) break
            offset += pageSize
        }
        return documents
    }

    /**
     * Team workspaces can grow beyond a single PostgREST response. Page in a deterministic
     * order so sync and reset see the full record set rather than silently skipping later rows.
     */
    private suspend fun loadAllWorkspaceDocuments(workspaceId: String): List<CloudWorkspaceDocumentRow> {
        val supabase = client ?: return emptyList()
        val pageSize = 500L
        var offset = 0L
        val documents = mutableListOf<CloudWorkspaceDocumentRow>()
        while (true) {
            val page = supabase.from("beekeep_workspace_documents")
                .select {
                    filter { eq("workspace_id", workspaceId) }
                    order(column = "entity_type", order = Order.ASCENDING)
                    order(column = "entity_id", order = Order.ASCENDING)
                    range(offset..(offset + pageSize - 1L))
                }
                .decodeList<CloudWorkspaceDocumentRow>()
            documents += page.filter { it.workspace_id == workspaceId }
            if (page.size < pageSize) break
            offset += pageSize
        }
        return documents
    }

    private fun personalSyncCursorKey(userId: String): String = "personal:$userId"

    private fun workspaceSyncCursorKey(userId: String, workspaceId: String): String =
        "workspace:$userId:$workspaceId"

    private fun readSyncCursor(key: String): Long? =
        if (syncCursorPrefs.contains(key)) syncCursorPrefs.getLong(key, 0L) else null

    private fun saveSyncCursor(key: String, version: Long) {
        check(syncCursorPrefs.edit().putLong(key, version).commit()) {
            "Could not save the cloud sync cursor. The next sync will safely retry these changes."
        }
    }

    private suspend fun loadPersonalSyncHead(): Long {
        val supabase = client ?: return 0L
        return supabase.postgrest.rpc("beekeep_personal_sync_head")
            .decodeList<CloudSyncHead>()
            .firstOrNull()?.head_version ?: 0L
    }

    private suspend fun loadWorkspaceSyncHead(workspaceId: String): Long {
        val supabase = client ?: return 0L
        return supabase.postgrest.rpc(
            "beekeep_workspace_sync_head",
            WorkspaceSyncHeadParams(workspaceId)
        ).decodeList<CloudSyncHead>()
            .firstOrNull()?.head_version ?: 0L
    }

    private suspend fun loadPersonalChanges(
        afterVersion: Long,
        throughVersion: Long
    ): List<CloudDocumentRow> {
        val supabase = client ?: return emptyList()
        if (throughVersion <= afterVersion) return emptyList()

        val documents = mutableListOf<CloudDocumentRow>()
        var pageCursor = afterVersion.coerceAtLeast(0L)
        while (pageCursor < throughVersion) {
            val page = supabase.postgrest.rpc(
                "beekeep_personal_sync_changes",
                PersonalSyncChangesParams(
                    after_version = pageCursor,
                    through_version = throughVersion,
                    requested_page_size = SYNC_DOWNLOAD_PAGE_SIZE
                )
            ).decodeList<CloudDocumentRow>()
            if (page.isEmpty()) break

            val nextCursor = page.last().sync_version
            check(nextCursor > pageCursor && page.all {
                it.sync_version > pageCursor && it.sync_version <= throughVersion
            }) {
                "The personal sync page returned an invalid version range."
            }
            documents += page
            pageCursor = nextCursor
            if (page.size < SYNC_DOWNLOAD_PAGE_SIZE) break
        }
        return documents
    }

    private suspend fun loadWorkspaceChanges(
        workspaceId: String,
        afterVersion: Long,
        throughVersion: Long
    ): List<CloudWorkspaceDocumentRow> {
        val supabase = client ?: return emptyList()
        if (throughVersion <= afterVersion) return emptyList()

        val documents = mutableListOf<CloudWorkspaceDocumentRow>()
        var pageCursor = afterVersion.coerceAtLeast(0L)
        while (pageCursor < throughVersion) {
            val page = supabase.postgrest.rpc(
                "beekeep_workspace_sync_changes",
                WorkspaceSyncChangesParams(
                    target_workspace_id = workspaceId,
                    after_version = pageCursor,
                    through_version = throughVersion,
                    requested_page_size = SYNC_DOWNLOAD_PAGE_SIZE
                )
            ).decodeList<CloudWorkspaceDocumentRow>()
            if (page.isEmpty()) break

            val nextCursor = page.last().sync_version
            check(nextCursor > pageCursor && page.all {
                it.sync_version > pageCursor && it.sync_version <= throughVersion
            }) {
                "The workspace sync page returned an invalid version range."
            }
            documents += page
            pageCursor = nextCursor
            if (page.size < SYNC_DOWNLOAD_PAGE_SIZE) break
        }
        return documents
    }

    /**
     * Conflict checks now fetch only cloud rows for locally queued entity IDs. The previous
     * implementation downloaded every document just to compare this small outbox batch.
     */
    private suspend fun loadPersonalDocumentsForEntityIds(
        userId: String,
        entityIds: Collection<Long>
    ): List<CloudDocumentRow> {
        val supabase = client ?: return emptyList()
        val ids = entityIds.distinct()
        if (ids.isEmpty()) return emptyList()

        return ids.chunked(SYNC_CONFLICT_LOOKUP_BATCH_SIZE).flatMap { batch ->
            supabase.from("beekeep_documents")
                .select {
                    filter {
                        eq("user_id", userId)
                        isIn("entity_id", batch.map { it as Any })
                    }
                }
                .decodeList<CloudDocumentRow>()
        }
    }

    private suspend fun loadWorkspaceDocumentsForEntityIds(
        workspaceId: String,
        entityIds: Collection<Long>
    ): List<CloudWorkspaceDocumentRow> {
        val supabase = client ?: return emptyList()
        val ids = entityIds.distinct()
        if (ids.isEmpty()) return emptyList()

        return ids.chunked(SYNC_CONFLICT_LOOKUP_BATCH_SIZE).flatMap { batch ->
            supabase.from("beekeep_workspace_documents")
                .select {
                    filter {
                        eq("workspace_id", workspaceId)
                        isIn("entity_id", batch.map { it as Any })
                    }
                }
                .decodeList<CloudWorkspaceDocumentRow>()
        }
    }

    private data class LegacyNfcSnapshotDocument(
        val entityType: String,
        val entityId: Long,
        val payload: String,
        val updatedAt: Long
    )

    /**
     * Old builds stamped full-hive payloads with the exact timestamp used by NFC assignment or
     * release. Inspect the full batch so the related assignment/event is visible even if it
     * hasn't been applied to Room yet.
     */
    private fun legacyNfcOnlyHiveSnapshotIds(
        documents: Collection<LegacyNfcSnapshotDocument>,
        knownMutations: Collection<NfcMutationSyncTimestamp> = emptyList()
    ): Set<Long> {
        val mutations = knownMutations.toMutableList()
        documents.forEach { document ->
            val payload = runCatching { JSONObject(document.payload) }.getOrNull() ?: return@forEach
            when (document.entityType) {
                "nfc_assignment" -> {
                    val hiveId = payload.optLong("hive_id", -1L)
                    if (hiveId <= 0L) return@forEach
                    payload.optLong("assigned_at", 0L).takeIf { it > 0L }?.let {
                        mutations += NfcMutationSyncTimestamp(hiveId, it)
                    }
                    if (payload.has("unassigned_at") && !payload.isNull("unassigned_at")) {
                        payload.optLong("unassigned_at", 0L).takeIf { it > 0L }?.let {
                            mutations += NfcMutationSyncTimestamp(hiveId, it)
                        }
                    }
                }
                "event" -> {
                    if (!payload.optString("type").equals("tag", ignoreCase = true) ||
                        !payload.optString("title").startsWith("NFC tag ", ignoreCase = true)
                    ) return@forEach
                    val hiveId = payload.optLong("hive_id", -1L)
                    val createdAt = payload.optLong("created_at", document.updatedAt)
                    if (hiveId > 0L && createdAt > 0L) {
                        mutations += NfcMutationSyncTimestamp(hiveId, createdAt)
                    }
                }
            }
        }
        val profiles = documents.asSequence()
            .filter { it.entityType == "hive" }
            .map { document ->
                val profileTimestamp = runCatching {
                    JSONObject(document.payload).optLong("updated_at", document.updatedAt)
                }.getOrDefault(document.updatedAt)
                HiveProfileSyncTimestamp(document.entityId, profileTimestamp)
            }
            .toList()
        return LegacyNfcProfileSnapshotGuard.legacyHiveIds(profiles, mutations)
    }

    private fun legacyNfcOnlyPersonalHiveSnapshotIds(
        documents: Collection<CloudDocumentRow>,
        knownMutations: Collection<NfcMutationSyncTimestamp> = emptyList()
    ): Set<Long> = legacyNfcOnlyHiveSnapshotIds(
        documents.map {
            LegacyNfcSnapshotDocument(it.entity_type, it.entity_id, it.payload, it.updated_at)
        },
        knownMutations
    )

    private fun legacyNfcOnlyWorkspaceHiveSnapshotIds(
        documents: Collection<CloudWorkspaceDocumentRow>,
        knownMutations: Collection<NfcMutationSyncTimestamp> = emptyList()
    ): Set<Long> = legacyNfcOnlyHiveSnapshotIds(
        documents.map {
            LegacyNfcSnapshotDocument(it.entity_type, it.entity_id, it.payload, it.updated_at)
        },
        knownMutations
    )

    /**
     * Builds a read-only account-attachment preview. The local snapshot is saved before the
     * cloud inventory is queried, and cloud inventory uses SELECT only under the current
     * authenticated user's RLS scope. This method never clears the attachment hold.
     */
    internal suspend fun previewLocalDataAttachment(): WorkspaceOperationResult<LocalDataAttachmentPreview> {
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        return try {
            ensureAuthStorageInitialized(supabase)
            val session = supabase.auth.currentSessionOrNull()
                ?: return WorkspaceOperationResult(error = "Sign in before reviewing this device's data.")
            val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: return WorkspaceOperationResult(error = "The signed-in account could not be verified.")
            if (!isLocalDataAttachmentPending(userId)) {
                return WorkspaceOperationResult(
                    error = "There is no pending local-data review for this signed-in account."
                )
            }

            // Capture first. Even if the network inventory request fails, the local snapshot
            // remains available on this device and the app continues to hold cloud sync.
            val snapshot = repository.createLocalDataAttachmentSnapshot(userId)

            var cloudCounts: Map<String, Int> = emptyMap()
            var deletedCounts: Map<String, Int> = emptyMap()
            var inventoryError: String? = null
            try {
                val remoteRows = loadAllPersonalDocuments().filter { it.user_id == userId }
                cloudCounts = remoteRows.groupingBy { it.entity_type }.eachCount()
                deletedCounts = remoteRows.asSequence()
                    .filter { it.deleted }
                    .groupingBy { it.entity_type }
                    .eachCount()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                inventoryError = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "The cloud inventory could not be loaded."
                )
            }

            WorkspaceOperationResult(
                value = LocalDataAttachmentPreview(
                    accountId = userId,
                    accountEmail = session.user?.email.orEmpty(),
                    capturedAt = System.currentTimeMillis(),
                    localSnapshot = snapshot,
                    cloudRecordCounts = cloudCounts,
                    cloudDeletedCounts = deletedCounts,
                    cloudInventoryError = inventoryError
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "The local-data review could not be prepared."
                )
            )
        }
    }

    /**
     * Builds a collision-aware plan from an integrity-verified local snapshot and a fresh
     * read-only cloud inventory. It never uploads, applies, or acknowledges any record.
     */
    internal suspend fun buildLocalDataAttachmentMergePlan(
        snapshotInfo: LocalDataAttachmentSnapshotInfo
    ): WorkspaceOperationResult<LocalDataAttachmentMergePlan> {
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        return try {
            ensureAuthStorageInitialized(supabase)
            val session = supabase.auth.currentSessionOrNull()
                ?: return WorkspaceOperationResult(error = "Sign in before building a merge plan.")
            val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: return WorkspaceOperationResult(error = "The signed-in account could not be verified.")
            if (!isLocalDataAttachmentPending(userId)) {
                return WorkspaceOperationResult(
                    error = "The local-data review is no longer pending for this account."
                )
            }

            val snapshotJson = LocalDataAttachmentSnapshotStore.readVerified(
                appContext.filesDir,
                snapshotInfo
            )
            val snapshotAccountId = JSONObject(snapshotJson).optString("targetAccountId").trim()
            require(snapshotAccountId == userId) {
                "This recovery snapshot belongs to a different account. Refresh the local-data preview."
            }

            // RLS scopes this full paginated SELECT to the currently authenticated user.
            val remoteRows = loadAllPersonalDocuments().filter { it.user_id == userId }
            val plan = LocalDataAttachmentMergePlanner.plan(snapshotJson, remoteRows)
                .copy(cloudInventorySha256 = cloudInventoryFingerprint(remoteRows))
            WorkspaceOperationResult(value = plan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "BeeKeep could not build a safe merge plan."
                )
            )
        }
    }

    /**
     * Executes an explicitly approved local-to-personal-account attachment.
     *
     * Cloud writes are INSERT-only and checkpointed. A retry discovers already inserted rows,
     * validates their exact payload, and resumes only the missing rows. No existing cloud row is
     * upserted, and the sync hold is cleared only after both sides are verified.
     */
    internal suspend fun executeLocalDataAttachment(
        snapshotInfo: LocalDataAttachmentSnapshotInfo,
        approvedPlan: LocalDataAttachmentMergePlan
    ): WorkspaceOperationResult<LocalDataAttachmentExecutionResult> = syncMutex.withLock {
        val supabase = client
            ?: return@withLock WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        try {
            ensureAuthStorageInitialized(supabase)
            val session = supabase.auth.currentSessionOrNull()
                ?: return@withLock WorkspaceOperationResult(error = "Sign in before attaching local data.")
            val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@withLock WorkspaceOperationResult(error = "The signed-in account could not be verified.")
            if (userId != approvedPlan.accountId) {
                return@withLock WorkspaceOperationResult(
                    error = "The signed-in account changed. Refresh the preview and build a new merge plan."
                )
            }
            if (!isLocalDataAttachmentPending(userId)) {
                return@withLock WorkspaceOperationResult(
                    error = "There is no pending local-data review for this signed-in account."
                )
            }
            if (repository.activeWorkspaceId.value != null) {
                return@withLock WorkspaceOperationResult(
                    error = "Switch to your personal data before attaching local records. Team workspaces are never combined."
                )
            }
            require(approvedPlan.isSafeToProceed) {
                "Resolve every item requiring manual review before attaching local data."
            }
            require(approvedPlan.cloudInventorySha256.matches(Regex("^[a-f0-9]{64}$"))) {
                "Refresh the cloud inventory and build a new merge plan before attaching."
            }

            stopRealtime()
            val snapshotJson = LocalDataAttachmentSnapshotStore.readVerified(
                appContext.filesDir,
                snapshotInfo
            )
            require(JSONObject(snapshotJson).optString("targetAccountId").trim() == userId) {
                "This recovery snapshot belongs to a different account."
            }

            var journal = LocalDataAttachmentExecutionStore.read(
                appContext.filesDir,
                snapshotInfo,
                userId
            )
            val remoteBefore = loadAllPersonalDocuments().filter { it.user_id == userId }
            val liveFingerprint = cloudInventoryFingerprint(remoteBefore)
            val resumed = journal != null

            if (journal == null) {
                // Confirm the local database has not changed since the user reviewed it.
                val latestSnapshot = repository.createLocalDataAttachmentSnapshot(userId)
                val latestJson = LocalDataAttachmentSnapshotStore.readVerified(
                    appContext.filesDir,
                    latestSnapshot
                )
                require(snapshotContentFingerprint(latestJson) == snapshotContentFingerprint(snapshotJson)) {
                    "Local records changed after the preview. Nothing was attached. Refresh the preview and build a new plan."
                }

                val freshPlan = LocalDataAttachmentMergePlanner.plan(snapshotJson, remoteBefore)
                    .copy(cloudInventorySha256 = liveFingerprint)
                require(freshPlan.isSafeToProceed && freshPlan.items == approvedPlan.items &&
                    freshPlan.cloudInventorySha256 == approvedPlan.cloudInventorySha256
                ) {
                    "The cloud account changed after the merge plan was built. Refresh the preview and review a new plan."
                }
                journal = LocalDataAttachmentExecutionJournal(
                    accountId = userId,
                    snapshotFileName = snapshotInfo.fileName,
                    snapshotSha256 = snapshotInfo.sha256,
                    baseCloudInventorySha256 = liveFingerprint,
                    plan = freshPlan,
                    createdAt = System.currentTimeMillis()
                )
                LocalDataAttachmentExecutionStore.save(appContext.filesDir, journal)
            } else {
                require(attachmentTargetMapping(journal.plan) == attachmentTargetMapping(approvedPlan)) {
                    "The saved attachment checkpoint has different ID mappings. Do not continue with this plan; refresh and review it."
                }
                // Before Room commits, the source snapshot must remain unchanged. After the
                // marker commits, local IDs and queue state intentionally differ from the source.
                val roomCommitted = repository.isLocalAttachmentRoomApplyCommitted(
                    snapshotInfo.sha256,
                    userId
                )
                if (!roomCommitted) {
                    val latestSnapshot = repository.createLocalDataAttachmentSnapshot(userId)
                    val latestJson = LocalDataAttachmentSnapshotStore.readVerified(
                        appContext.filesDir,
                        latestSnapshot
                    )
                    require(snapshotContentFingerprint(latestJson) == snapshotContentFingerprint(snapshotJson)) {
                        "Local records changed while an earlier attachment attempt was paused. The checkpoint remains held; refresh the preview and resolve the new changes before retrying."
                    }
                }
            }

            val frozenJournal = journal
                ?: throw IllegalStateException("BeeKeep could not create the attachment checkpoint.")
            val uploadDocuments = buildLocalAttachmentUploadDocuments(
                snapshotJson,
                frozenJournal.plan,
                userId
            )
            val expectedByKey = uploadDocuments.associateBy { "${it.entity_type}:${it.entity_id}" }
            require(expectedByKey.size == uploadDocuments.size) {
                "The attachment plan contains duplicate upload IDs. No cloud rows were written."
            }

            fun matchingUploadedRows(rows: List<CloudDocumentRow>): Set<String> {
                val byKey = rows.groupBy { "${it.entity_type}:${it.entity_id}" }
                val alreadyPresent = mutableSetOf<String>()
                expectedByKey.forEach { (key, expected) ->
                    val matches = byKey[key].orEmpty()
                    require(matches.size <= 1) {
                        "The cloud account returned duplicate rows for $key. Attachment has been paused."
                    }
                    val existing = matches.singleOrNull()
                    if (existing != null) {
                        require(!existing.deleted && existing.payload == expected.payload) {
                            "Cloud record $key changed or now belongs to different data. Nothing will be overwritten; manual review is required."
                        }
                        alreadyPresent += key
                    }
                }
                return alreadyPresent
            }

            val alreadyPresentKeys = matchingUploadedRows(remoteBefore)
            val baseRows = remoteBefore.filter {
                "${it.entity_type}:${it.entity_id}" !in expectedByKey
            }
            require(cloudInventoryFingerprint(baseRows) == frozenJournal.baseCloudInventorySha256) {
                "Other cloud records changed after this attachment was checkpointed. The remaining upload is paused for a fresh review."
            }

            val missingDocuments = uploadDocuments.filter {
                "${it.entity_type}:${it.entity_id}" !in alreadyPresentKeys
            }
            missingDocuments.chunked(100).forEach { batch ->
                // Deliberately INSERT, never UPSERT: a primary-key collision must fail rather
                // than replace a record owned by the signed-in account.
                supabase.from("beekeep_documents").insert(batch)
            }

            val remoteAfterInsert = loadAllPersonalDocuments().filter { it.user_id == userId }
            val confirmedKeys = matchingUploadedRows(remoteAfterInsert)
            require(confirmedKeys.size == uploadDocuments.size) {
                "Cloud verification found missing attachment rows. The checkpoint is retained and sync stays paused."
            }
            val verifiedBaseRows = remoteAfterInsert.filter {
                "${it.entity_type}:${it.entity_id}" !in expectedByKey
            }
            require(cloudInventoryFingerprint(verifiedBaseRows) == frozenJournal.baseCloudInventorySha256) {
                "The cloud account changed during attachment. The uploaded rows are retained, but sync stays paused for review."
            }

            repository.applyLocalDataAttachmentRemap(
                frozenJournal.plan,
                snapshotJson,
                snapshotInfo.sha256
            )
            repository.applyCloudDocuments(CloudDocumentOrder.personal(remoteAfterInsert))
            repository.holdPendingAttachmentQueues()
            require(repository.verifyPersonalAttachmentDocuments(remoteAfterInsert)) {
                "The cloud data could not be fully verified on this device. The attachment checkpoint is retained and cloud sync stays paused."
            }

            val currentHead = loadPersonalSyncHead()
            saveSyncCursor(personalSyncCursorKey(userId), currentHead)
            LocalDataAttachmentExecutionStore.save(
                appContext.filesDir,
                frozenJournal.copy(localDatabaseApplied = true)
            )

            _account.value = _account.value.copy(syncing = false, error = null, signedIn = true)
            startRealtime()

            WorkspaceOperationResult(
                value = LocalDataAttachmentExecutionResult(
                    accountId = userId,
                    uploadedRecordCount = missingDocuments.size,
                    alreadyPresentRecordCount = alreadyPresentKeys.size,
                    remappedRecordCount = frozenJournal.plan.remapCount,
                    preservedLocalOnlyCount = frozenJournal.plan.localOnlyCount,
                    resumed = resumed,
                    verifiedAt = System.currentTimeMillis()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            stopRealtime()
            val message = TeamWorkspaceSyncError.userFacing(
                error.message ?: "BeeKeep could not complete the local-data attachment."
            )
            _account.value = _account.value.copy(syncing = false, error = message)
            WorkspaceOperationResult(error = message)
        }
    }

    private fun buildLocalAttachmentUploadDocuments(
        snapshotJson: String,
        plan: LocalDataAttachmentMergePlan,
        userId: String
    ): List<CloudDocumentRow> {
        val records = JSONObject(snapshotJson).optJSONArray("records") ?: JSONArray()
        val localRows = (0 until records.length()).associate { index ->
            val row = records.getJSONObject(index)
            "${row.optString("type")}:${row.optLong("id")}" to row
        }
        return plan.items
            .filter {
                it.entityType in setOf(
                    "apiary", "hive", "nfc_assignment", "inspection",
                    "feeding", "treatment", "harvest", "event"
                ) && it.disposition in setOf(
                    LocalDataAttachmentDisposition.UPLOAD_NEW,
                    LocalDataAttachmentDisposition.REMAP_ID
                )
            }
            .sortedWith(compareBy<LocalDataAttachmentMergeItem> {
                when (it.entityType) {
                    "apiary" -> 0
                    "hive" -> 1
                    "nfc_assignment" -> 2
                    "inspection" -> 3
                    "feeding" -> 4
                    "treatment" -> 5
                    "harvest" -> 6
                    else -> 7
                }
            }.thenBy { it.targetId })
            .map { item ->
                val row = localRows["${item.entityType}:${item.sourceId}"]
                    ?: throw IllegalArgumentException(
                        "The recovery snapshot is missing ${item.entityType} #${item.sourceId}."
                    )
                val payload = JSONObject(row.getJSONObject("payload").toString())
                    .put("id", item.targetId)
                item.targetHiveId?.let { payload.put("hive_id", it) }
                item.targetApiaryId?.let { payload.put("apiary_id", it) }
                val updatedAt = row.optLong(
                    "updatedAt",
                    payload.optLong("updated_at", System.currentTimeMillis())
                )
                CloudDocumentRow(
                    user_id = userId,
                    entity_type = item.entityType,
                    entity_id = item.targetId,
                    payload = payload.toString(),
                    updated_at = updatedAt,
                    deleted = false,
                    device_id = payload.optString("device_id", deviceId)
                )
            }
    }

    private fun attachmentTargetMapping(plan: LocalDataAttachmentMergePlan): String =
        plan.items
            .sortedWith(compareBy<LocalDataAttachmentMergeItem> { it.entityType }.thenBy { it.sourceId })
            .joinToString("\n") {
                "${it.entityType}:${it.sourceId}->${it.targetId}:${it.targetHiveId}:${it.targetApiaryId}"
            }

    private fun snapshotContentFingerprint(snapshotJson: String): String {
        val root = JSONObject(snapshotJson)
        root.remove("createdAt")
        return sha256(root.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun cloudInventoryFingerprint(rows: Collection<CloudDocumentRow>): String {
        val canonical = rows.sortedWith(
            compareBy<CloudDocumentRow> { it.entity_type }.thenBy { it.entity_id }
        ).joinToString("\n") { row ->
            listOf(
                row.user_id,
                row.entity_type,
                row.entity_id.toString(),
                row.payload,
                row.updated_at.toString(),
                row.deleted.toString(),
                row.device_id
            ).joinToString("|")
        }
        return sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun syncNow(): CloudResult = syncMutex.withLock {
        val result = syncNowUnlocked()
        if (result is CloudResult.Success || result is CloudResult.Failure) {
            WorkspaceActionLogRefreshSignal.publish()
        }
        result
    }

    /**
     * Synchronize the scoped harvest interpretation independently from hive documents.
     * Team writes require a currently verified Owner/Admin role; RLS repeats that check
     * on the server. A missing additive table is tolerated until its migration is deployed.
     */
    private suspend fun syncWorkspaceActionSettings(
        scopeKey: String,
        workspaceId: String?,
        userId: String,
        canWrite: Boolean
    ): String? {
        val supabase = client ?: return "BeeKeep Cloud is not configured."
        val local = repository.getWorkspaceActionSettings(scopeKey)
        return try {
            val remote = supabase.from("beekeep_action_settings")
                .select {
                    filter { eq("scope_key", scopeKey) }
                }
                .decodeList<BeeKeepActionSettingsRow>()
                .firstOrNull()

            val localPending = local?.syncState in setOf("pending", "local") &&
                (workspaceId == null || local?.syncState == "pending")

            // For this two-value preference, an explicitly pending local edit wins when
            // it next reaches the server. The database assigns the authoritative updated_at;
            // device clocks must not silently discard an offline Owner/Admin change.
            if (local != null && localPending && canWrite) {
                val outgoing = BeeKeepActionSettingsRow(
                    scope_key = scopeKey,
                    owner_user_id = if (workspaceId == null) userId else null,
                    workspace_id = workspaceId,
                    harvest_quantity_mode = HarvestQuantityMode.fromStorage(
                        local.harvestQuantityMode
                    ).storageValue,
                    updated_at = local.updatedAt,
                    updated_by = userId
                )
                supabase.from("beekeep_action_settings").upsert(outgoing) {
                    onConflict = "scope_key"
                }

                // The database trigger owns the authoritative timestamp and editor ID.
                val confirmed = supabase.from("beekeep_action_settings")
                    .select {
                        filter { eq("scope_key", scopeKey) }
                    }
                    .decodeList<BeeKeepActionSettingsRow>()
                    .firstOrNull()
                    ?: throw IllegalStateException(
                        "The server did not return the saved harvest setting."
                    )

                repository.applyRemoteWorkspaceActionSettings(
                    remote = confirmed.toLocalEntity(),
                    preservePendingLocal = false
                )
                return null
            }

            if (remote != null) {
                repository.applyRemoteWorkspaceActionSettings(
                    remote = remote.toLocalEntity(),
                    preservePendingLocal = canWrite
                )
            } else if (!canWrite && localPending) {
                // A locally queued Owner/Admin change must not survive a verified role
                // downgrade when the workspace has no server-side setting row yet.
                repository.applyRemoteWorkspaceActionSettings(
                    remote = WorkspaceActionSettingsEntity(
                        scopeKey = scopeKey,
                        workspaceId = workspaceId,
                        ownerUserId = if (workspaceId == null) userId else null,
                        harvestQuantityMode = HarvestQuantityMode.PER_HIVE.storageValue,
                        updatedAt = 0L,
                        updatedBy = null,
                        syncState = "synced",
                        lastError = null
                    ),
                    preservePendingLocal = false
                )
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (local?.syncState == "pending") {
                runCatching {
                    repository.setWorkspaceActionSettingsSyncState(
                        scopeKey = scopeKey,
                        state = "pending",
                        error = TeamWorkspaceSyncError.userFacing(error.message)
                    )
                }
            }
            val message = error.message.orEmpty()
            val missingSettingsTable = listOf(
                "beekeep_action_settings",
                "PGRST205",
                "42P01",
                "does not exist",
                "could not find the table"
            ).any { token -> message.contains(token, ignoreCase = true) }

            if (missingSettingsTable && local?.syncState != "pending") {
                null
            } else {
                val detail = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "Unknown settings sync error"
                )
                "Harvest quantity setting wasn't confirmed in the cloud. " +
                    "It remains saved on this device. $detail"
            }
        }
    }

    private fun BeeKeepActionSettingsRow.toLocalEntity() = WorkspaceActionSettingsEntity(
        scopeKey = scope_key,
        workspaceId = workspace_id,
        ownerUserId = owner_user_id,
        harvestQuantityMode = HarvestQuantityMode.fromStorage(harvest_quantity_mode).storageValue,
        updatedAt = updated_at,
        updatedBy = updated_by,
        syncState = "synced",
        lastError = null
    )

    /**
     * Uploads each durable apiary action as one server-side transaction. The ordinary
     * document outbox is drained first so the server can verify the apiary and active hives.
     * Failed requests remain pending locally and retry with the same batch identity.
     */
    private suspend fun uploadPendingActionBatches(workspaceId: String?) {
        val supabase = client ?: throw IllegalStateException("BeeKeep Cloud is not configured.")
        while (true) {
            val pending = repository.pendingActionBatches()
            if (pending.isEmpty()) return

            for (batch in pending) {
                if (repository.activeWorkspaceId.value != workspaceId || batch.workspaceId != workspaceId) {
                    throw IllegalStateException(
                        "The selected workspace changed before an apiary action could sync. Switch back to the original workspace and sync again."
                    )
                }
                try {
                    if (batch.schemaVersion != 1) {
                        throw IllegalStateException(
                            "This apiary action uses an unsupported batch format. Update BeeKeep before syncing it."
                        )
                    }
                    val envelope = Json.parseToJsonElement(batch.payload).jsonObject
                    val payloadSchemaVersion = envelope["schema_version"]?.jsonPrimitive?.int
                    if (payloadSchemaVersion != batch.schemaVersion) {
                        throw IllegalStateException("The saved apiary action batch is incomplete or has an invalid schema version.")
                    }
                    val documents = envelope["documents"] as? JsonArray
                        ?: throw IllegalStateException("The saved apiary action batch has no document list.")
                    val apiaryId = envelope["apiary_id"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: throw IllegalStateException("The saved apiary action batch has no valid apiary ID.")
                    val apiaryName = envelope["apiary_name"]?.jsonPrimitive?.content
                        ?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("The saved apiary action batch has no apiary name.")
                    val actionType = envelope["action_type"]?.jsonPrimitive?.content
                        ?.trim()?.lowercase()
                        ?: throw IllegalStateException("The saved apiary action batch has no action type.")
                    val storedBatchId = envelope["batch_id"]?.jsonPrimitive?.content
                    if (storedBatchId != batch.batchId) {
                        throw IllegalStateException("The saved apiary action batch ID does not match its outbox identity.")
                    }

                    val result = supabase.postgrest.rpc(
                        "beekeep_apply_action_batch",
                        ApplyActionBatchParams(
                            target_workspace_id = workspaceId,
                            target_apiary_id = apiaryId,
                            target_apiary_name = apiaryName,
                            target_batch_id = batch.batchId,
                            target_action_type = actionType,
                            target_documents = documents
                        )
                    ).decodeList<ApplyActionBatchResult>().singleOrNull()
                        ?: throw IllegalStateException("The server did not confirm the apiary action batch.")
                    if (result.saved_records < 1) {
                        throw IllegalStateException("The server did not save any records for the apiary action batch.")
                    }

                    repository.completeActionBatch(batch.batchId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    repository.markActionBatchError(
                        batch.batchId,
                        error.message ?: "The apiary action batch could not sync."
                    )
                    throw error
                }
            }
        }
    }

    private suspend fun syncNowUnlocked(): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured
        try {
            ensureAuthStorageInitialized(client)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = TeamWorkspaceSyncError.userFacing(error.message)
            _account.value = _account.value.copy(syncing = false, error = message)
            return CloudResult.Failure(message)
        }
        val session = client.auth.currentSessionOrNull() ?: run {
            refreshAccountUnlocked()
            return CloudResult.NotSignedIn
        }
        val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            refreshAccountUnlocked()
            _account.value = _account.value.copy(syncing = false, signedIn = false, error = "Your cloud session has no user ID. Sign in again.")
            return CloudResult.NotSignedIn
        }
        refreshVerifiedPlanForUser(userId)
        refreshHiveLimitOverrideForUser(userId)
        var activeTeamWorkspaceId = appContext
            .getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            .getString("active_team_workspace_id", "")
            .orEmpty().trim()
        val savedWorkspaceOwnerId = appContext
            .getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            .getString("active_team_workspace_user_id", null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (activeTeamWorkspaceId.isNotBlank() && savedWorkspaceOwnerId != null &&
            !ActiveWorkspaceSessionPolicy.ownerMatches(userId, savedWorkspaceOwnerId)
        ) {
            repository.switchWorkspace(null)
            activeTeamWorkspaceId = ""
        } else if (activeTeamWorkspaceId.isBlank() && repository.activeWorkspaceId.value != null) {
            repository.switchWorkspace(null)
        }
        // A non-empty team selection is switched into the active Room database only after
        // syncSharedWorkspace confirms membership for this account.
        _account.value = _account.value.copy(syncing = true, error = null)
        if (activeTeamWorkspaceId.isNotEmpty()) {
            return syncSharedWorkspace(activeTeamWorkspaceId, userId, session.user?.email.orEmpty())
        }
        return try {

            // Remove legacy NFC-only hive snapshots before they can overwrite newer cloud data.
            repository.discardLegacyNfcProfileSnapshots()
            // Recover note edits saved locally before their debounce queued an outbox row.
            repository.queuePendingInspectionNoteDrafts()
            repository.queuePendingInspectionFormDrafts()

            // Pull first so an older offline edit cannot overwrite a newer cloud record.
            // Drain the entire outbox in bounded batches: the old single-batch sync could
            // publish an apiary while leaving its hive rows queued for a later run.
            val cursorKey = personalSyncCursorKey(userId)
            val previousCursor = readSyncCursor(cursorKey)
            while (true) {
                val pending = repository.pendingSync(SYNC_UPLOAD_BATCH_SIZE)
                if (pending.isEmpty()) break

                val remoteRows = loadPersonalDocumentsForEntityIds(
                    userId,
                    pending.map { it.entityId }
                )
                val remoteByKey = remoteRows.associateBy { "${it.entity_type}:${it.entity_id}" }
                val uploadable = mutableListOf<CloudDocumentRow>()
                val uploadableIds = mutableSetOf<Long>()
                val supersededIds = mutableListOf<Long>()
                val supersededKeys = mutableSetOf<String>()
                for (item in pending) {
                    val payloadTimestamp = runCatching {
                        JSONObject(item.payload).optLong("updated_at", item.createdAt)
                    }.getOrDefault(item.createdAt)
                    val entityKey = "${item.entityType}:${item.entityId}"
                    val remote = remoteByKey[entityKey]
                    if (!CloudConflictPolicy.shouldUploadLocal(
                        localUpdatedAt = payloadTimestamp,
                        remoteUpdatedAt = remote?.updated_at,
                        remoteDeleted = remote?.deleted == true,
                        localDeleted = item.operation == "delete",
                        allowDraftRecreation = item.entityType == "inspection_note_draft" ||
                            item.entityType == "inspection_form_draft"
                    )) {
                        supersededIds += item.id
                        supersededKeys += entityKey
                        continue
                    }
                    val isDelete = item.operation == "delete"
                    uploadable += CloudDocumentRow(
                        user_id = userId,
                        entity_type = item.entityType,
                        entity_id = item.entityId,
                        payload = item.payload,
                        updated_at = CloudConflictPolicy.effectiveUploadTimestamp(
                            payloadTimestamp,
                            remote?.updated_at,
                            isDelete,
                            allowDraftRecreation = item.entityType == "inspection_note_draft" ||
                                item.entityType == "inspection_form_draft"
                        ),
                        deleted = isDelete,
                        device_id = deviceId
                    )
                    uploadableIds += item.id
                }

                // Apply the cloud winner before acknowledging a stale/equal local outbox row.
                // This also reconciles a local copy when the cloud version was already covered
                // by the previous incremental cursor.
                if (supersededKeys.isNotEmpty()) {
                    val winningCloudRows = remoteRows.filter {
                        "${it.entity_type}:${it.entity_id}" in supersededKeys
                    }
                    if (winningCloudRows.isNotEmpty()) {
                        val knownNfcMutations = repository.nfcMutationSyncTimestamps()
                        val legacyNfcHiveIds = legacyNfcOnlyPersonalHiveSnapshotIds(
                            winningCloudRows,
                            knownMutations = knownNfcMutations
                        )
                        repository.applyCloudDocuments(
                            documents = CloudDocumentOrder.personal(winningCloudRows),
                            ignoreLegacyNfcProfileSnapshotHiveIds = legacyNfcHiveIds
                        )
                    }
                }
                repository.markSyncDone(supersededIds)
                if (uploadable.isNotEmpty()) {
                    client.from("beekeep_documents").upsert(uploadable) {
                        onConflict = "user_id,entity_type,entity_id"
                    }
                    // Only acknowledge uploads after the remote upsert succeeds.
                    repository.markSyncDone(uploadableIds)
                }
            }

            // Apiary batches are deliberately uploaded after the apiary/hive documents they
            // depend on, and before the incremental pull on this same selected dataset.
            uploadPendingActionBatches(workspaceId = null)

            // A fixed high-water mark makes the pull resumable. Any write that commits after
            // this read receives a higher server version and is picked up on the next sync.
            var syncHeadToCommit: Long? = null
            val documents = try {
                val headVersion = loadPersonalSyncHead()
                val afterVersion = previousCursor?.takeIf { it <= headVersion } ?: 0L
                loadPersonalChanges(afterVersion, headVersion).also {
                    syncHeadToCommit = headVersion
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error)) throw error
                // Keep cloud sync working while an environment is still on the pre-incremental
                // schema. This is the same deterministic, paginated pull used before the RPCs.
                loadAllPersonalDocuments().filter { it.user_id == userId }
            }
            val knownNfcMutations = repository.nfcMutationSyncTimestamps()
            val legacyNfcHiveIds = legacyNfcOnlyPersonalHiveSnapshotIds(
                documents,
                knownMutations = knownNfcMutations
            )
            repository.applyCloudDocuments(
                documents = CloudDocumentOrder.personal(documents),
                ignoreLegacyNfcProfileSnapshotHiveIds = legacyNfcHiveIds
            )
            // Only commit the incremental cursor after all downloaded changes were applied.
            // The legacy full-pull fallback intentionally leaves it untouched.
            syncHeadToCommit?.let { saveSyncCursor(cursorKey, it) }

            val settingsWarning = syncWorkspaceActionSettings(
                scopeKey = workspaceActionSettingsScopeKey(null, userId),
                workspaceId = null,
                userId = userId,
                canWrite = true
            )

            repository.pruneSync()
            val now = System.currentTimeMillis()
            _account.value = _account.value.copy(
                syncing = false,
                lastSyncAt = now,
                error = settingsWarning,
                signedIn = true,
                email = session.user?.email ?: ""
            )
            if (settingsWarning == null) CloudResult.Success else CloudResult.Failure(settingsWarning)
        } catch (t: CancellationException) {
            _account.value = _account.value.copy(syncing = false)
            throw t
        } catch (t: Throwable) {
            val message = TeamWorkspaceSyncError.userFacing(t.message)
            _account.value = _account.value.copy(syncing = false, error = message)
            CloudResult.Failure(message)
        }
    }


    /**
     * Explicitly copies the signed-in user's latest private cloud records into an owner/admin's
     * team workspace. Private sync must finish first. The server copy is atomic and refuses key
     * collisions, so an existing team record can never be silently overwritten.
     */
    suspend fun copyPersonalRecordsToWorkspace(workspaceId: String): WorkspaceOperationResult<Int> = syncMutex.withLock {
        val supabase = client
        if (!configured || supabase == null) {
            return@withLock WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        }
        val cleanId = workspaceId.trim()
        if (cleanId.isBlank()) return@withLock WorkspaceOperationResult(error = "Choose a team workspace first.")

        val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        val previousWorkspaceCandidate = prefs.getString("active_team_workspace_id", "")
            .orEmpty().trim().ifBlank { null }
        var previousWorkspaceId: String? = null
        val previousReadOnly = prefs.getBoolean("active_team_workspace_read_only", false)
        var recoveryReadOnly = previousReadOnly
        var recoveryUserId: String? = null
        var copyCompleted = false
        var copiedCount: Int? = null

        try {
            ensureAuthStorageInitialized(supabase)
            val session = supabase.auth.currentSessionOrNull()
                ?: return@withLock WorkspaceOperationResult(error = "Sign in before copying personal records.")
            val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@withLock WorkspaceOperationResult(error = "Your cloud session has no user ID. Sign in again.")
            recoveryUserId = userId
            val available = supabase.postgrest.rpc("beekeep_list_workspaces")
                .decodeList<BeeKeepWorkspace>()
            val target = available.firstOrNull { it.id == cleanId && !it.is_personal }
                ?: return@withLock WorkspaceOperationResult(error = "You no longer have access to that team workspace.")
            if (target.member_role !in setOf("owner", "admin")) {
                return@withLock WorkspaceOperationResult(error = "Only a workspace owner or admin can copy personal records into a team.")
            }

            // Restore the previous team only when the current account is still a member.
            // This prevents an error-recovery path from reopening another account's cached team.
            val previousWorkspace = previousWorkspaceCandidate?.let { oldId ->
                available.firstOrNull { it.id == oldId && !it.is_personal }
            }
            previousWorkspaceId = previousWorkspace?.id
            val previousRole = previousWorkspace?.member_role
            recoveryReadOnly = previousRole == "viewer" || (previousRole == null && previousReadOnly)
            suspend fun restorePreviousWorkspace() {
                repository.switchWorkspace(
                    previousWorkspaceId,
                    readOnly = previousRole == "viewer" || (previousRole == null && previousReadOnly),
                    ownerUserId = userId
                )
            }

            // Flush private/offline edits before asking the server to copy the private cloud rows.
            repository.switchWorkspace(null)
            when (val privateSync = syncNowUnlocked()) {
                CloudResult.Success -> Unit
                is CloudResult.Failure -> {
                    restorePreviousWorkspace()
                    return@withLock WorkspaceOperationResult(
                        error = "Personal records were not copied because private sync failed: ${privateSync.message}"
                    )
                }
                CloudResult.NotConfigured -> {
                    restorePreviousWorkspace()
                    return@withLock WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
                }
                CloudResult.NotSignedIn -> {
                    restorePreviousWorkspace()
                    return@withLock WorkspaceOperationResult(error = "Sign in before copying personal records.")
                }
            }

            val copy = supabase.postgrest.rpc(
                "beekeep_copy_personal_documents_to_workspace",
                WorkspaceIdParams(cleanId)
            ).decodeList<WorkspacePersonalCopyResult>().firstOrNull()
                ?: error("The server did not confirm the personal-data copy.")
            copiedCount = copy.copied_count
            copyCompleted = true

            // Make the imported team dataset the active local dataset and pull the server's result.
            repository.switchWorkspace(cleanId, readOnly = false, ownerUserId = userId)
            val teamSync = syncNowUnlocked()
            return@withLock when (teamSync) {
                CloudResult.Success -> WorkspaceOperationResult(value = copy.copied_count)
                is CloudResult.Failure -> WorkspaceOperationResult(
                    value = copy.copied_count,
                    error = "Copied ${copy.copied_count} records to the team, but the first download failed: ${teamSync.message}. Use Sync Now to retry."
                )
                CloudResult.NotConfigured -> WorkspaceOperationResult(
                    value = copy.copied_count,
                    error = "Copied ${copy.copied_count} records, but BeeKeep Cloud is no longer configured."
                )
                CloudResult.NotSignedIn -> WorkspaceOperationResult(
                    value = copy.copied_count,
                    error = "Copied ${copy.copied_count} records, but the session expired before the team download. Sign in and sync again."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!copyCompleted) {
                runCatching {
                    repository.switchWorkspace(
                        workspaceId = previousWorkspaceId,
                        readOnly = recoveryReadOnly,
                        ownerUserId = recoveryUserId
                    )
                }
            }
            return@withLock WorkspaceOperationResult(
                value = copiedCount,
                error = error.message?.takeIf { it.isNotBlank() }
                    ?: if (copyCompleted) "Records were copied, but team setup did not finish." else "Could not copy personal records to the team."
            )
        }
    }

    /**
     * Switches the app's active dataset to a team workspace or back to the user's personal data.
     * The repository stores team data in a separate Room file before the first team pull.
     */
    suspend fun activateWorkspace(workspaceId: String?): WorkspaceOperationResult<Unit> {
        val result = syncMutex.withLock {
            val supabase = client
            if (!configured || supabase == null) {
                return@withLock WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
            }
            try {
                ensureAuthStorageInitialized(supabase)
                val session = supabase.auth.currentSessionOrNull()
                if (session == null) {
                    refreshAccountUnlocked()
                    return@withLock WorkspaceOperationResult(error = "Sign in before switching workspaces.")
                }
                val userId = session.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@withLock WorkspaceOperationResult(error = "Your cloud session has no user ID. Sign in again.")
                val cleanId = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
                val workspace = if (cleanId == null) null else {
                    supabase.postgrest.rpc("beekeep_list_workspaces")
                        .decodeList<BeeKeepWorkspace>()
                        .firstOrNull { it.id == cleanId && !it.is_personal }
                        ?: error("You no longer have access to that team workspace.")
                }
                val ownerCommercialActive = if (workspace != null) {
                    hasActiveCommercialEntitlementForWorkspace(
                        supabase,
                        workspace.id,
                        workspace.member_role
                    )
                } else {
                    true
                }
                repository.switchWorkspace(
                    workspaceId = cleanId,
                    readOnly = WorkspaceAccessPolicy.isReadOnly(
                        memberRole = workspace?.member_role,
                        ownerCommercialEntitlementActive = ownerCommercialActive
                    ),
                    ownerUserId = userId
                )
                val syncResult = syncNowUnlocked()
                if (syncResult is CloudResult.Failure) {
                    WorkspaceOperationResult(
                        value = Unit,
                        error = "Workspace selected, but the first sync failed: ${syncResult.message}"
                    )
                } else {
                    WorkspaceOperationResult(value = Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                WorkspaceOperationResult(
                    error = TeamWorkspaceSyncError.userFacing(error.message ?: "Could not switch workspace.")
                )
            }
        }

        // Rebind Realtime after the Room database and active-workspace preference switch.
        // Do this outside the sync lock so the previous listener can finish its own sync.
        if (enableRealtime && _account.value.signedIn) startRealtime()
        return result
    }

    /**
     * Replays every locally recorded team mutation in insertion order. The database
     * trigger creates each Actions entry from the actual accepted document transition.
     * A stable action UUID makes a retry safe if the response is lost after commit.
     */
    private suspend fun uploadPendingWorkspaceActionChanges(workspaceId: String) {
        val supabase = client ?: throw IllegalStateException("BeeKeep Cloud is not configured.")
        while (true) {
            val pending = repository.pendingWorkspaceActionChanges(workspaceId, limit = 50)
            if (pending.isEmpty()) return
            if (repository.activeWorkspaceId.value != workspaceId) {
                throw IllegalStateException(
                    "The selected workspace changed before offline actions could sync. Switch back to the original workspace and sync again."
                )
            }

            try {
                val actions = buildJsonArray {
                    pending.forEach { action ->
                        add(buildJsonObject {
                            put("client_action_id", action.clientActionId)
                            put("entity_type", action.entityType)
                            put("entity_id", action.entityId)
                            put("operation", action.operation)
                            put("payload", action.payload)
                            put("action_at", action.actionAt)
                            put("device_id", deviceId)
                        })
                    }
                }
                val confirmation = supabase.postgrest.rpc(
                    "beekeep_replay_workspace_action_changes",
                    ReplayWorkspaceActionChangesParams(
                        target_workspace_id = workspaceId,
                        target_actions = actions
                    )
                ).decodeList<ReplayWorkspaceActionChangesResult>().singleOrNull()
                    ?: throw IllegalStateException(
                        "The server did not confirm the offline workspace actions."
                    )
                if (confirmation.processed_actions != pending.size) {
                    throw IllegalStateException(
                        "The server confirmed ${confirmation.processed_actions} of ${pending.size} offline workspace actions."
                    )
                }
                if (repository.activeWorkspaceId.value != workspaceId) {
                    throw IllegalStateException(
                        "The selected workspace changed while offline actions were syncing. The same actions will safely retry."
                    )
                }
                repository.completeWorkspaceActionChanges(pending.map { it.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                repository.markWorkspaceActionChangesError(
                    pending.map { it.id },
                    error.message ?: "Offline workspace actions could not sync."
                )
                throw error
            }
        }
    }

    private suspend fun syncSharedWorkspace(
        workspaceId: String,
        userId: String,
        userEmail: String
    ): CloudResult {
        val supabase = client ?: return CloudResult.NotConfigured
        try {
            val workspace = supabase.postgrest.rpc("beekeep_list_workspaces")
                .decodeList<BeeKeepWorkspace>()
                .firstOrNull { it.id == workspaceId && !it.is_personal }
            if (workspace == null) {
                // Do not silently change the selected dataset when the remote membership lookup
                // cannot find the team. Keep an account-bound cache selected but read-only; it
                // remains local and no outbox records are uploaded without verified membership.
                val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                val storedOwnerId = prefs.getString("active_team_workspace_user_id", null)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val keepSelectedTeam = ActiveWorkspaceSessionPolicy.canKeepCachedWorkspace(
                    workspaceId, userId, storedOwnerId
                )
                if (keepSelectedTeam) {
                    repository.switchWorkspace(
                        workspaceId = workspaceId,
                        readOnly = true,
                        ownerUserId = userId
                    )
                } else {
                    repository.switchWorkspace(null)
                }
                val message = if (keepSelectedTeam) {
                    "This team is still selected. BeeKeep couldn't verify team access, so its saved data is temporarily view-only. Reconnect or switch workspaces manually."
                } else {
                    "You no longer have access to this team workspace."
                }
                _account.value = _account.value.copy(syncing = false, signedIn = true, email = userEmail, error = message)
                return CloudResult.Failure(message)
            }

            val ownerCommercialActive = hasActiveCommercialEntitlementForWorkspace(
                supabase,
                workspaceId,
                workspace.member_role
            )
            val readOnly = WorkspaceAccessPolicy.isReadOnly(
                memberRole = workspace.member_role,
                ownerCommercialEntitlementActive = ownerCommercialActive
            )
            val isWritable = !readOnly
            // Reconcile both workspace identity and access mode. A downgraded owner must
            // not become writable again when a later sync refreshes only the member role.
            repository.switchWorkspace(
                workspaceId = workspaceId,
                readOnly = readOnly,
                ownerUserId = userId
            )
            if (isWritable) {
                // Do not queue new local drafts or upload pending edits for a read-only team.
                repository.discardLegacyNfcProfileSnapshots()
                repository.queuePendingInspectionNoteDrafts()
                repository.queuePendingInspectionFormDrafts()
            }
            val cursorKey = workspaceSyncCursorKey(userId, workspaceId)
            val previousCursor = readSyncCursor(cursorKey)
            if (isWritable) {
                // Replay immutable action snapshots before draining the coalescing document
                // outbox. This preserves repeated offline edits to the same record; the
                // ordinary outbox then reconciles to the final accepted cloud state.
                uploadPendingWorkspaceActionChanges(workspaceId)

                // Keep uploading bounded chunks until the selected workspace outbox drains.
                // Conflict checks now read only the cloud records for this pending outbox batch.
                while (true) {
                    val pending = repository.pendingSync(SYNC_UPLOAD_BATCH_SIZE)
                    if (pending.isEmpty()) break

                    val remoteRows = loadWorkspaceDocumentsForEntityIds(
                        workspaceId,
                        pending.map { it.entityId }
                    )
                    val remoteByKey = remoteRows.associateBy { "${it.entity_type}:${it.entity_id}" }
                    val uploadable = mutableListOf<CloudWorkspaceDocumentRow>()
                    val uploadableIds = mutableSetOf<Long>()
                    val supersededIds = mutableListOf<Long>()
                    val supersededKeys = mutableSetOf<String>()
                    for (item in pending) {
                        val payloadTimestamp = runCatching {
                            JSONObject(item.payload).optLong("updated_at", item.createdAt)
                        }.getOrDefault(item.createdAt)
                        val entityKey = "${item.entityType}:${item.entityId}"
                        val remote = remoteByKey[entityKey]
                        if (!CloudConflictPolicy.shouldUploadLocal(
                        localUpdatedAt = payloadTimestamp,
                        remoteUpdatedAt = remote?.updated_at,
                        remoteDeleted = remote?.deleted == true,
                        localDeleted = item.operation == "delete",
                        allowDraftRecreation = item.entityType == "inspection_note_draft" ||
                            item.entityType == "inspection_form_draft"
                    )) {
                            supersededIds += item.id
                            supersededKeys += entityKey
                            continue
                        }
                        val isDelete = item.operation == "delete"
                        uploadable += CloudWorkspaceDocumentRow(
                            workspace_id = workspaceId,
                            entity_type = item.entityType,
                            entity_id = item.entityId,
                            payload = item.payload,
                            updated_at = CloudConflictPolicy.effectiveUploadTimestamp(
                                payloadTimestamp,
                                remote?.updated_at,
                                isDelete,
                                allowDraftRecreation = item.entityType == "inspection_note_draft" ||
                                    item.entityType == "inspection_form_draft"
                            ),
                            deleted = isDelete,
                            device_id = deviceId,
                            updated_by = userId
                        )
                        uploadableIds += item.id
                    }

                    // Reconcile Room with each cloud winner before acknowledging stale/equal
                    // local outbox rows. This also handles rows already covered by the cursor.
                    if (supersededKeys.isNotEmpty()) {
                        val winningCloudRows = CloudDocumentOrder.workspace(
                            remoteRows.filter {
                                "${it.entity_type}:${it.entity_id}" in supersededKeys
                            }
                        ).map { document ->
                            CloudDocumentRow(
                                user_id = userId,
                                entity_type = document.entity_type,
                                entity_id = document.entity_id,
                                payload = document.payload,
                                updated_at = document.updated_at,
                                deleted = document.deleted,
                                device_id = document.device_id
                            )
                        }
                        if (winningCloudRows.isNotEmpty()) {
                            val legacyNfcHiveIds = legacyNfcOnlyWorkspaceHiveSnapshotIds(
                                remoteRows.filter {
                                    "${it.entity_type}:${it.entity_id}" in supersededKeys
                                },
                                knownMutations = repository.nfcMutationSyncTimestamps()
                            )
                            repository.applyCloudDocuments(
                                documents = winningCloudRows,
                                ignoreLegacyNfcProfileSnapshotHiveIds = legacyNfcHiveIds
                            )
                        }
                    }
                    repository.markSyncDone(supersededIds)
                    if (uploadable.isNotEmpty()) {
                        supabase.from("beekeep_workspace_documents").upsert(uploadable) {
                            onConflict = "workspace_id,entity_type,entity_id"
                        }
                        // Only acknowledge uploads after the remote upsert succeeds.
                        repository.markSyncDone(uploadableIds)
                    }
                }
                // The normal document outbox is drained first so cloud apiary/hive identity
                // checks see the newest records before the atomic action transaction runs.
                uploadPendingActionBatches(workspaceId)
            }

            // Prefer server-versioned incremental pages. Older or partially migrated
            // environments retain the previous safe full, paginated pull until all RPCs exist.
            var syncHeadToCommit: Long? = null
            val workspaceDocuments = try {
                val headVersion = loadWorkspaceSyncHead(workspaceId)
                val afterVersion = previousCursor?.takeIf { it <= headVersion } ?: 0L
                loadWorkspaceChanges(workspaceId, afterVersion, headVersion).also {
                    syncHeadToCommit = headVersion
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!IncrementalSyncCompatibility.isMissingIncrementalSyncRpc(error)) throw error
                loadAllWorkspaceDocuments(workspaceId)
                    .filter { it.workspace_id == workspaceId }
            }
            val knownNfcMutations = repository.nfcMutationSyncTimestamps()
            val legacyNfcHiveIds = legacyNfcOnlyWorkspaceHiveSnapshotIds(
                workspaceDocuments,
                knownMutations = knownNfcMutations
            )
            val localDocuments = CloudDocumentOrder.workspace(workspaceDocuments).map { document ->
                CloudDocumentRow(
                    user_id = userId,
                    entity_type = document.entity_type,
                    entity_id = document.entity_id,
                    payload = document.payload,
                    updated_at = document.updated_at,
                    deleted = document.deleted,
                    device_id = document.device_id
                )
            }
            repository.applyCloudDocuments(
                documents = localDocuments,
                ignoreLegacyNfcProfileSnapshotHiveIds = legacyNfcHiveIds
            )
            // The legacy fallback has no server cursor; do not advance an incremental cursor.
            syncHeadToCommit?.let { saveSyncCursor(cursorKey, it) }

            val canWriteSettings = isWritable && workspace.member_role in setOf("owner", "admin")
            val settingsWarning = syncWorkspaceActionSettings(
                scopeKey = workspaceActionSettingsScopeKey(workspaceId, userId),
                workspaceId = workspaceId,
                userId = userId,
                canWrite = canWriteSettings
            )

            repository.pruneSync()
            val now = System.currentTimeMillis()
            val documentWarning = if (
                !isWritable && (repository.pendingSync(1).isNotEmpty() || repository.hasPendingActionBatches())
            ) {
                "This workspace is view-only. Pending local edits were not uploaded."
            } else null
            val warning = listOfNotNull(documentWarning, settingsWarning)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
            _account.value = _account.value.copy(
                syncing = false,
                lastSyncAt = now,
                error = warning,
                signedIn = true,
                email = userEmail
            )
            return if (warning == null) CloudResult.Success else CloudResult.Failure(warning)
        } catch (cancelled: CancellationException) {
            _account.value = _account.value.copy(syncing = false)
            throw cancelled
        } catch (error: Throwable) {
            val message = TeamWorkspaceSyncError.userFacing(error.message)
            _account.value = _account.value.copy(syncing = false, error = message)
            return CloudResult.Failure(message)
        }
    }

    /**
     * Accepts Supabase verification links returning to this build's app-specific URI scheme.
     * Called from MainActivity for both cold starts and singleTask onNewIntent callbacks.
     */
    fun handleAuthDeepLink(intent: Intent?) {
        val supabase = client ?: return
        val uri = intent?.data ?: return
        if (uri.scheme != BuildConfig.AUTH_REDIRECT_SCHEME ||
            uri.host != BuildConfig.AUTH_REDIRECT_HOST
        ) return

        val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
        val pendingResetEmail = prefs.getString(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF, null)
        val pendingResetAt = prefs.getLong(PENDING_PASSWORD_RESET_REQUEST_AT_PREF, 0L)
        val pendingResetAge = System.currentTimeMillis() - pendingResetAt
        val pendingResetIsFresh = pendingResetAt > 0L &&
            pendingResetAge in 0L..PASSWORD_RESET_REQUEST_MAX_AGE_MS
        val explicitRecoveryType = AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
            callbackUrl = uri.toString(),
            recoveryRequestPending = false
        )
        val isPasswordRecovery = explicitRecoveryType || AuthRecoveryCallbackPolicy.isPasswordRecoveryCallback(
            callbackUrl = uri.toString(),
            recoveryRequestPending = pendingResetIsFresh
        )

        // Wait for stored credentials before processing the link. Only a successful session
        // callback can enable password changes; an expired link cannot reuse an older session.
        scope.launch {
            try {
                ensureAuthStorageInitialized(supabase)
                supabase.handleDeeplinks(
                    intent = intent,
                    onSessionSuccess = { userSession ->
                        if (isPasswordRecovery) {
                            val recoveredEmail = userSession.user?.email?.trim()
                            val requestedEmailMatches = !pendingResetEmail.isNullOrBlank() &&
                                !recoveredEmail.isNullOrBlank() &&
                                pendingResetEmail.equals(recoveredEmail, ignoreCase = true)
                            if (explicitRecoveryType || requestedEmailMatches) {
                                prefs.edit()
                                    .putBoolean(PASSWORD_RECOVERY_SESSION_PREF, true)
                                    .remove(PENDING_PASSWORD_RESET_REQUEST_EMAIL_PREF)
                                    .remove(PENDING_PASSWORD_RESET_REQUEST_AT_PREF)
                                    .apply()
                                _passwordRecoveryRequired.value = true
                            } else {
                                clearPendingPasswordResetRequest()
                                _account.value = _account.value.copy(
                                    error = "This password-reset link is invalid or expired. Request a new link and try again."
                                )
                            }
                        } else {
                            clearPendingPasswordResetRequest()
                        }
                        scope.launch { refreshAccount() }
                    },
                    onError = { error ->
                        if (isPasswordRecovery) clearPendingPasswordResetRequest()
                        _account.value = _account.value.copy(
                            error = TeamWorkspaceSyncError.userFacing(
                                error.message ?: "Could not complete email verification"
                            )
                        )
                        scope.launch { refreshAccount() }
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (isPasswordRecovery) clearPendingPasswordResetRequest()
                _account.value = _account.value.copy(
                    error = TeamWorkspaceSyncError.userFacing(t.message ?: "Could not complete email verification")
                )
                refreshAccount()
            }
        }
    }


    // Stage 2 workspace administration uses SECURITY DEFINER database functions so the
    // mobile client never writes membership or invitation records directly.
    suspend fun listWorkspaces(): WorkspaceOperationResult<List<BeeKeepWorkspace>> =
        workspaceOperation {
            client!!.postgrest.rpc("beekeep_list_workspaces").decodeList<BeeKeepWorkspace>()
        }

    /** Owner-only Workspace Actions feed. The database RLS is the final access boundary. */
    suspend fun listWorkspaceActionLog(
        workspaceId: String
    ): WorkspaceOperationResult<List<BeeKeepWorkspaceActionLogEntry>> = workspaceOperation {
        require(workspaceId.isNotBlank()) { "A team workspace is required." }
        client!!.from("beekeep_workspace_action_log")
            .select {
                filter { eq("workspace_id", workspaceId) }
                order(column = "action_at", order = Order.DESCENDING)
                order(column = "id", order = Order.DESCENDING)
                limit(200)
            }
            .decodeList<BeeKeepWorkspaceActionLogEntry>()
    }

    /** Reads the workspace's owner-controlled retention mode; missing rows mean Keep. */
    suspend fun getWorkspaceActionLogSettings(
        workspaceId: String
    ): WorkspaceOperationResult<BeeKeepWorkspaceActionLogSettings?> = workspaceOperation {
        require(workspaceId.isNotBlank()) { "A team workspace is required." }
        client!!.from("beekeep_workspace_action_log_settings")
            .select { filter { eq("workspace_id", workspaceId) } }
            .decodeList<BeeKeepWorkspaceActionLogSettings>()
            .firstOrNull()
    }

    suspend fun setWorkspaceActionLogRetention(
        workspaceId: String,
        retentionMode: String
    ): WorkspaceOperationResult<String> = workspaceOperation {
        require(retentionMode in setOf("keep", "expire_90_days")) { "Choose a supported retention setting." }
        client!!.postgrest.rpc(
            "beekeep_workspace_action_log_set_retention",
            WorkspaceActionRetentionParams(workspaceId, retentionMode)
        ).decodeSingle<String>()
    }

    suspend fun deleteWorkspaceActionLogEntry(
        entryId: String
    ): WorkspaceOperationResult<Boolean> = workspaceOperation {
        require(entryId.isNotBlank()) { "An activity entry is required." }
        client!!.postgrest.rpc(
            "beekeep_workspace_action_log_delete",
            WorkspaceActionDeleteParams(entryId)
        ).decodeSingle<Boolean>()
    }

    /**
     * Reads the signed-in account's server-managed Commercial entitlement. The database derives
     * this from trusted auth app metadata; local plan simulation is never an authorization source.
     */
    suspend fun myActiveCommercialEntitlement(): WorkspaceOperationResult<Boolean> =
        workspaceOperation {
            val supabase = client ?: error("BeeKeep Cloud is not configured.")
            val userId = supabase.auth.currentSessionOrNull()?.user?.id
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("Sign in to BeeKeep Cloud to refresh your Commercial access.")

            // Use the same trusted, complete entitlement response that powers the plan limits.
            // This also refreshes the cached app plan and custom hive allowance after an admin
            // grant/revoke, instead of updating only a separate boolean permission check.
            val entitlement = supabase.postgrest.rpc("beekeep_my_hive_entitlement")
                .decodeList<BeeKeepServerEntitlement>()
                .firstOrNull()
                ?: error("The server did not return your BeeKeep entitlement.")

            val verifiedPlan = BeeKeepEntitlements.planFromServerEntitlement(
                planCode = entitlement.planCode,
                baseHiveLimit = entitlement.baseHiveLimit,
                isActivePaidPlan = entitlement.isActivePaidPlan
            )
            val verifiedHiveOverride = entitlement.customHiveLimit?.takeIf {
                it in BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT..BeeKeepEntitlements.MAX_CUSTOM_HIVE_LIMIT
            }

            verifiedPlanUserId = userId
            _verifiedPlan.value = verifiedPlan
            _hiveLimitOverride.value = verifiedHiveOverride
            BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, userId)
            BeeKeepEntitlements.updateCloudVerifiedPlan(appContext, userId, verifiedPlan)
            BeeKeepEntitlements.updateCloudHiveLimitOverride(appContext, userId, verifiedHiveOverride)

            verifiedPlan == BeeKeepPlan.COMMERCIAL
        }

    @Deprecated("Use myActiveCommercialEntitlement")
    suspend fun myActiveUltimateEntitlement(): WorkspaceOperationResult<Boolean> =
        myActiveCommercialEntitlement()

    /**
     * Reads the owner's active plan and exact server quota for the selected shared workspace.
     * Membership is checked inside the SECURITY DEFINER RPC; invitees cannot supply an owner ID.
     */
    suspend fun workspaceOwnerQuotaEntitlement(
        workspaceId: String
    ): WorkspaceOperationResult<BeeKeepWorkspaceOwnerQuotaEntitlement> = workspaceOperation {
        require(workspaceId.isNotBlank()) { "A workspace is required to check its quota." }
        client!!.postgrest.rpc(
            "beekeep_workspace_owner_quota_entitlement",
            buildJsonObject { put("target_workspace_id", workspaceId) }
        ).decodeList<BeeKeepWorkspaceOwnerQuotaEntitlement>().firstOrNull()
            ?: error("Workspace owner quota was not returned.")
    }

    /**
     * Reads the trusted Commercial entitlement of a specific shared workspace owner.
     * The server returns a result only when the signed-in account is a member of that workspace.
     */
    suspend fun workspaceOwnerCommercialEntitlement(
        workspaceId: String
    ): WorkspaceOperationResult<Boolean> = workspaceOperation {
        require(workspaceId.isNotBlank()) { "A workspace is required to check its subscription." }
        client!!.postgrest.rpc(
            "beekeep_workspace_owner_commercial_entitlement",
            buildJsonObject { put("target_workspace_id", workspaceId) }
        ).decodeList<BeeKeepUltimateEntitlement>().firstOrNull()?.active
            ?: error("Workspace owner entitlement was not returned.")
    }

    /**
     * Debug purchase simulation is synced only to the isolated staging Edge Function.
     * The function itself rejects every project except BeeKeep-Staging and cannot grant
     * entitlement on production. Release builds never call this method.
     */
    suspend fun syncDebugPlanToStaging(plan: BeeKeepPlan): WorkspaceOperationResult<Boolean> {
        if (!BuildConfig.DEBUG) {
            return WorkspaceOperationResult(error = "Debug plan simulation is unavailable in release builds.")
        }
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        if (supabase.auth.currentSessionOrNull() == null) {
            return WorkspaceOperationResult(error = "Sign in to BeeKeep Cloud to sync the staging test entitlement.")
        }
        val serverPlan = when (plan) {
            BeeKeepPlan.HOBBYIST -> "hobbyist"
            BeeKeepPlan.COMMERCIAL -> "commercial_monthly"
        }
        return try {
            val response = supabase.functions.invoke(
                function = "beekeep-debug-entitlement",
                body = buildJsonObject { put("plan", serverPlan) }
            ).body<DebugEntitlementSyncResponse>()
            if (!response.success || response.plan != serverPlan) {
                WorkspaceOperationResult(error = "The staging test entitlement could not be updated.")
            } else {
                WorkspaceOperationResult(value = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "The staging test entitlement could not be updated."
                )
            )
        }
    }

    /**
     * Asks the protected server endpoint whether the current account has the trusted support-admin flag.
     * The UI may hide the entry point, but the Edge Function independently authorizes every request.
     */
    suspend fun checkSupportAdmin(): WorkspaceOperationResult<Boolean> {
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        return try {
            ensureAuthStorageInitialized(supabase)
            if (supabase.auth.currentSessionOrNull() == null) {
                return WorkspaceOperationResult(value = false)
            }
            val response = supabase.functions.invoke(
                function = "beekeep-admin-hive-limits",
                body = buildJsonObject { put("action", "check") }
            ).body<SupportAdminHiveLimitResponse>()
            if (!response.success) {
                WorkspaceOperationResult(error = response.error ?: "Could not verify support-admin access.")
            } else {
                WorkspaceOperationResult(value = response.allowed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(error.message ?: "Could not verify support-admin access.")
            )
        }
    }

    /** Performs a server-authorized exact-email lookup, set, or clear operation. */
    suspend fun manageSupportHiveLimit(
        action: String,
        email: String,
        hiveLimit: Int?,
        reason: String?
    ): WorkspaceOperationResult<SupportAdminHiveLimitResponse> {
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        if (action !in setOf("lookup", "set", "clear")) {
            return WorkspaceOperationResult(error = "Unsupported support-admin action.")
        }
        return try {
            ensureAuthStorageInitialized(supabase)
            if (supabase.auth.currentSessionOrNull() == null) {
                refreshAccount()
                return WorkspaceOperationResult(error = "Sign in before using support-admin tools.")
            }
            val body = buildJsonObject {
                put("action", action)
                put("email", email.trim())
                if (hiveLimit != null) put("hive_limit", hiveLimit)
                if (reason != null) put("reason", reason.trim())
            }
            val response = supabase.functions.invoke(
                function = "beekeep-admin-hive-limits",
                body = body
            ).body<SupportAdminHiveLimitResponse>()
            if (!response.success || response.accountEmail.isNullOrBlank()) {
                WorkspaceOperationResult(
                    error = response.error ?: "The support-admin request did not return an account."
                )
            } else {
                WorkspaceOperationResult(value = response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message
                        ?: "The support-admin request could not be completed. Verify the deployed server function and admin access."
                )
            )
        }
    }

    /** Looks up, grants, or revokes a trusted manual Commercial entitlement through the protected Edge Function. */
    suspend fun manageSupportCommercialAccess(
        action: String,
        email: String,
        expiresInDays: Int?,
        reason: String?
    ): WorkspaceOperationResult<SupportAdminCommercialAccessResponse> {
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        if (action !in setOf("commercial_lookup", "commercial_grant", "commercial_revoke")) {
            return WorkspaceOperationResult(error = "Unsupported Commercial access action.")
        }
        val cleanedReason = reason?.trim().orEmpty()
        if (action == "commercial_grant" &&
            ((expiresInDays != null && expiresInDays !in 1..3650) ||
                cleanedReason.length !in 3..500)
        ) {
            return WorkspaceOperationResult(
                error = "Enter a reason and choose permanent access or a duration between 1 and 3,650 days."
            )
        }
        return try {
            ensureAuthStorageInitialized(supabase)
            if (supabase.auth.currentSessionOrNull() == null) {
                refreshAccount()
                return WorkspaceOperationResult(error = "Sign in before using support-admin tools.")
            }
            val body = buildJsonObject {
                put("action", action)
                put("email", email.trim())
                if (expiresInDays != null) put("expires_in_days", expiresInDays)
                if (cleanedReason.isNotEmpty()) put("reason", cleanedReason)
            }
            val response = supabase.functions.invoke(
                function = "beekeep-admin-hive-limits",
                body = body
            ).body<SupportAdminCommercialAccessResponse>()
            if (!response.success || response.accountEmail.isNullOrBlank()) {
                WorkspaceOperationResult(
                    error = response.error ?: "The Commercial access request did not return an account."
                )
            } else {
                WorkspaceOperationResult(value = response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message
                        ?: "The Commercial access request could not be completed. Verify the SQL setup, Edge Function deployment and admin access."
                )
            )
        }
    }

    suspend fun createWorkspace(name: String): WorkspaceOperationResult<BeeKeepWorkspace> =
        workspaceOperation {
            client!!.postgrest.rpc("beekeep_create_workspace", WorkspaceNameParams(name.trim()))
                .decodeList<BeeKeepWorkspace>().firstOrNull()
                ?: error("Workspace was not returned after creation")
        }

    /**
     * Checks handle availability before account creation. This endpoint deliberately supports
     * signed-out users and returns only a boolean; it never exposes profile rows.
     */
    suspend fun isUsernameAvailable(username: String): WorkspaceOperationResult<Boolean> {
        val supabase = client
            ?: return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        val cleanedUsername = username.trim()
        if (!cleanedUsername.matches(Regex("^[A-Za-z0-9_]{3,20}$"))) {
            return WorkspaceOperationResult(
                error = "Username must be 3–20 characters using only letters, numbers, or underscores."
            )
        }

        return try {
            val availability = supabase.postgrest.rpc(
                "beekeep_is_username_available",
                WorkspaceUsernameParams(cleanedUsername)
            ).decodeList<BeeKeepUsernameAvailability>().firstOrNull()
                ?: error("Username availability was not returned.")
            WorkspaceOperationResult(value = availability.available)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = "Couldn't check that username right now. Please try again."
            )
        }
    }

    suspend fun getUsername(): WorkspaceOperationResult<String> =
        workspaceOperation {
            client!!.postgrest.rpc("beekeep_get_username")
                .decodeList<BeeKeepUsername>().firstOrNull()?.username.orEmpty()
        }

    suspend fun setUsername(username: String): WorkspaceOperationResult<String> {
        val result = workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_set_username",
                WorkspaceUsernameParams(username.trim())
            ).decodeList<BeeKeepUsername>().firstOrNull()?.username
                ?: error("Username was not returned after saving")
        }
        val savedUsername = result.value?.trim().orEmpty()
        val userId = _account.value.userId?.trim().orEmpty()
        if (result.isSuccess && savedUsername.isNotBlank() && userId.isNotBlank()) {
            val current = _accountSetupState.value
            val workspace = when (current) {
                is BeeKeepAccountSetupState.UsernameRequired ->
                    current.personalWorkspaceId to current.personalWorkspaceName
                is BeeKeepAccountSetupState.Ready ->
                    current.personalWorkspaceId to current.personalWorkspaceName
                else -> null
            }
            if (workspace != null) {
                cacheAccountSetup(userId, savedUsername, workspace.first, workspace.second)
                _accountSetupState.value = BeeKeepAccountSetupState.Ready(
                    username = savedUsername,
                    personalWorkspaceId = workspace.first,
                    personalWorkspaceName = workspace.second
                )
            }
        }
        return result
    }

    suspend fun deleteWorkspace(workspaceId: String): WorkspaceOperationResult<Unit> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_delete_workspace",
                WorkspaceIdParams(workspaceId)
            )
            Unit
        }

    suspend fun listWorkspaceMembers(workspaceId: String): WorkspaceOperationResult<List<BeeKeepWorkspaceMember>> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_list_workspace_members",
                WorkspaceIdParams(workspaceId)
            ).decodeList<BeeKeepWorkspaceMember>()
        }

    suspend fun listWorkspaceInvitations(workspaceId: String): WorkspaceOperationResult<List<BeeKeepWorkspaceInvitation>> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_list_workspace_invitations",
                WorkspaceIdParams(workspaceId)
            ).decodeList<BeeKeepWorkspaceInvitation>()
        }

    suspend fun createWorkspaceInvitation(
        workspaceId: String,
        email: String,
        role: String
    ): WorkspaceOperationResult<BeeKeepWorkspaceInvitationCreated> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_create_workspace_invitation",
                WorkspaceInviteParams(workspaceId, email.trim(), role)
            ).decodeList<BeeKeepWorkspaceInvitationCreated>().firstOrNull()
                ?: error("Invitation was not returned after creation")
        }

    suspend fun acceptWorkspaceInvitation(token: String): WorkspaceOperationResult<BeeKeepWorkspaceJoinResult> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_accept_workspace_invitation",
                WorkspaceInvitationTokenParams(token.trim())
            ).decodeList<BeeKeepWorkspaceJoinResult>().firstOrNull()
                ?: error("No matching invitation was accepted. Check the code, confirm it has not expired, and make sure you are signed in with the email address the invitation was sent to. Too many failed attempts can temporarily block code entry.")
        }

    suspend fun cancelWorkspaceInvitation(invitationId: String): WorkspaceOperationResult<Unit> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_cancel_workspace_invitation",
                WorkspaceInvitationIdParams(invitationId)
            )
            Unit
        }

    suspend fun updateWorkspaceMemberRole(
        workspaceId: String,
        userId: String,
        role: String
    ): WorkspaceOperationResult<Unit> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_update_workspace_member_role",
                WorkspaceMemberRoleParams(workspaceId, userId, role)
            )
            Unit
        }

    suspend fun removeWorkspaceMember(
        workspaceId: String,
        userId: String
    ): WorkspaceOperationResult<Unit> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_remove_workspace_member",
                WorkspaceRemoveMemberParams(workspaceId, userId)
            )
            Unit
        }

    private suspend fun <T> workspaceOperation(block: suspend () -> T): WorkspaceOperationResult<T> {
        val supabase = client
        if (!configured || supabase == null) {
            return WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        }
        if (supabase.auth.currentSessionOrNull() == null) {
            refreshAccount()
            return WorkspaceOperationResult(error = "Sign in to manage workspaces.")
        }
        return try {
            WorkspaceOperationResult(value = block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkspaceOperationResult(
                error = TeamWorkspaceSyncError.userFacing(
                    error.message ?: "The workspace request failed. Please try again."
                )
            )
        }
    }

    private suspend fun authCall(
        restoreWorkspaceId: String? = null,
        block: suspend () -> Unit
    ): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured

        // The login/signup screen is replaced as soon as refreshAccountUnlocked marks startup
        // as pending. Run the session transition in this gateway-owned scope so disposing that
        // Compose screen cannot cancel the operation and strand accountInitialized=false.
        return authOperationRunner.run {
            val supabase = client ?: return@run CloudResult.NotConfigured
            try {
                ensureAuthStorageInitialized(supabase)
                // Authentication can replace the signed-in account. Return to private/local data
                // before that transition so a cached team dataset is not displayed to the next account.
                // Serialize account transitions with sync so a background worker cannot
                // keep applying documents while the active database is being changed.
                syncMutex.withLock {
                    repository.switchWorkspace(null)
                    block()
                    refreshAccountUnlocked()
                }
                val workspaceToRestore = restoreWorkspaceId?.trim()?.takeIf { it.isNotEmpty() }
                if (_account.value.signedIn && workspaceToRestore != null) {
                    // Authentication first switches to private data for account isolation. Restore
                    // the previous team only after the newly authenticated user passes membership checks.
                    val restored = activateWorkspace(workspaceToRestore)
                    if (!restored.isSuccess) {
                        _account.value = _account.value.copy(
                            error = "Signed in, but the previous team workspace could not be restored: " +
                                (restored.error ?: "you may no longer be a member")
                        )
                    }
                }
                if (enableRealtime && _account.value.signedIn) startRealtime()
                CloudResult.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val rawMessage = t.message.orEmpty().trim()
                val lowerMessage = rawMessage.lowercase()
                val message = when {
                    "already registered" in lowerMessage ||
                        "an account with this email already exists" in lowerMessage ->
                        "An account with this email already exists. Please sign in instead."
                    "beekeep_profiles_username_lower_idx" in lowerMessage ||
                        ("username" in lowerMessage && "already taken" in lowerMessage) ->
                        "That username is already taken. Please choose another."
                    rawMessage.isBlank() ||
                        "unknown error" in lowerMessage ||
                        "headers:" in lowerMessage ||
                        "url:" in lowerMessage ||
                        rawMessage.length > 240 ->
                        "BeeKeep couldn't complete authentication. Check your connection and account details, then try again."
                    else -> TeamWorkspaceSyncError.userFacing(rawMessage)
                }
                _account.value = _account.value.copy(error = message)
                CloudResult.Failure(message)
            }
        }
    }
}
