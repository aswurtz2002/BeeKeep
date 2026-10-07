package com.beekeep.app.cloud

import android.content.Context
import com.beekeep.app.BuildConfig
import com.beekeep.app.data.LocalHiveRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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
import java.util.UUID
import org.json.JSONObject

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

    private val client = if (configured) createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {
        install(Auth) { autoLoadFromStorage = true; alwaysAutoRefresh = true }
        install(Postgrest)
        install(Realtime)
        install(Storage)
    } else null

    private var realtimeJob: Job? = null
    private val syncMutex = Mutex()

    private val _account = MutableStateFlow(
        CloudAccountState(configured = configured, signedIn = false)
    )
    val account: StateFlow<CloudAccountState> = _account.asStateFlow()

    init { scope.launch { refreshAccount() } }

    suspend fun refreshAccount() {
        val supabase = client
        if (!configured || supabase == null) {
            _account.value = CloudAccountState(configured = false, signedIn = false)
            return
        }
        runCatching {
            val session = supabase.auth.currentSessionOrNull()
            _account.value = CloudAccountState(
                configured = true,
                signedIn = session != null,
                email = session?.user?.email ?: ""
            )
            if (enableRealtime && session != null) startRealtime()
        }.onFailure {
            _account.value = CloudAccountState(configured = true, signedIn = false, error = it.message)
        }
    }

    suspend fun signIn(email: String, password: String): CloudResult = authCall {
        val supabase = client ?: return@authCall
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String): CloudResult = authCall {
        val supabase = client ?: return@authCall
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut(): CloudResult = try {
        val supabase = client ?: return CloudResult.NotConfigured
        stopRealtime()
        supabase.auth.signOut()
        refreshAccount()
        CloudResult.Success
    } catch (t: Throwable) {
        CloudResult.Failure(t.message ?: "Sign out failed")
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
                        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "beekeep_documents"
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

    fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        val supabase = client
        if (configured && supabase != null) {
            scope.launch { runCatching { supabase.realtime.removeAllChannels() } }
        }
    }

    suspend fun uploadPendingPhotos(): CloudResult {
        val supabase = client ?: return CloudResult.NotConfigured
        val session = supabase.auth.currentSessionOrNull() ?: return CloudResult.NotSignedIn
        return try {
            val userId = session.user?.id ?: return CloudResult.NotSignedIn
            val pending = repository.pendingPhotos(10)
            val bucket = supabase.storage["inspection-photos"]
            for (photo in pending) {
                val file = java.io.File(photo.localPath)
                if (!file.exists()) {
                    repository.markPhotoError(photo.id, "Local photo file is missing")
                    continue
                }
                val cloudPath = "$userId/hive-${photo.hiveId}/inspection-${photo.inspectionId}-${photo.id}.jpg"
                bucket.upload(cloudPath, file.readBytes()) { upsert = true }
                repository.markPhotoUploaded(photo.id, cloudPath)
            }
            CloudResult.Success
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            CloudResult.Failure(t.message ?: "Photo upload failed")
        }
    }

    suspend fun syncNow(): CloudResult = syncMutex.withLock {
        if (!configured || client == null) return@withLock CloudResult.NotConfigured
        val session = client.auth.currentSessionOrNull() ?: run {
            refreshAccount()
            return@withLock CloudResult.NotSignedIn
        }
        _account.value = _account.value.copy(syncing = true, error = null)
        try {
            val userId = session.user?.id ?: return@withLock CloudResult.NotSignedIn

            // Pull first. This prevents an older offline outbox entry from overwriting a newer cloud record.
            val remoteBefore = client.from("beekeep_documents").select().decodeList<CloudDocumentRow>()
            val remoteByKey = remoteBefore.associateBy { "${it.entity_type}:${it.entity_id}" }
            val pending = repository.pendingSync(100)
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

            val remote = client.from("beekeep_documents").select().decodeList<CloudDocumentRow>()
            remote.forEach { repository.applyCloudDocument(it) }

            // Photos are synchronized after their inspection metadata. Local inspection saves never depend on this.
            when (val photoResult = uploadPendingPhotos()) {
                CloudResult.Success -> Unit
                CloudResult.NotConfigured, CloudResult.NotSignedIn -> Unit
                is CloudResult.Failure -> throw IllegalStateException(photoResult.message)
            }

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

    private suspend fun authCall(block: suspend () -> Unit): CloudResult {
        if (!configured || client == null) return CloudResult.NotConfigured
        return try {
            block()
            refreshAccount()
            if (enableRealtime && _account.value.signedIn) startRealtime()
            CloudResult.Success
        } catch (t: Throwable) {
            val message = t.message ?: "Authentication failed"
            _account.value = _account.value.copy(error = message)
            CloudResult.Failure(message)
        }
    }
}
