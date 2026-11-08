package com.beekeep.app.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface TrustedDeviceApiResult<out T> {
    data class Success<T>(val value: T) : TrustedDeviceApiResult<T>
    data class Failure(val message: String) : TrustedDeviceApiResult<Nothing>
}

@Serializable
internal data class TrustedDeviceRequestRow(
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("request_status") val requestStatus: String? = null,
    @SerialName("request_mode") val requestMode: String? = null,
    @SerialName("device_label") val deviceLabel: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("challenge_verified_at") val challengeVerifiedAt: String? = null,
    @SerialName("approval_method") val approvalMethod: String? = null
)

@Serializable
internal data class TrustedDeviceFunctionResponse(
    val success: Boolean = false,
    val error: String? = null,
    val request: List<TrustedDeviceRequestRow>? = null,
    val requests: List<TrustedDeviceRequestRow>? = null,
    val status: String? = null,
    val trusted: Boolean? = null
)

/**
 * Small authenticated API for the one-session handover.
 * The Supabase JWT session_id identifies the phone session; no client-created
 * device key or client-side cryptographic approval protocol is involved.
 */
internal class TrustedDeviceAccessApi(
    private val supabase: SupabaseClient
) {
    suspend fun currentSessionTrusted(): TrustedDeviceApiResult<Boolean> = call {
        invoke("access").trusted == true
    }

    suspend fun begin(deviceLabel: String): TrustedDeviceApiResult<TrustedDeviceRequestRow> = call {
        val response = invoke(
            action = "begin",
            args = buildJsonObject { put("device_label", deviceLabel.take(80)) }
        )
        response.request?.singleOrNull()
            ?: error("BeeKeep didn't return a phone approval request. Try again.")
    }

    suspend fun requestStatus(): TrustedDeviceApiResult<TrustedDeviceRequestRow?> = call {
        invoke("status").request?.singleOrNull()
    }

    suspend fun pendingRequests(): TrustedDeviceApiResult<List<TrustedDeviceRequestRow>> = call {
        invoke("list").requests.orEmpty()
    }

    suspend fun approve(requestId: String): TrustedDeviceApiResult<String> = call {
        invoke(
            action = "approve",
            args = buildJsonObject { put("request_id", requestId) }
        ).status.orEmpty()
    }

    suspend fun deny(requestId: String): TrustedDeviceApiResult<String> = call {
        invoke(
            action = "deny",
            args = buildJsonObject { put("request_id", requestId) }
        ).status.orEmpty()
    }

    suspend fun finalize(requestId: String): TrustedDeviceApiResult<String> = call {
        invoke(
            action = "finalize",
            args = buildJsonObject { put("request_id", requestId) }
        ).status.orEmpty()
    }

    suspend fun recover(requestId: String): TrustedDeviceApiResult<String> = call {
        invoke(
            action = "recover",
            args = buildJsonObject { put("request_id", requestId) }
        ).status.orEmpty()
    }

    private suspend fun invoke(
        action: String,
        args: JsonObject = buildJsonObject {}
    ): TrustedDeviceFunctionResponse {
        val body = buildJsonObject {
            put("action", action)
            args.forEach { (key, value) -> put(key, value) }
        }
        val response = supabase.functions.invoke(
            function = FUNCTION_NAME,
            body = body
        ).body<TrustedDeviceFunctionResponse>()
        if (!response.success) {
            throw IllegalStateException(
                response.error?.takeIf { it.isNotBlank() }
                    ?: "BeeKeep couldn't complete the phone handover. Try again."
            )
        }
        return response
    }

    private suspend fun <T> call(operation: suspend () -> T): TrustedDeviceApiResult<T> =
        try {
            TrustedDeviceApiResult.Success(operation())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.trim()?.takeIf { it.isNotBlank() }
                ?.take(MAX_ERROR_LENGTH)
                ?: "BeeKeep couldn't reach the phone approval service. Check your connection and try again."
            TrustedDeviceApiResult.Failure(message)
        }

    private companion object {
        const val FUNCTION_NAME = "beekeep-device-access"
        const val MAX_ERROR_LENGTH = 220
    }
}
