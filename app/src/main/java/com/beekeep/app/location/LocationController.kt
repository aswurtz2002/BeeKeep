package com.beekeep.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class LocationController(context: Context) {
    data class Result(val latitude: Double, val longitude: Double)

    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val appContext = context.applicationContext

    private companion object {
        const val MAX_CACHED_AGE_NANOS = 60_000_000_000L
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun current(onResult: (Result?) -> Unit) {
        if (!hasPermission()) {
            onResult(null)
            return
        }

        try {
            client.lastLocation
                .addOnSuccessListener { cached ->
                    val ageNanos = cached?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                    val cachedResult = cached?.toValidatedResult()

                    if (ageNanos != null &&
                        ageNanos in 0..MAX_CACHED_AGE_NANOS &&
                        cachedResult != null
                    ) {
                        onResult(cachedResult)
                        return@addOnSuccessListener
                    }

                    requestFreshLocation(onResult)
                }
                .addOnFailureListener { requestFreshLocation(onResult) }
        } catch (_: SecurityException) {
            // Permissions can be revoked while the app is open.
            onResult(null)
        } catch (_: RuntimeException) {
            onResult(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(onResult: (Result?) -> Unit) {
        try {
            val token = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { location -> onResult(location?.toValidatedResult()) }
                .addOnFailureListener { onResult(null) }
        } catch (_: SecurityException) {
            onResult(null)
        } catch (_: RuntimeException) {
            onResult(null)
        }
    }

    private fun isAccurateEnough(location: Location): Boolean =
        location.hasAccuracy() &&
            location.accuracy.isFinite() &&
            location.accuracy <= MAX_ACCEPTABLE_ACCURACY_METERS

    private fun Location.toValidatedResult(): Result? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!isAccurateEnough(this)) return null
        return Result(latitude, longitude)
    }
}
