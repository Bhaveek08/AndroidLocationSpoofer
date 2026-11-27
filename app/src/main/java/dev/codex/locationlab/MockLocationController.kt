package dev.codex.locationlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Looper
import android.os.SystemClock

/**
 * Writes test-provider fixes through Android's mock-location API and reads them back to confirm
 * Android really delivered them.
 *
 * Providers: `gps` and `network` on every supported release, plus the platform `fused` provider on
 * API 31+. Apps that read location through Google Play Services' fused client see the `fused`
 * provider, so mocking only gps/network leaves those apps on their own estimate.
 */
class MockLocationController(
    private val context: Context,
    private val manager: LocationManager,
    private val onVerified: (GeoPoint, Set<String>) -> Unit = { _, _ -> }
) {
    private val requestedProviders: List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }

    /** Providers Android actually accepted; a device may refuse one without failing the session. */
    private val activeProviders = mutableListOf<String>()
    private val listeners = mutableMapOf<String, LocationListener>()
    private val lock = Any()
    private var targetPoint: GeoPoint? = null
    private var targetTimeMillis = 0L
    private val verifiedProviders = mutableSetOf<String>()
    private var reportedPoint: GeoPoint? = null
    private var reportedProviders = emptySet<String>()
    private var registered = false

    val providerNames: List<String> get() = synchronized(lock) { activeProviders.toList() }

    fun inject(
        point: GeoPoint,
        accuracyMeters: Float = 5f,
        bearingDegrees: Float = 0f,
        speedMetersPerSecond: Float = 0f,
        altitudeMeters: Double = DEFAULT_ALTITUDE_METERS
    ) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Allow precise location so Android's mock fix can be verified.")
        }
        if (!registered) registerProviders()
        val injectionTime = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            targetPoint = point
            targetTimeMillis = injectionTime
            verifiedProviders.clear()
        }
        val providers = synchronized(lock) { activeProviders.toList() }
        if (providers.isEmpty()) throw IllegalStateException("Android did not accept any mock location provider.")
        providers.forEach { provider ->
            manager.setTestProviderLocation(provider, buildFix(provider, point, accuracyMeters, bearingDegrees, speedMetersPerSecond, altitudeMeters, injectionTime, elapsed))
        }
    }

    /**
     * A fix with every field a consumer may look at. Play Services discards or down-weights fixes
     * that are missing accuracy fields, which is a common reason a spoof appears to do nothing.
     */
    private fun buildFix(
        provider: String,
        point: GeoPoint,
        accuracyMeters: Float,
        bearingDegrees: Float,
        speedMetersPerSecond: Float,
        altitudeMeters: Double,
        injectionTime: Long,
        elapsedRealtime: Long
    ) = Location(provider).apply {
        latitude = point.latitude
        longitude = point.longitude
        accuracy = accuracyMeters
        altitude = altitudeMeters
        speed = speedMetersPerSecond
        bearing = bearingDegrees
        time = injectionTime
        elapsedRealtimeNanos = elapsedRealtime
        verticalAccuracyMeters = accuracyMeters * 1.5f
        speedAccuracyMetersPerSecond = (speedMetersPerSecond * 0.1f).coerceAtLeast(0.3f)
        bearingAccuracyDegrees = if (speedMetersPerSecond > 0.5f) 5f else 45f
        if (Build.VERSION.SDK_INT >= 29) elapsedRealtimeUncertaintyNanos = 1_000_000.0
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
        synchronized(lock) { activeProviders.toList() }.forEach { provider ->
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
        synchronized(lock) {
            activeProviders.clear()
            targetPoint = null
            verifiedProviders.clear()
            reportedPoint = null
            reportedProviders = emptySet()
        }
        registered = false
    }

    private fun registerProviders() {
        // Mark ownership before creating providers so a partial setup can be cleaned up on failure.
        registered = true
        val accepted = mutableListOf<String>()
        var firstFailure: Exception? = null
        requestedProviders.forEach { provider ->
            try {
                addProviderRecovering(provider)
                manager.setTestProviderEnabled(provider, true)
                accepted += provider
            } catch (failure: Exception) {
                // A device that refuses one provider (commonly `fused`) can still be mocked on the rest.
                if (firstFailure == null) firstFailure = failure
            }
        }
        synchronized(lock) { activeProviders.addAll(accepted) }
        if (accepted.isEmpty()) {
            throw firstFailure ?: IllegalStateException(
                "Android refused every mock provider. Select GeoShift under Developer options → Select mock location app."
            )
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Precise location permission was revoked during mock-provider setup.")
        }
        accepted.forEach { provider ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    verifyProviderUpdate(provider, location)
                }

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            listeners[provider] = listener
            runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        }
    }

    private fun addProviderRecovering(provider: String) {
        try {
            addProvider(provider)
        } catch (alreadyAdded: IllegalArgumentException) {
            // Recover cleanly if a previous run left a provider registered.
            runCatching { manager.removeTestProvider(provider) }
            addProvider(provider)
        }
    }

    private fun verifyProviderUpdate(provider: String, location: Location) {
        val expected = synchronized(lock) {
            val point = targetPoint ?: return
            if (location.time < targetTimeMillis) return
            point
        }
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        if (!isMock ||
            kotlin.math.abs(location.latitude - expected.latitude) > COORDINATE_TOLERANCE ||
            kotlin.math.abs(location.longitude - expected.longitude) > COORDINATE_TOLERANCE
        ) return

        // Report the first confirmation and every time another provider joins, but not on every
        // 1 Hz refresh: the listener fires continuously and each report writes state to disk.
        val report = synchronized(lock) {
            if (targetPoint != expected) return
            verifiedProviders += provider
            val changed = reportedProviders != verifiedProviders.toSet() || reportedPoint == null
            if (!changed) return
            reportedPoint = expected
            reportedProviders = verifiedProviders.toSet()
            expected to reportedProviders
        }
        onVerified(report.first, report.second)
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

    companion object {
        private const val COORDINATE_TOLERANCE = 0.000001
        private const val DEFAULT_ALTITUDE_METERS = 15.0
    }
}
