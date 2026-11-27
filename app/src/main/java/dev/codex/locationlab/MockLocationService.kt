package dev.codex.locationlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the mock fix fresh while the app is in the background.
 *
 * Two modes: a fixed point, or a simulated drive along a route polyline. The drive advances by
 * simulated time rather than wall clock, so a pause for a real-location sample resumes where it
 * left off instead of jumping ahead.
 */
class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MockLocationController? = null
    private var point: GeoPoint? = null
    private var accuracyMeters: Float = 5f
    private var simulator: RouteSimulator? = null
    private var driveSpeedKph = 0.0
    private var simulatedSeconds = 0.0
    private var lastFix: SimulatedFix? = null
    private var paused = false
    private var preserveError = false
    private var ticks = 0

    private val firstFixTimeout = Runnable {
        if (readState() != STATE_ACTIVE) {
            preserveError = true
            state(
                STATE_ERROR,
                "Android accepted the provider setup but did not deliver a verified mock fix within 30 seconds.",
                0L
            )
            stopSession()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (paused) return
            val target = point ?: return
            try {
                val route = simulator
                if (route == null) {
                    controller?.inject(target, accuracyMeters)
                } else {
                    val fix = route.fixAt(simulatedSeconds)
                    lastFix = fix
                    point = fix.point
                    controller?.inject(
                        fix.point,
                        accuracyMeters,
                        fix.bearingDegrees,
                        fix.speedMetersPerSecond
                    )
                    if (!fix.arrived) simulatedSeconds += UPDATE_INTERVAL_MS / 1_000.0
                }
                if (simulator != null) publishProgress()
                if (ticks++ % NOTIFICATION_REFRESH_TICKS == 0) refreshNotification()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            } catch (failure: Exception) {
                preserveError = true
                state(STATE_ERROR, failure.message ?: failure.javaClass.simpleName, 0L)
                stopSession()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (point == null) stopSelf() else pauseInjection()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (point == null) stopSelf() else resumeInjection()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }
        preserveError = false

        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            preserveError = true
            state(STATE_ERROR, "Invalid coordinates.", 0L)
            stopSelf()
            return START_NOT_STICKY
        }

        controller?.stop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(firstFixTimeout)
        paused = false
        ticks = 0
        simulatedSeconds = 0.0
        lastFix = null
        point = GeoPoint(lat, lon)
        accuracyMeters = intent.getFloatExtra(EXTRA_ACCURACY, 5f).coerceIn(1f, 1000f)
        simulator = buildSimulator(intent)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        controller = MockLocationController(this, getSystemService(Context.LOCATION_SERVICE) as LocationManager) { verifiedPoint, verifiedProviders ->
            handler.removeCallbacks(firstFixTimeout)
            state(
                STATE_ACTIVE,
                "Android delivered a mock fix for ${format(verifiedPoint)} through ${verifiedProviders.sorted().joinToString()}.",
                System.currentTimeMillis()
            )
            refreshNotification()
        }
        state(STATE_STARTING, "Starting Android mock providers…", 0L)
        handler.post(ticker)
        handler.postDelayed(firstFixTimeout, FIRST_FIX_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun buildSimulator(intent: Intent): RouteSimulator? {
        val flat = intent.getDoubleArrayExtra(EXTRA_ROUTE) ?: return null
        if (flat.size < 4 || flat.size % 2 != 0) return null
        val points = (flat.indices step 2).mapNotNull { index ->
            runCatching { GeoPoint.parse(flat[index].toString(), flat[index + 1].toString()) }.getOrNull()
        }
        driveSpeedKph = intent.getDoubleExtra(EXTRA_SPEED_KPH, 0.0)
        return runCatching { RouteSimulator(points, driveSpeedKph / 3.6) }.getOrNull()
    }

    private fun pauseInjection() {
        if (point == null || paused) return
        paused = true
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(firstFixTimeout)
        controller?.stop()
        state(STATE_PAUSED, "Mock providers are removed so Android can report a real fix. Other apps can see your real location until you resume.", 0L)
        refreshNotification()
    }

    private fun resumeInjection() {
        if (point == null || !paused) return
        paused = false
        preserveError = false
        state(STATE_STARTING, "Restoring Android mock providers…", 0L)
        handler.post(ticker)
        handler.postDelayed(firstFixTimeout, FIRST_FIX_TIMEOUT_MS)
        refreshNotification()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(firstFixTimeout)
        try {
            controller?.stop()
        } finally {
            controller = null
            simulator = null
            lastFix = null
            driveSpeedKph = 0.0
            if (!preserveError) state(STATE_STOPPED, "Mock location stopped.", 0L)
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSession() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(firstFixTimeout)
        controller?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while GeoShift is replacing your location."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val target = point
        val fix = lastFix
        val body = when {
            paused -> "Paused · your real location is visible to apps"
            fix != null && fix.arrived -> "Arrived · holding ${format(target)}"
            fix != null -> "Driving · ${formatRouteDistance(fix.metersRemaining)} to go"
            target != null -> "Reporting ${format(target)}"
            else -> "Mock location is active"
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (paused) "GeoShift is paused" else "GeoShift is active")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun format(target: GeoPoint?): String = target?.let {
        String.format(java.util.Locale.US, "%.5f, %.5f", it.latitude, it.longitude)
    } ?: "the selected point"

    /**
     * Where the drive currently is, so the map can follow it. Only written while a route is being
     * simulated; a parked spoof writes nothing after its first verification.
     */
    private fun publishProgress() {
        val fix = lastFix ?: return
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_REMAINING_METERS, fix.metersRemaining.toFloat())
            .putBoolean(KEY_ARRIVED, fix.arrived)
            .putFloat(KEY_BEARING, fix.bearingDegrees)
            .putString(KEY_CURRENT_LAT, fix.point.latitude.toString())
            .putString(KEY_CURRENT_LON, fix.point.longitude.toString())
            .apply()
    }

    private fun readState(): String =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, STATE_STOPPED) ?: STATE_STOPPED

    private fun state(value: String, detail: String, updatedAt: Long) {
        val fix = lastFix
        val target = point
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, value)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_UPDATED, updatedAt)
            .putBoolean(KEY_SIMULATING, simulator != null)
            .putFloat(KEY_TOTAL_METERS, simulator?.totalMeters?.toFloat() ?: -1f)
            .putFloat(KEY_SPEED_KPH, driveSpeedKph.toFloat())
            .putFloat(KEY_REMAINING_METERS, fix?.metersRemaining?.toFloat() ?: -1f)
            .putBoolean(KEY_ARRIVED, fix?.arrived == true)
            .putFloat(KEY_BEARING, fix?.bearingDegrees ?: 0f)
            .putString(KEY_CURRENT_LAT, target?.latitude?.toString())
            .putString(KEY_CURRENT_LON, target?.longitude?.toString())
            .apply()
    }

    companion object {
        const val ACTION_START = "dev.codex.locationlab.action.START"
        const val ACTION_STOP = "dev.codex.locationlab.action.STOP"
        const val ACTION_PAUSE = "dev.codex.locationlab.action.PAUSE"
        const val ACTION_RESUME = "dev.codex.locationlab.action.RESUME"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_SPEED_KPH = "speed_kph"
        const val PREFS = "location_lab"
        const val KEY_STATE = "state"
        const val KEY_DETAIL = "detail"
        const val KEY_UPDATED = "updated"
        const val KEY_SIMULATING = "simulating"
        const val KEY_REMAINING_METERS = "remaining_meters"
        const val KEY_ARRIVED = "arrived"
        const val KEY_TOTAL_METERS = "total_meters"
        const val KEY_SPEED_KPH = "speed_kph_active"
        const val KEY_BEARING = "bearing"
        const val KEY_CURRENT_LAT = "current_lat"
        const val KEY_CURRENT_LON = "current_lon"
        const val STATE_ACTIVE = "active"
        const val STATE_STARTING = "starting"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 42
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_REFRESH_TICKS = 5
        private const val FIRST_FIX_TIMEOUT_MS = 30_000L
    }
}
