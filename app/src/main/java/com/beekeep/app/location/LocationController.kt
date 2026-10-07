package com.beekeep.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.os.SystemClock

class LocationController(context: Context) {
    data class Result(val latitude: Double, val longitude: Double)
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val appContext = context.applicationContext

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun current(onResult: (Result?) -> Unit) {
        if (!hasPermission()) { onResult(null); return }
        client.lastLocation
            .addOnSuccessListener { cached ->
                val freshEnough = cached != null &&
                    SystemClock.elapsedRealtimeNanos() - cached.elapsedRealtimeNanos < 120_000_000_000L &&
                    cached.accuracy <= 150f
                if (freshEnough && cached != null) {
                    onResult(Result(cached.latitude, cached.longitude))
                    return@addOnSuccessListener
                }
                requestFreshLocation(onResult)
            }
            .addOnFailureListener { requestFreshLocation(onResult) }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(onResult: (Result?) -> Unit) {
        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location: Location? -> onResult(location?.let { Result(it.latitude, it.longitude) }) }
            .addOnFailureListener { onResult(null) }
    }
}
