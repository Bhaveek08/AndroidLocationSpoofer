package dev.codex.locationlab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockLocationRoundTripTest {
    @Test
    fun spoofControlIsOneToggleWithClearOnAndOffStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Location Lab launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val root = activity.window.decorView
        val toggle = root.findViewWithTag("spoof-toggle") as? android.widget.TextView
            ?: error("The single spoof toggle was not shown.")
        val prefs = context.getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        val oldState = prefs.getString(MockLocationService.KEY_STATE, null)
        try {
            assertEquals("Start spoofing", toggle.contentDescription)
            assertEquals("The control should fill the action row.", ViewGroup.LayoutParams.MATCH_PARENT, toggle.layoutParams.width)
            assertTrue("There should be no separate stop control.", root.findViewWithTag<View>("stop-mock") == null)
            instrumentation.runOnMainSync {
                prefs.edit().putString(MockLocationService.KEY_STATE, "active").commit()
                activity.javaClass.getDeclaredMethod("refreshStatus").apply { isAccessible = true }.invoke(activity)
            }
            assertEquals("Stop spoofing", toggle.contentDescription)
            assertTrue("Active state should be explicit on the button.", toggle.text.toString().contains("Stop spoofing"))
            instrumentation.runOnMainSync {
                prefs.edit().putString(MockLocationService.KEY_STATE, "stopped").commit()
                activity.javaClass.getDeclaredMethod("refreshStatus").apply { isAccessible = true }.invoke(activity)
            }
            assertEquals("Start spoofing", toggle.contentDescription)
        } finally {
            val editor = prefs.edit()
            if (oldState == null) editor.remove(MockLocationService.KEY_STATE) else editor.putString(MockLocationService.KEY_STATE, oldState)
            editor.commit()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun savedPlacesAreVisibleAndSelectTheirCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Location Lab launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val root = activity.window.decorView
        val latitude = root.findViewWithTag("latitude-input") as EditText
        val longitude = root.findViewWithTag("longitude-input") as EditText
        var hadFavorites = false
        var previousFavorites: String? = null
        try {
            instrumentation.runOnMainSync {
                val prefs = activity.getPreferences(Context.MODE_PRIVATE)
                hadFavorites = prefs.contains("favorites")
                previousFavorites = prefs.getString("favorites", null)
                val samples = JSONArray()
                    .put(JSONObject().put("name", "First test place").put("lat", 12.5).put("lon", 44.25))
                    .put(JSONObject().put("name", "Second test place").put("lat", -7.75).put("lon", 151.2))
                prefs.edit().putString("favorites", samples.toString()).commit()
                activity.javaClass.getDeclaredMethod("refreshSavedPlacesStrip").apply { isAccessible = true }.invoke(activity)
                val first = root.findViewWithTag("saved-place-first-test-place") as? View
                    ?: error("The first saved place was not visible in the map sheet.")
                val second = root.findViewWithTag("saved-place-second-test-place") as? View
                    ?: error("The second saved place was not visible in the map sheet.")
                first.performClick()
                assertTrue("Saved place chip should respond to taps.", first.isClickable && second.isClickable)
            }
            assertEquals("12.50000000", latitude.text.toString())
            assertEquals("44.25000000", longitude.text.toString())
        } finally {
            instrumentation.runOnMainSync {
                val prefs = activity.getPreferences(Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (hadFavorites) editor.putString("favorites", previousFavorites) else editor.remove("favorites")
                editor.commit()
                activity.javaClass.getDeclaredMethod("refreshSavedPlacesStrip").apply { isAccessible = true }.invoke(activity)
                activity.finish()
            }
        }
    }

    @Test
    fun nativeMapLoadsAndTapSelectsAValidCoordinate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Location Lab launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val root = activity.window.decorView
        val map = root.findViewWithTag("location-map") as? View
            ?: error("The interactive map was not added to the screen.")
        val latitude = root.findViewWithTag("latitude-input") as? EditText
            ?: error("Latitude input was not added to the screen.")
        val longitude = root.findViewWithTag("longitude-input") as? EditText
            ?: error("Longitude input was not added to the screen.")
        val zoomIn = root.findViewWithTag("zoom-in") as? View
            ?: error("The visible zoom-in control was not added to the screen.")
        val detailsToggle = root.findViewWithTag("details-toggle") as? View
            ?: error("The expandable location controls were not added to the screen.")
        val detailsGroup = root.findViewWithTag("extra-location-controls") as? View
            ?: error("The extra location controls were not added to the screen.")
        try {
            val readyDeadline = SystemClock.elapsedRealtime() + 30_000L
            while (SystemClock.elapsedRealtime() < readyDeadline &&
                map.contentDescription?.toString()?.contains("ready", ignoreCase = true) != true
            ) Thread.sleep(250L)
            assertTrue("OpenStreetMap style and tile map should finish loading.",
                map.contentDescription?.toString()?.contains("ready", ignoreCase = true) == true)
            assertEquals("The default map panel should stay compact.", View.GONE, detailsGroup.visibility)
            instrumentation.runOnMainSync { detailsToggle.performClick() }
            assertEquals("The panel toggle should reveal saved-place and status controls.", View.VISIBLE, detailsGroup.visibility)
            instrumentation.runOnMainSync { detailsToggle.performClick() }
            assertEquals("The panel toggle should collapse the extra controls again.", View.GONE, detailsGroup.visibility)

            var zoomBefore = 0.0
            instrumentation.runOnMainSync {
                val mapField = activity.javaClass.getDeclaredField("map").apply { isAccessible = true }
                zoomBefore = (mapField.get(activity) as org.maplibre.android.maps.MapLibreMap).cameraPosition.zoom
                zoomIn.performClick()
            }
            Thread.sleep(900L)
            var zoomAfter = 0.0
            instrumentation.runOnMainSync {
                val mapField = activity.javaClass.getDeclaredField("map").apply { isAccessible = true }
                zoomAfter = (mapField.get(activity) as org.maplibre.android.maps.MapLibreMap).cameraPosition.zoom
            }
            assertTrue("The on-screen + control should zoom the native map in.", zoomAfter > zoomBefore)

            instrumentation.runOnMainSync {
                val x = map.width / 2f
                val y = map.height / 2f
                val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(SystemClock.uptimeMillis() + 80, SystemClock.uptimeMillis() + 80, MotionEvent.ACTION_UP, x, y, 0)
                map.dispatchTouchEvent(down)
                map.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
            val tapDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < tapDeadline && (latitude.text.isBlank() || longitude.text.isBlank())) {
                Thread.sleep(200L)
            }
            val lat = latitude.text.toString().toDoubleOrNull()
            val lon = longitude.text.toString().toDoubleOrNull()
            assertTrue("Map tap should select a valid latitude.", lat != null && lat in -90.0..90.0)
            assertTrue("Map tap should select a valid longitude.", lon != null && lon in -180.0..180.0)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun androidReturnsTheInjectedMockGpsAndNetworkFix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Location Lab launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val target = GeoPoint(37.4219983, -122.084)
        val received = CountDownLatch(1)
        var confirmedProviders = emptySet<String>()
        val controller = MockLocationController(context, manager) { _, providers ->
            confirmedProviders = providers
            received.countDown()
        }
        try {
            controller.inject(target, 15f)
            assertTrue("Android should deliver the mock fix to both providers.", received.await(15, TimeUnit.SECONDS))
            assertEquals(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), confirmedProviders)
        } finally {
            controller.stop()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun foregroundServiceKeepsTheFixFreshAndStopsCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Location Lab launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = context.getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        try {
            val start = Intent(context, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_START)
                .putExtra(MockLocationService.EXTRA_LATITUDE, 37.4219983)
                .putExtra(MockLocationService.EXTRA_LONGITUDE, -122.084)
                .putExtra(MockLocationService.EXTRA_ACCURACY, 15f)
            instrumentation.runOnMainSync { activity.startForegroundService(start) }

            val startedState = waitForState(prefs, "active", 20_000L)
            assertEquals(
                "Expected verified mock updates; service detail: ${prefs.getString(MockLocationService.KEY_DETAIL, "")}",
                "active",
                startedState
            )
            val firstUpdate = prefs.getLong(MockLocationService.KEY_UPDATED, 0L)
            assertTrue("The service should record an Android readback timestamp.", firstUpdate > 0L)
            Thread.sleep(6_000L)
            assertTrue("The foreground service should refresh the fix while running.", prefs.getLong(MockLocationService.KEY_UPDATED, 0L) > firstUpdate)

            val stop = Intent(context, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP)
            instrumentation.runOnMainSync { activity.startService(stop) }
            assertEquals("stopped", waitForState(prefs, "stopped", 10_000L))
        } finally {
            val stop = Intent(context, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP)
            instrumentation.runOnMainSync {
                try {
                    activity.startService(stop)
                } catch (_: Exception) {
                    // The service may already have stopped after a failed assertion.
                }
                activity.finish()
            }
        }
    }

    private fun waitForState(prefs: android.content.SharedPreferences, expected: String, timeoutMs: Long): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = prefs.getString(MockLocationService.KEY_STATE, "stopped") ?: "stopped"
            if (current == expected || current == "error") return current
            Thread.sleep(200L)
        }
        return prefs.getString(MockLocationService.KEY_STATE, "stopped") ?: "stopped"
    }
}
