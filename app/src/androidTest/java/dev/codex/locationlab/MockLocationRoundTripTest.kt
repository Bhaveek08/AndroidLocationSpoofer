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
    fun startButtonStartsAndVerifiesTheMockProviders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = context.getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        val mockToggle = activity.window.decorView.findViewWithTag("spoof-toggle") as? android.widget.TextView
            ?: error("The spoof toggle was not shown.")
        try {
            instrumentation.runOnMainSync {
                activity.startService(Intent(context, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
            }
            assertEquals("The test must begin with mock mode stopped.", "stopped", waitForState(prefs, "stopped", 10_000L))
            instrumentation.runOnMainSync {
                (activity.window.decorView.findViewWithTag("latitude-input") as EditText).setText("37.4219983")
                (activity.window.decorView.findViewWithTag("longitude-input") as EditText).setText("-122.084")
            }
            instrumentation.runOnMainSync { mockToggle.performClick() }
            // No real fix is staged, so this also proves a missing real fix cannot block a start.
            val startedState = waitForState(prefs, "active", 45_000L)
            assertEquals(
                "Start button did not activate both mock providers. Service detail: ${prefs.getString(MockLocationService.KEY_DETAIL, "")}",
                "active",
                startedState
            )
            assertEquals("Stop spoofing", mockToggle.contentDescription)
            instrumentation.runOnMainSync { mockToggle.performClick() }
            assertEquals("The same button should stop the session.", "stopped", waitForState(prefs, "stopped", 10_000L))
        } finally {
            val stop = Intent(context, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP)
            instrumentation.runOnMainSync {
                runCatching { activity.startService(stop) }
                activity.finish()
            }
        }
    }

    @Test
    fun privacyCheckupReportsLimitsAndCanBeDismissed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                activity.javaClass.getDeclaredMethod("showPrivacyCheckup").apply { isAccessible = true }.invoke(activity)
            }
            val ui = instrumentation.uiAutomation
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var foundWarning = false
            while (SystemClock.uptimeMillis() < deadline && !foundWarning) {
                val root = ui.rootInActiveWindow
                foundWarning = root?.let {
                    it.findAccessibilityNodeInfosByText("Privacy checkup").isNotEmpty() &&
                        it.findAccessibilityNodeInfosByText("GeoShift changes Android mock GPS/network fixes only.").isNotEmpty()
                } == true
                if (!foundWarning) SystemClock.sleep(100L)
            }
            assertTrue("Privacy checkup should explain Android mock-location limits.", foundWarning)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun mapTapModeClearlySwitchesBetweenSpoofAndDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val toggle = activity.window.decorView.findViewWithTag("map-pick-mode") as? android.widget.TextView
            ?: error("The map tap-mode control was not shown.")
        try {
            assertTrue(toggle.text.toString().contains("spoof", ignoreCase = true))
            instrumentation.runOnMainSync { toggle.performClick() }
            assertTrue("The second mode should clearly select a directions destination.", toggle.text.toString().contains("destination", ignoreCase = true))
            instrumentation.runOnMainSync { toggle.performClick() }
            assertTrue("The control should switch back to spoof target selection.", toggle.text.toString().contains("spoof", ignoreCase = true))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun spoofControlIsOneToggleWithClearOnAndOffStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val root = activity.window.decorView
        val toggle = root.findViewWithTag("spoof-toggle") as? android.widget.TextView
            ?: error("The single spoof toggle was not shown.")
        val prefs = context.getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        val oldState = prefs.getString(MockLocationService.KEY_STATE, null)
        try {
            assertEquals("The launcher name should match the GeoShift brand.", "GeoShift", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
            assertEquals("Start spoofing", toggle.contentDescription)
            assertTrue("The action should use a consistent vector icon.", toggle.compoundDrawablesRelative[0] != null)
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
            ?: error("GeoShift launcher activity was not found.")
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
                val selected = root.findViewWithTag<View>("saved-place-first-test-place")
                    ?: error("The selected place card should remain visible after loading it.")
                assertTrue("The selected saved place should be announced as selected.", selected.contentDescription.toString().contains("selected"))
            }
            assertEquals("12.5", latitude.text.toString())
            assertEquals("44.25", longitude.text.toString())
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
        wakeDevice(instrumentation)
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val root = activity.window.decorView
        val appRoot = root.findViewWithTag<View>("app-root")
            ?: error("The themed app surface was not added to the screen.")
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
            val rootColor = (appRoot.background as? android.graphics.drawable.ColorDrawable)?.color
            assertEquals("The app surface should use the premium dark palette.", 0xFF0B1114.toInt(), rootColor)
            val readyDeadline = SystemClock.elapsedRealtime() + 30_000L
            while (SystemClock.elapsedRealtime() < readyDeadline &&
                map.contentDescription?.toString()?.contains("ready", ignoreCase = true) != true
            ) Thread.sleep(250L)
            assertTrue("The OpenFreeMap dark vector style should finish loading.",
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
    fun mapControlsStayResponsiveAcrossOneHundredInteractions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        wakeDevice(instrumentation)
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
        val activity = instrumentation.startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val root = activity.window.decorView
        val mapView = root.findViewWithTag("location-map") as? View
            ?: error("The interactive map was not added to the screen.")
        val detailsToggle = root.findViewWithTag("details-toggle") as? View
            ?: error("The extra-controls toggle was not added to the screen.")
        val detailsGroup = root.findViewWithTag("extra-location-controls") as? View
            ?: error("The extra-controls panel was not added to the screen.")
        val zoomIn = root.findViewWithTag("zoom-in") as? View
            ?: error("The zoom-in control was not added to the screen.")
        val zoomOut = root.findViewWithTag("zoom-out") as? View
            ?: error("The zoom-out control was not added to the screen.")
        try {
            val readyDeadline = SystemClock.elapsedRealtime() + 45_000L
            while (SystemClock.elapsedRealtime() < readyDeadline &&
                mapView.contentDescription?.toString()?.contains("ready", ignoreCase = true) != true
            ) Thread.sleep(200L)
            assertTrue("The map should be visibly ready before stress interactions.",
                mapView.contentDescription?.toString()?.contains("ready", ignoreCase = true) == true)
            val density = activity.resources.displayMetrics.density
            listOf(detailsToggle, zoomIn, zoomOut).forEach { control ->
                assertTrue("Every map control should be visible, clickable, and at least 48dp high.",
                    control.isShown && control.isClickable && control.height >= 48f * density)
            }

            val mapField = activity.javaClass.getDeclaredField("map").apply { isAccessible = true }
            val map = mapField.get(activity) as org.maplibre.android.maps.MapLibreMap
            var interactions = 0
            repeat(25) { cycle ->
                instrumentation.runOnMainSync {
                    assertTrue("Expand should accept a tap in cycle $cycle.", detailsToggle.performClick())
                    interactions++
                    assertEquals("Expanded controls should be visible in cycle $cycle.", View.VISIBLE, detailsGroup.visibility)
                    assertTrue("Collapse should accept a tap in cycle $cycle.", detailsToggle.performClick())
                    interactions++
                    assertEquals("Expanded controls should collapse in cycle $cycle.", View.GONE, detailsGroup.visibility)
                }
                val beforeIn = map.cameraPosition.zoom
                instrumentation.runOnMainSync { assertTrue("Zoom in should accept a tap in cycle $cycle.", zoomIn.performClick()) }
                interactions++
                waitForZoom(map, beforeIn, zoomingIn = true, cycle = cycle)
                val afterIn = map.cameraPosition.zoom
                instrumentation.runOnMainSync { assertTrue("Zoom out should accept a tap in cycle $cycle.", zoomOut.performClick()) }
                interactions++
                waitForZoom(map, afterIn, zoomingIn = false, cycle = cycle)
            }
            assertEquals("The stress pass should verify exactly 100 real UI control activations.", 100, interactions)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertTrue("The app should still render a frame after the repeated interactions.", screenshot != null)
            screenshot?.recycle()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun aDrivenRouteMovesTheDeliveredMockFixTowardsTheDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val prefs = context.getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // A straight 2 km leg at 100 km/h, so a few seconds of simulation is unmistakable movement.
        val route = doubleArrayOf(37.4000, -122.0840, 37.4180, -122.0840)
        val start = Intent(context, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LATITUDE, route[0])
            .putExtra(MockLocationService.EXTRA_LONGITUDE, route[1])
            .putExtra(MockLocationService.EXTRA_ACCURACY, 5f)
            .putExtra(MockLocationService.EXTRA_ROUTE, route)
            .putExtra(MockLocationService.EXTRA_SPEED_KPH, 100.0)
        try {
            context.startForegroundService(start)
            assertEquals(
                "The simulated drive should reach the active state. Detail: ${prefs.getString(MockLocationService.KEY_DETAIL, "")}",
                "active",
                waitForState(prefs, "active", 30_000L)
            )
            assertTrue("The session should report that it is simulating a route.",
                prefs.getBoolean(MockLocationService.KEY_SIMULATING, false))
            val first = awaitMockFix(manager)
            SystemClock.sleep(6_000L)
            val later = awaitMockFix(manager)
            val moved = RouteSimulator.metersBetween(
                GeoPoint(first.latitude, first.longitude),
                GeoPoint(later.latitude, later.longitude)
            )
            assertTrue("A 100 km/h drive should cover well over 50 m in six seconds, moved ${moved}m.", moved > 50.0)
            assertTrue("A moving fix should carry a non-zero speed.", later.speed > 1f)
            assertTrue("Distance remaining should shrink as the drive progresses.",
                prefs.getFloat(MockLocationService.KEY_REMAINING_METERS, -1f) < 2_000f)
        } finally {
            context.startService(Intent(context, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
            waitForState(prefs, "stopped", 10_000L)
        }
    }

    @Test
    fun theFusedProviderIsMockedWhereAndroidSupportsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val controller = MockLocationController(context, manager)
        try {
            controller.inject(GeoPoint(48.8584, 2.2945))
            val expected = buildList {
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            }
            assertEquals(
                "Apps that read Play Services' fused client need the fused provider mocked too.",
                expected.sorted(),
                controller.providerNames.sorted()
            )
        } finally {
            controller.stop()
        }
    }

    private fun awaitMockFix(manager: LocationManager): android.location.Location {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val fix = runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            @Suppress("DEPRECATION")
            val isMock = fix != null && if (Build.VERSION.SDK_INT >= 31) fix.isMock else fix.isFromMockProvider
            if (fix != null && isMock) return fix
            SystemClock.sleep(200L)
        }
        error("Android never returned a mock GPS fix for the simulated drive.")
    }

    private fun wakeDevice(instrumentation: android.app.Instrumentation) {
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
    }

    private fun waitForZoom(
        map: org.maplibre.android.maps.MapLibreMap,
        before: Double,
        zoomingIn: Boolean,
        cycle: Int
    ) {
        val deadline = SystemClock.elapsedRealtime() + 1_500L
        while (SystemClock.elapsedRealtime() < deadline) {
            val zoom = map.cameraPosition.zoom
            if (if (zoomingIn) zoom > before else zoom < before) return
            Thread.sleep(40L)
        }
        assertTrue("Zoom ${if (zoomingIn) "in" else "out"} should change the camera in cycle $cycle.",
            if (zoomingIn) map.cameraPosition.zoom > before else map.cameraPosition.zoom < before)
    }

    @Test
    fun androidReturnsTheInjectedMockGpsAndNetworkFix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("GeoShift launcher activity was not found.")
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
            ?: error("GeoShift launcher activity was not found.")
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
