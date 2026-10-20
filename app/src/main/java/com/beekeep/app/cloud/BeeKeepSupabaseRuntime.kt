package com.beekeep.app.cloud

import com.beekeep.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.sync.Mutex

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
    private var sharedClient: SupabaseClient? = null

    /** Serialize syncs requested by the foreground app and background worker. */
    val syncMutex = Mutex()

    @Synchronized
    fun client(): SupabaseClient? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            return null
        }

        sharedClient?.let { return it }

        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Auth) {
                scheme = BuildConfig.AUTH_REDIRECT_SCHEME
                host = BuildConfig.AUTH_REDIRECT_HOST
                autoLoadFromStorage = true
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }.also { sharedClient = it }
    }
}
