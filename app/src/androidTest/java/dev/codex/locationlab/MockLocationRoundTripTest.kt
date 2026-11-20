package dev.codex.locationlab

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val target = GeoPoint(37.4219983, -122.084)
        val controller = MockLocationController(context, manager)
        try {
            controller.inject(target, 15f)

            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                val returned = manager.getLastKnownLocation(provider)
                assertNotNull("Android should return a fix from $provider", returned)
                assertEquals(target.latitude, returned!!.latitude, 0.000001)
                assertEquals(target.longitude, returned.longitude, 0.000001)
                @Suppress("DEPRECATION")
                val isMock = if (Build.VERSION.SDK_INT >= 31) returned.isMock else returned.isFromMockProvider
                assertTrue("Android must identify the $provider fix as mock", isMock)
            }
        } finally {
            controller.stop()
        }
    }
}
