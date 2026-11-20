package dev.codex.locationlab

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.location.provider.ProviderProperties
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.SystemClock

/** Writes real test-provider fixes through Android's mock-location API. */
class MockLocationController(private val context: Context, private val manager: LocationManager) {
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private var registered = false

    fun inject(point: GeoPoint, accuracyMeters: Float = 5f) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Allow precise location so Android's mock fix can be verified.")
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
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            manager.setTestProviderLocation(provider, fix)
            val returned = manager.getLastKnownLocation(provider)
                ?: throw IllegalStateException("Android did not return a fix from the $provider provider.")
            @Suppress("DEPRECATION")
            val isMock = if (Build.VERSION.SDK_INT >= 31) returned.isMock else returned.isFromMockProvider
            if (!isMock ||
                kotlin.math.abs(returned.latitude - point.latitude) > 0.000001 ||
                kotlin.math.abs(returned.longitude - point.longitude) > 0.000001
            ) {
                throw IllegalStateException("Android returned a different or non-mock fix from $provider.")
            }
        }
    }

    fun stop() {
        if (!registered) return
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
