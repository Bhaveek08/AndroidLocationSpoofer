package dev.codex.locationlab

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.location.provider.ProviderProperties
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.SystemClock

/** Writes real test-provider fixes through Android's mock-location API. */
class MockLocationController(
    private val context: Context,
    private val manager: LocationManager,
    private val onVerified: (GeoPoint, Set<String>) -> Unit = { _, _ -> }
) {
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val listeners = mutableMapOf<String, LocationListener>()
    private val lock = Any()
    private var targetPoint: GeoPoint? = null
    private var targetTimeMillis = 0L
    private val verifiedProviders = mutableSetOf<String>()
    private var completedTimeMillis = 0L
    private var registered = false

    fun inject(point: GeoPoint, accuracyMeters: Float = 5f) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Allow precise location so Android's mock fix can be verified.")
        }
        val injectionTime = System.currentTimeMillis()
        synchronized(lock) {
            targetPoint = point
            targetTimeMillis = injectionTime
            verifiedProviders.clear()
        }
        if (!registered) registerProviders()
        providers.forEach { provider ->
            val fix = Location(provider).apply {
                latitude = point.latitude
                longitude = point.longitude
                accuracy = accuracyMeters
                altitude = 0.0
                speed = 0f
                bearing = 0f
                time = injectionTime
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            manager.setTestProviderLocation(provider, fix)
        }
    }

    fun stop() {
        if (!registered) return
        listeners.values.forEach { listener ->
            try {
                manager.removeUpdates(listener)
            } catch (_: Exception) {
                // Android may already have removed the listener with its provider.
            }
        }
        listeners.clear()
        providers.forEach { provider ->
            try {
                manager.setTestProviderEnabled(provider, false)
            } catch (_: Exception) {
                // The provider may already have been removed by Android.
            }
            try {
                manager.removeTestProvider(provider)
            } catch (_: Exception) {
                // Stopping is idempotent, including after an OS cleanup.
            }
        }
        registered = false
    }

    private fun registerProviders() {
        // Mark ownership before creating providers so a partial setup can be cleaned up on failure.
        registered = true
        providers.forEach { provider ->
            try {
                addProvider(provider)
            } catch (alreadyAdded: IllegalArgumentException) {
                // Recover cleanly if a previous run left a provider registered.
                try {
                    manager.removeTestProvider(provider)
                } catch (_: IllegalArgumentException) {
                    // There was nothing to remove.
                }
                addProvider(provider)
            }
            manager.setTestProviderEnabled(provider, true)
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Precise location permission was revoked during mock-provider setup.")
        }
        providers.forEach { provider ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    verifyProviderUpdate(provider, location)
                }
            }
            listeners[provider] = listener
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun verifyProviderUpdate(provider: String, location: Location) {
        val snapshot = synchronized(lock) {
            val point = targetPoint ?: return
            if (location.time < targetTimeMillis) return
            point to targetTimeMillis
        }
        val expected = snapshot.first
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        if (!isMock ||
            kotlin.math.abs(location.latitude - expected.latitude) > 0.000001 ||
            kotlin.math.abs(location.longitude - expected.longitude) > 0.000001
        ) return

        val completion = synchronized(lock) {
            if (targetPoint != expected || targetTimeMillis != snapshot.second) return
            verifiedProviders += provider
            if (verifiedProviders.containsAll(providers) && completedTimeMillis != targetTimeMillis) {
                completedTimeMillis = targetTimeMillis
                expected to verifiedProviders.toSet()
            } else {
                null
            }
        }
        completion?.let { (point, confirmedProviders) -> onVerified(point, confirmedProviders) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun addProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= 31) {
            val properties = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(false)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            manager.addTestProvider(provider, properties)
        } else {
            @Suppress("DEPRECATION")
            manager.addTestProvider(
                provider,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
        }
    }
}
