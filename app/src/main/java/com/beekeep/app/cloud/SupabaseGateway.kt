package com.beekeep.app.cloud

import android.content.Context
import android.content.Intent
import com.beekeep.app.BuildConfig
import com.beekeep.app.BeeKeepPlan
import com.beekeep.app.BeeKeepEntitlements
import com.beekeep.app.data.LocalHiveRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.Email
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import io.ktor.client.call.body
import java.util.UUID
import org.json.JSONObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface CloudResult {
    data object Success : CloudResult
    data class Failure(val message: String) : CloudResult
    data object NotConfigured : CloudResult
    data object NotSignedIn : CloudResult
}

private const val SYNC_UPLOAD_BATCH_SIZE = 200
private const val SYNC_DOWNLOAD_PAGE_SIZE = 500
private const val SYNC_CONFLICT_LOOKUP_BATCH_SIZE = 100

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
     * Restore a selected team only for the account that selected it. A legacy selection without
     * an owner ID is restored only after a successful membership lookup; offline, the app stays
     * on private/local data until the identity can be verified.
     */
    private suspend fun refreshAccountUnlocked() {
        val supabase = client
        if (!configured || supabase == null) {
            stopRealtime()
            repository.switchWorkspace(null)
            BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, null)
            _hiveLimitOverride.value = null
            _verifiedPlan.value = null
            verifiedPlanUserId = null
            _account.value = CloudAccountState(configured = false, signedIn = false)
            return
        }
        try {
            // Wait until stored credentials have been restored before showing signed-out UI.
            supabase.auth.awaitInitialization()
            val session = supabase.auth.currentSessionOrNull()
            val userId = session?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
            if (userId == null) {
                stopRealtime()
                repository.switchWorkspace(null)
                BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, null)
                _hiveLimitOverride.value = null
                _verifiedPlan.value = null
                verifiedPlanUserId = null
            } else {
                // Load only this account's previously server-verified plan before trying the network.
                // Release ViewModel creation checks use the same cached entitlement as the plan UI.
                BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, userId)
                refreshVerifiedPlanForUser(userId)
                refreshHiveLimitOverrideForUser(userId)
                restoreWorkspaceForAccount(supabase, userId)
            }
            _account.value = CloudAccountState(
                configured = true,
                signedIn = userId != null,
                email = session?.user?.email ?: "",
                userId = userId
            )
            if (enableRealtime && userId != null) startRealtime() else stopRealtime()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // If session identity cannot be established, fail closed to private/local data.
            stopRealtime()
            repository.switchWorkspace(null)
            BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, null)
            _hiveLimitOverride.value = null
            _verifiedPlan.value = null
            verifiedPlanUserId = null
            _account.value = CloudAccountState(configured = true, signedIn = false, error = error.message)
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
                repository.switchWorkspace(null)
                return
            }
        }

        val memberRole = memberWorkspace?.member_role
        val ownerCommercialActive = if (membershipLookupCompleted && memberRole == "owner") {
            hasActiveCommercialEntitlementForWorkspaceOwner(supabase)
        } else {
            true
        }
        val readOnly = if (membershipLookupCompleted) {
            memberRole == "viewer" || (memberRole == "owner" && !ownerCommercialActive)
        } else {
            // During an outage preserve the role-based read-only state already recorded
            // for this account; the owner entitlement is rechecked as soon as online.
            prefs.getBoolean("active_team_workspace_read_only", false)
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

    suspend fun signIn(email: String, password: String): CloudResult = authCall(restoreWorkspaceId = repository.activeWorkspaceId.value) {
        val supabase = client ?: return@authCall
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String, username: String = ""): CloudResult = authCall {
        val supabase = client ?: return@authCall
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                if (username.isNotBlank()) put("username", username.trim())
            }
        }
    }

    suspend fun signOut(): CloudResult = syncMutex.withLock { signOutUnlocked() }

    private suspend fun signOutUnlocked(): CloudResult = try {
        val supabase = client ?: return CloudResult.NotConfigured
        stopRealtime()
        // Never leave another account viewing the previously selected team's local cache.
        repository.switchWorkspace(null)
        supabase.auth.signOut()
        refreshAccountUnlocked()
        CloudResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        _account.value = _account.value.copy(syncing = false, error = t.message ?: "Sign out failed")
        CloudResult.Failure(t.message ?: "Sign out failed")
    }

    /**
     * Permanently clears this signed-in workspace in cloud sync. Deletion tombstones are
     * retained instead of simply dropping rows, so another device won't restore old records.
     */
    suspend fun resetWorkspace(): CloudResult = syncMutex.withLock {
        val supabase = client ?: return@withLock CloudResult.NotConfigured
        try {
            supabase.auth.awaitInitialization()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withLock CloudResult.Failure(error.message ?: "Could not restore your cloud session")
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
            val message = error.message ?: "Cloud workspace reset failed"
            _account.value = _account.value.copy(syncing = false, error = message)
            CloudResult.Failure(message)
        }
    }

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
                            val changes = if (workspaceId.isBlank()) {
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

                            supabase.realtime.connect()
                            channel.subscribe()
                            _account.value = _account.value.copy(error = null)
                            backoffMs = 1_000L
                            changes.debounce(500).collect {
                                syncNow()
                            }
                        } finally {
                            runCatching { supabase.realtime.removeAllChannels() }
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        _account.value = _account.value.copy(error = "Live sync unavailable: ${t.message ?: "connection failed"}")
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

    suspend fun syncNow(): CloudResult = syncMutex.withLock { syncNowUnlocked() }

    private suspend fun syncNowUnlocked(): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured
        try {
            client.auth.awaitInitialization()
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

            repository.pruneSync()
            val now = System.currentTimeMillis()
            _account.value = _account.value.copy(
                syncing = false,
                lastSyncAt = now,
                error = null,
                signedIn = true,
                email = session.user?.email ?: ""
            )
            CloudResult.Success
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
            supabase.auth.awaitInitialization()
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
                supabase.auth.awaitInitialization()
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
                val ownerCommercialActive = if (workspace?.member_role == "owner") {
                    hasActiveCommercialEntitlementForWorkspaceOwner(supabase)
                } else {
                    true
                }
                repository.switchWorkspace(
                    workspaceId = cleanId,
                    readOnly = workspace?.member_role == "viewer" ||
                        (workspace?.member_role == "owner" && !ownerCommercialActive),
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
                WorkspaceOperationResult(error = error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not switch workspace.")
            }
        }

        // Rebind Realtime after the Room database and active-workspace preference switch.
        // Do this outside the sync lock so the previous listener can finish its own sync.
        if (enableRealtime && _account.value.signedIn) startRealtime()
        return result
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
                // The membership was revoked or the selected workspace was deleted.
                // Keep the cached team database intact, but stop displaying it as the active dataset.
                repository.switchWorkspace(null)
                val message = "You no longer have access to this team workspace. Switched back to your personal workspace."
                _account.value = _account.value.copy(syncing = false, signedIn = true, email = userEmail, error = message)
                return CloudResult.Failure(message)
            }

            val isWritable = workspace.member_role in setOf("owner", "admin", "member")
            // Reconcile both workspace identity and access mode. Comparing only the
            // read-only flag can leave this gateway writing Team B records into Team A's
            // local Room database when both memberships have the same role.
            repository.switchWorkspace(
                workspaceId = workspaceId,
                readOnly = !isWritable,
                ownerUserId = userId
            )
            if (isWritable) {
                // Remove legacy NFC-only hive snapshots before they can overwrite team profiles.
                repository.discardLegacyNfcProfileSnapshots()
                repository.queuePendingInspectionNoteDrafts()
            }
            repository.queuePendingInspectionFormDrafts()
            val cursorKey = workspaceSyncCursorKey(userId, workspaceId)
            val previousCursor = readSyncCursor(cursorKey)
            if (isWritable) {
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

            repository.pruneSync()
            val now = System.currentTimeMillis()
            val warning = if (!isWritable && repository.pendingSync(1).isNotEmpty()) {
                "This workspace is view-only. Pending local edits were not uploaded."
            } else null
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

        try {
            supabase.handleDeeplinks(intent)
            scope.launch { refreshAccount() }
        } catch (t: Throwable) {
            _account.value = _account.value.copy(
                error = t.message ?: "Could not complete email verification"
            )
        }
    }


    // Stage 2 workspace administration uses SECURITY DEFINER database functions so the
    // mobile client never writes membership or invitation records directly.
    suspend fun listWorkspaces(): WorkspaceOperationResult<List<BeeKeepWorkspace>> =
        workspaceOperation {
            client!!.postgrest.rpc("beekeep_list_workspaces").decodeList<BeeKeepWorkspace>()
        }

    /**
     * Reads the signed-in account's server-managed Commercial entitlement. The database derives
     * this from trusted auth app metadata; local plan simulation is never an authorization source.
     */
    suspend fun myActiveCommercialEntitlement(): WorkspaceOperationResult<Boolean> =
        workspaceOperation {
            // The RPC name is historical; it now authorizes the Commercial tier.
            client!!.postgrest.rpc("beekeep_my_active_ultimate_entitlement")
                .decodeList<BeeKeepUltimateEntitlement>()
                .firstOrNull()?.active ?: error("Commercial entitlement was not returned")
        }

    @Deprecated("Use myActiveCommercialEntitlement")
    suspend fun myActiveUltimateEntitlement(): WorkspaceOperationResult<Boolean> =
        myActiveCommercialEntitlement()

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
                error = error.message ?: "The staging test entitlement could not be updated."
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
            supabase.auth.awaitInitialization()
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
                error = error.message ?: "Could not verify support-admin access."
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
            supabase.auth.awaitInitialization()
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
                error = error.message
                    ?: "The support-admin request could not be completed. Verify the deployed server function and admin access."
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

    suspend fun setUsername(username: String): WorkspaceOperationResult<String> =
        workspaceOperation {
            client!!.postgrest.rpc(
                "beekeep_set_username",
                WorkspaceUsernameParams(username.trim())
            ).decodeList<BeeKeepUsername>().firstOrNull()?.username
                ?: error("Username was not returned after saving")
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
            WorkspaceOperationResult(error = error.message?.takeIf { it.isNotBlank() }
                ?: "The workspace request failed. Please try again.")
        }
    }

    private suspend fun authCall(
        restoreWorkspaceId: String? = null,
        block: suspend () -> Unit
    ): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured
        return try {
            client?.auth?.awaitInitialization()
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
                "beekeep_profiles_username_lower_idx" in lowerMessage ||
                    ("username" in lowerMessage && "already taken" in lowerMessage) ->
                    "That username is already taken. Please choose another."
                rawMessage.isBlank() ||
                    "unknown error" in lowerMessage ||
                    "headers:" in lowerMessage ||
                    "url:" in lowerMessage ||
                    rawMessage.length > 240 ->
                    "BeeKeep couldn't complete authentication. Check your connection and account details, then try again."
                else -> rawMessage
            }
            _account.value = _account.value.copy(error = message)
            CloudResult.Failure(message)
        }
    }
}
