package com.blizzardcaron.freeolleefaces.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Coords(val lat: Double, val lng: Double, val accuracyM: Float?, val provider: String?)

class LocationSource(private val context: Context) {

    suspend fun fetch(timeoutMs: Long = 10_000): Result<Coords> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasAnyLocationPermission()) {
                throw SecurityException("ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION required")
            }
            val lm = context.getSystemService(LocationManager::class.java)
                ?: error("LocationManager unavailable")
            val providers = preferredProviders(lm)
            if (providers.isEmpty()) error("no location providers enabled")

            val fresh = withTimeoutOrNull(timeoutMs) { tryGetCurrent(lm, providers) }
            if (fresh != null) return@runCatching fresh.toCoords()

            val lastKnown = providers
                .mapNotNull { runCatching { getLastKnown(lm, it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?: error("no fix available (current request timed out and no last-known fix)")
            lastKnown.toCoords()
        }
    }

    private fun preferredProviders(lm: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { LocationManagerCompat.isLocationEnabled(lm) && it in lm.allProviders && lm.isProviderEnabled(it) }

    @SuppressLint("MissingPermission")
    private suspend fun tryGetCurrent(lm: LocationManager, providers: List<String>): Location? {
        for (provider in providers) {
            val loc = suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, DIRECT_EXECUTOR) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
            if (loc != null) return loc
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnown(lm: LocationManager, provider: String): Location? =
        lm.getLastKnownLocation(provider)

    private fun hasAnyLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun Location.toCoords() =
        Coords(latitude, longitude, if (hasAccuracy()) accuracy else null, provider)

    companion object {
        private val DIRECT_EXECUTOR: java.util.concurrent.Executor =
            java.util.concurrent.Executor { it.run() }
    }
}
