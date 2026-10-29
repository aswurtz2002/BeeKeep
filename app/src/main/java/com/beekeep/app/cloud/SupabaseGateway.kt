package com.beekeep.app.cloud

import android.content.Context
import android.content.Intent
import com.beekeep.app.BuildConfig
import com.beekeep.app.BeeKeepPlan
import com.beekeep.app.BeeKeepEntitlements
import com.beekeep.app.data.LocalHiveRepository
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
import kotlinx.coroutines.flow.merge
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

class SupabaseGateway(
    private val context: Context,
    private val repository: LocalHiveRepository,
    private val enableRealtime: Boolean = true
) {
    private val appContext = context.applicationContext
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

    private var realtimeJob: Job? = null
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

    suspend fun refreshAccount() {
        val supabase = client
        if (!configured || supabase == null) {
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
            val userId = session?.user?.id
            if (userId == null) {
                BeeKeepEntitlements.activateCloudHiveLimitOverride(appContext, null)
                _hiveLimitOverride.value = null
                _verifiedPlan.value = null
                verifiedPlanUserId = null
            } else {
                refreshVerifiedPlanForUser(userId)
                refreshHiveLimitOverrideForUser(userId)
            }
            _account.value = CloudAccountState(
                configured = true,
                signedIn = session != null,
                email = session?.user?.email ?: ""
            )
            if (enableRealtime && session != null) startRealtime()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _account.value = CloudAccountState(configured = true, signedIn = false, error = error.message)
        }
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
            _verifiedPlan.value = null
        }
        val supabase = client ?: return
        try {
            val response = supabase.postgrest.rpc("beekeep_my_hive_entitlement")
                .decodeList<BeeKeepServerEntitlement>()
                .firstOrNull()
            _verifiedPlan.value = response?.let {
                BeeKeepEntitlements.planFromServerEntitlement(
                    planCode = it.planCode,
                    baseHiveLimit = it.baseHiveLimit,
                    isActivePaidPlan = it.isActivePaidPlan
                )
            } ?: BeeKeepPlan.FREE
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
                it in BeeKeepEntitlements.PRO_HIVE_LIMIT..BeeKeepEntitlements.MAX_CUSTOM_HIVE_LIMIT
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
        refreshAccount()
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
            refreshAccount()
            return@withLock CloudResult.NotSignedIn
        }
        val userId = session.user?.id ?: return@withLock CloudResult.NotSignedIn
        _account.value = _account.value.copy(syncing = true, error = null)
        try {
            val activeTeamWorkspaceId = appContext
                .getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
                .getString("active_team_workspace_id", "")
                .orEmpty().trim()
            if (activeTeamWorkspaceId.isNotEmpty()) {
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

    fun startRealtime() {
        val supabase = client
        if (!configured || supabase == null || realtimeJob?.isActive == true) return
        realtimeJob = scope.launch {
            var backoffMs = 1_000L
            while (true) {
                try {
                    if (supabase.auth.currentSessionOrNull() == null) return@launch
                    val channel = supabase.channel("beekeep-doc-sync")
                    try {
                        val privateChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "beekeep_documents"
                        }
                        val teamChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "beekeep_workspace_documents"
                        }
                        supabase.realtime.connect()
                        channel.subscribe()
                        _account.value = _account.value.copy(error = null)
                        backoffMs = 1_000L
                        merge(privateChanges, teamChanges).debounce(500).collect {
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

    fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        val supabase = client
        if (configured && supabase != null) {
            scope.launch { runCatching { supabase.realtime.removeAllChannels() } }
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

    suspend fun syncNow(): CloudResult = syncMutex.withLock { syncNowUnlocked() }

    private suspend fun syncNowUnlocked(): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured
        try {
            client.auth.awaitInitialization()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _account.value = _account.value.copy(syncing = false, error = error.message ?: "Could not restore your cloud session")
            return CloudResult.Failure(error.message ?: "Could not restore your cloud session")
        }
        val session = client.auth.currentSessionOrNull() ?: run {
            refreshAccount()
            return CloudResult.NotSignedIn
        }
        val userId = session.user?.id ?: run {
            _account.value = _account.value.copy(syncing = false, signedIn = false, error = "Your cloud session has no user ID. Sign in again.")
            return CloudResult.NotSignedIn
        }
        refreshVerifiedPlanForUser(userId)
        refreshHiveLimitOverrideForUser(userId)
        val activeTeamWorkspaceId = appContext
            .getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
            .getString("active_team_workspace_id", "")
            .orEmpty().trim()
        // WorkManager and the foreground app each own a repository instance; align this
        // instance with the latest scope preference before touching its local outbox.
        if (repository.activeWorkspaceId.value.orEmpty() != activeTeamWorkspaceId) {
            repository.switchWorkspace(activeTeamWorkspaceId.ifBlank { null })
        }
        _account.value = _account.value.copy(syncing = true, error = null)
        if (activeTeamWorkspaceId.isNotEmpty()) {
            return syncSharedWorkspace(activeTeamWorkspaceId, userId, session.user?.email.orEmpty())
        }
        return try {

            // Recover a local note edit if the screen was closed before its debounce queued the outbox row.
            repository.queuePendingInspectionNoteDrafts()
            // Pull first. This prevents an older offline outbox entry from overwriting a newer cloud record.
            val remoteBefore = loadAllPersonalDocuments()
            val remoteByKey = remoteBefore.associateBy { "${it.entity_type}:${it.entity_id}" }
            val pending = repository.pendingSync(500)
            val uploadable = mutableListOf<CloudDocumentRow>()
            val uploadableIds = mutableSetOf<Long>()

            for (item in pending) {
                val payloadTimestamp = runCatching { JSONObject(item.payload).optLong("updated_at", item.createdAt) }
                    .getOrDefault(item.createdAt)
                val remote = remoteByKey["${item.entityType}:${item.entityId}"]
                if (remote != null && remote.updated_at > payloadTimestamp) {
                    repository.markSyncDone(item.id)
                    continue
                }
                uploadable += CloudDocumentRow(
                    user_id = userId,
                    entity_type = item.entityType,
                    entity_id = item.entityId,
                    payload = item.payload,
                    updated_at = payloadTimestamp,
                    deleted = item.operation == "delete",
                    device_id = deviceId
                )
                uploadableIds += item.id
            }

            if (uploadable.isNotEmpty()) {
                client.from("beekeep_documents").upsert(uploadable) { onConflict = "user_id,entity_type,entity_id" }
                pending.filter { it.id in uploadableIds }.forEach { repository.markSyncDone(it.id) }
            }

            val remote = loadAllPersonalDocuments()
            remote.forEach { repository.applyCloudDocument(it) }

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
            _account.value = _account.value.copy(syncing = false, error = t.message ?: "Sync failed")
            CloudResult.Failure(t.message ?: "Sync failed")
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
        val previousWorkspaceId = prefs.getString("active_team_workspace_id", "")
            .orEmpty().trim().ifBlank { null }
        val previousReadOnly = prefs.getBoolean("active_team_workspace_read_only", false)
        var copyCompleted = false
        var copiedCount: Int? = null

        try {
            supabase.auth.awaitInitialization()
            val session = supabase.auth.currentSessionOrNull()
                ?: return@withLock WorkspaceOperationResult(error = "Sign in before copying personal records.")
            val available = supabase.postgrest.rpc("beekeep_list_workspaces")
                .decodeList<BeeKeepWorkspace>()
            val target = available.firstOrNull { it.id == cleanId && !it.is_personal }
                ?: return@withLock WorkspaceOperationResult(error = "You no longer have access to that team workspace.")
            if (target.member_role !in setOf("owner", "admin")) {
                return@withLock WorkspaceOperationResult(error = "Only a workspace owner or admin can copy personal records into a team.")
            }

            val previousRole = previousWorkspaceId?.let { oldId ->
                available.firstOrNull { it.id == oldId && !it.is_personal }?.member_role
            }
            suspend fun restorePreviousWorkspace() {
                repository.switchWorkspace(
                    previousWorkspaceId,
                    readOnly = previousRole == "viewer" || (previousRole == null && previousReadOnly)
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
            repository.switchWorkspace(cleanId, readOnly = false)
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
                        previousWorkspaceId,
                        readOnly = previousReadOnly
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
    suspend fun activateWorkspace(workspaceId: String?): WorkspaceOperationResult<Unit> = syncMutex.withLock {
        val supabase = client
        if (!configured || supabase == null) {
            return@withLock WorkspaceOperationResult(error = "BeeKeep Cloud is not configured.")
        }
        try {
            supabase.auth.awaitInitialization()
            if (supabase.auth.currentSessionOrNull() == null) {
                refreshAccount()
                return@withLock WorkspaceOperationResult(error = "Sign in before switching workspaces.")
            }
            val cleanId = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
            val workspace = if (cleanId == null) null else {
                supabase.postgrest.rpc("beekeep_list_workspaces")
                    .decodeList<BeeKeepWorkspace>()
                    .firstOrNull { it.id == cleanId && !it.is_personal }
                    ?: error("You no longer have access to that team workspace.")
            }
            repository.switchWorkspace(
                workspaceId = cleanId,
                readOnly = workspace?.member_role == "viewer"
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
            if (repository.activeWorkspaceId.value != workspaceId ||
                repository.activeWorkspaceReadOnly.value != (!isWritable)
            ) {
                repository.switchWorkspace(workspaceId, readOnly = !isWritable)
            }
            if (isWritable) repository.queuePendingInspectionNoteDrafts()
            val remoteBefore = loadAllWorkspaceDocuments(workspaceId)
            val remoteByKey = remoteBefore.associateBy { "${it.entity_type}:${it.entity_id}" }.toMutableMap()
            val pending = repository.pendingSync(500)
            val uploadable = mutableListOf<CloudWorkspaceDocumentRow>()
            val uploadableIds = mutableSetOf<Long>()

            if (isWritable) {
                for (item in pending) {
                    val payloadTimestamp = runCatching {
                        JSONObject(item.payload).optLong("updated_at", item.createdAt)
                    }.getOrDefault(item.createdAt)
                    val remote = remoteByKey["${item.entityType}:${item.entityId}"]
                    if (remote != null && remote.updated_at > payloadTimestamp) {
                        repository.markSyncDone(item.id)
                        continue
                    }
                    uploadable += CloudWorkspaceDocumentRow(
                        workspace_id = workspaceId,
                        entity_type = item.entityType,
                        entity_id = item.entityId,
                        payload = item.payload,
                        updated_at = payloadTimestamp,
                        deleted = item.operation == "delete",
                        device_id = deviceId,
                        updated_by = userId
                    )
                    uploadableIds += item.id
                }

                if (uploadable.isNotEmpty()) {
                    supabase.from("beekeep_workspace_documents").upsert(uploadable) {
                        onConflict = "workspace_id,entity_type,entity_id"
                    }
                    pending.filter { it.id in uploadableIds }.forEach { repository.markSyncDone(it.id) }
                    // Reuse the pulled snapshot and overlay successful writes instead of
                    // issuing a second full paginated download on every sync.
                    uploadable.forEach { row ->
                        remoteByKey["${row.entity_type}:${row.entity_id}"] = row
                    }
                }
            }

            val remote = remoteByKey.values.toList()
            // Child history is ignored locally if its hive has not been inserted yet.
            // Apply parents first rather than relying on the database's unspecified row order.
            val applyPriority = mapOf(
                "apiary" to 0,
                "hive" to 1,
                "nfc_assignment" to 2,
                "inspection" to 3,
                "feeding" to 3,
                "treatment" to 3,
                "harvest" to 3,
                "event" to 3
            )
            remote.sortedWith(
                compareBy<CloudWorkspaceDocumentRow> { applyPriority[it.entity_type] ?: 4 }
                    .thenBy { it.entity_id }
            ).forEach { document ->
                repository.applyCloudDocument(
                    CloudDocumentRow(
                        user_id = userId,
                        entity_type = document.entity_type,
                        entity_id = document.entity_id,
                        payload = document.payload,
                        updated_at = document.updated_at,
                        deleted = document.deleted,
                        device_id = document.device_id
                    )
                )
            }

            repository.pruneSync()
            val now = System.currentTimeMillis()
            val warning = if (!isWritable && pending.isNotEmpty()) {
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
     * Reads the signed-in account's server-managed Ultimate entitlement. The database derives
     * this from trusted auth app metadata; local plan simulation is never an authorization source.
     */
    suspend fun myActiveUltimateEntitlement(): WorkspaceOperationResult<Boolean> =
        workspaceOperation {
            client!!.postgrest.rpc("beekeep_my_active_ultimate_entitlement")
                .decodeList<BeeKeepUltimateEntitlement>()
                .firstOrNull()?.active ?: error("Ultimate entitlement was not returned")
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
            BeeKeepPlan.FREE -> "free"
            BeeKeepPlan.PRO -> "pro_monthly"
            BeeKeepPlan.PRO_YEARLY -> "pro_yearly"
            BeeKeepPlan.ULTIMATE -> "ultimate_monthly"
            BeeKeepPlan.ULTIMATE_YEARLY -> "ultimate_yearly"
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
                refreshAccount()
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
