package com.beekeep.app.cloud

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException

/**
 * Maps the phone-verification transport into a small set of safe user-facing outcomes.
 * Raw server payloads, HTTP codes, request headers, and exception text never reach the UI.
 */
internal object TrustedDeviceErrorMessages {
    private const val UNAVAILABLE =
        "Something went wrong. Please try again."

    fun failureKind(error: Throwable): TrustedDeviceApiFailureKind {
        val chain = generateSequence(error) { it.cause }.toList()
        val messages = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val rejected = chain.any { cause ->
            cause is RestException && cause.statusCode in 401..403
        } || listOf(
            "unauthorized", "forbidden", "invalid jwt", "session is no longer valid",
            "device mismatch", "not the active device", "session has been revoked"
        ).any(messages::contains)
        if (rejected) return TrustedDeviceApiFailureKind.AUTH_REJECTED

        val offline = chain.any { cause ->
            cause is IOException ||
                cause is HttpRequestException ||
                cause is HttpRequestTimeoutException
        } || listOf(
            "unknownhostexception", "unable to resolve host", "network is unreachable",
            "connection timed out", "sockettimeoutexception", "connection reset",
            "failed to connect", "no address associated with hostname"
        ).any(messages::contains)
        return if (offline) TrustedDeviceApiFailureKind.NETWORK
        else TrustedDeviceApiFailureKind.OTHER
    }

    fun from(error: Throwable): String {
        val raw = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
        val lower = raw.lowercase()
        val authCode = generateSequence(error) { it.cause }
            .filterIsInstance<AuthRestException>()
            .firstOrNull()
            ?.errorCode
            ?.name
            ?.lowercase()
            .orEmpty()

        return when {
            authCode in setOf(
                "overrequestratelimit",
                "overemailsendratelimit",
                "oversmssendratelimit"
            ) || lower.contains("too many") || lower.contains("rate limit") ||
                lower.contains("rate_limit") || lower.contains("429") ||
                lower.contains("53300") ->
                "Too many tries. Please wait a minute."

            authCode in setOf("otpexpired", "flowstateexpired", "tokenexpired") ||
                lower.contains("expired") || lower.contains("otp_expired") ||
                lower.contains("token has expired") ->
                "That code has expired. We can send a new one."

            authCode == "invalidcredentials" ||
                lower.contains("invalid otp") || lower.contains("invalid code") ||
                lower.contains("token is invalid") || lower.contains("otp is invalid") ->
                "That code isn't right. Try again."

            failureKind(error) == TrustedDeviceApiFailureKind.NETWORK ->
                "No connection. Check your internet and try again."

            failureKind(error) == TrustedDeviceApiFailureKind.AUTH_REJECTED ->
                "Your sign-in session is no longer active. Please sign in again."

            else -> UNAVAILABLE
        }
    }
}
