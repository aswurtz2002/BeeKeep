package com.beekeep.app.cloud

import com.beekeep.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Small, thread-safe process singleton used so client creation remains testable without starting
 * Android or Supabase. The first successful factory result is retained for the lifetime of the
 * holder; concurrent callers cannot create independent Auth refresh loops.
 */
internal class LazySingleton<T : Any> {
    @Volatile
    private var instance: T? = null

    fun getOrCreate(factory: () -> T): T {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: factory().also { instance = it }
        }
    }
}

/**
 * Owns the single Supabase client used in this app process.
 *
 * The foreground Activity and WorkManager worker both sync in the same process and share
 * Supabase's persisted Auth session. Constructing one auto-refreshing client per gateway lets
 * independent refresh loops race over the same rotating refresh token; Supabase can reject the
 * second use with refresh_token_already_used and revoke the session. Sharing the client also
 * gives every gateway the same refresh lifecycle.
 */
internal object BeeKeepSupabaseRuntime {
    private val clientHolder = LazySingleton<SupabaseClient>()

    /** Serialize syncs requested by the foreground app and background worker. */
    val syncMutex = Mutex()
    private val authInitializationMutex = Mutex()
    @Volatile
    private var authStorageInitialized = false

    /**
     * Restore persisted credentials locally exactly once for the shared process-wide Supabase
     * client. Never join the SDK's initial refresh job; BeeKeep schedules bounded refreshes later.
     */
    @OptIn(SupabaseInternal::class)
    suspend fun ensureAuthStorageInitialized(client: SupabaseClient) {
        authInitializationMutex.withLock {
            if (authStorageInitialized) return
            val found = withTimeout(5_000L) {
                client.auth.loadFromStorage(autoRefresh = false)
            }
            if (!found && client.auth.sessionStatus.value is SessionStatus.Initializing) {
                client.auth.setSessionStatus(SessionStatus.NotAuthenticated())
            }
            authStorageInitialized = true
        }
    }

    fun client(): SupabaseClient? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            return null
        }

        return clientHolder.getOrCreate {
            createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            ) {
                // Every Supabase HTTP request (including Auth refresh and RPC) is bounded.
                requestTimeout = 12.seconds
                install(Auth) {
                    scheme = BuildConfig.AUTH_REDIRECT_SCHEME
                    host = BuildConfig.AUTH_REDIRECT_HOST
                    // Restore the persisted session locally in SupabaseGateway. The SDK's
                    // auto-load joins its refresh job, whose network retry loop can be unbounded.
                    autoLoadFromStorage = false
                    autoSaveToStorage = true
                    // BeeKeep schedules bounded refresh attempts in its gateway-owned scope.
                    alwaysAutoRefresh = false
                }
                install(Postgrest)
                install(Realtime)
                install(Storage)
                install(Functions)
            }
        }
    }
}
