package dev.codex.locationlab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
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
