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

/** Keeps a fixed mock fix fresh while the app is in the background. */
class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MockLocationController? = null
    private var point: GeoPoint? = null
    private var accuracyMeters: Float = 5f
    private var preserveError = false
    private val ticker = object : Runnable {
        override fun run() {
            val target = point ?: return
            try {
                controller?.inject(target, accuracyMeters)
                state("active", "Android returned a mock fix for ${target.latitude}, ${target.longitude}", System.currentTimeMillis())
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            } catch (failure: Exception) {
                preserveError = true
                state("error", failure.message ?: failure.javaClass.simpleName, 0L)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        preserveError = false

        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) {
            preserveError = true
            state("error", "Invalid coordinates.", 0L)
            stopSelf()
            return START_NOT_STICKY
        }

        point = GeoPoint(lat, lon)
        accuracyMeters = intent.getFloatExtra(EXTRA_ACCURACY, 5f).coerceIn(1f, 1000f)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(point!!))
        controller = MockLocationController(this, getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        state("starting", "Starting Android mock providers…", 0L)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            controller?.stop()
        } finally {
            controller = null
            if (!preserveError) state("stopped", "Mock location stopped.", 0L)
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSession() {
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(target: GeoPoint): Notification {
        val stopIntent = Intent(this, MockLocationService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
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
            .setContentTitle("Location Lab is active")
            .setContentText("${target.latitude}, ${target.longitude}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun state(value: String, detail: String, updatedAt: Long) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, value)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_UPDATED, updatedAt)
            .apply()
    }

    companion object {
        const val ACTION_START = "dev.codex.locationlab.action.START"
        const val ACTION_STOP = "dev.codex.locationlab.action.STOP"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val PREFS = "location_lab"
        const val KEY_STATE = "state"
        const val KEY_DETAIL = "detail"
        const val KEY_UPDATED = "updated"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 42
        private const val UPDATE_INTERVAL_MS = 5_000L
    }
}
