package dev.codex.locationlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {
    private val start = GeoPoint(12.9716, 77.5946)
    private val end = GeoPoint(12.9816, 77.5946)
    private val tenMetresPerSecond = 36.0 / 3.6

    @Test
    fun totalDistanceMatchesTheGreatCircleLengthOfTheRoute() {
        val simulator = RouteSimulator(listOf(start, end), tenMetresPerSecond)
        assertEquals(RouteSimulator.metersBetween(start, end), simulator.totalMeters, 0.5)
        assertEquals(simulator.totalMeters / tenMetresPerSecond, simulator.totalSeconds, 0.001)
    }

    @Test
    fun theFirstFixSitsOnTheStartAndCarriesTheTravelSpeedAndHeading() {
        val fix = RouteSimulator(listOf(start, end), tenMetresPerSecond).fixAt(0.0)
        assertEquals(start.latitude, fix.point.latitude, 1e-9)
        assertEquals(start.longitude, fix.point.longitude, 1e-9)
        assertEquals(10f, fix.speedMetersPerSecond, 0.001f)
        assertEquals("Due north should be reported as a zero bearing.", 0f, fix.bearingDegrees, 0.5f)
        assertFalse(fix.arrived)
    }

    @Test
    fun halfOfTheTravelTimePutsTheFixHalfwayAlongTheRoute() {
        val simulator = RouteSimulator(listOf(start, end), tenMetresPerSecond)
        val fix = simulator.fixAt(simulator.totalSeconds / 2.0)
        val midpoint = GeoPoint((start.latitude + end.latitude) / 2, start.longitude)
        assertEquals(2.0, RouteSimulator.metersBetween(fix.point, midpoint), 2.0)
        assertEquals(simulator.totalMeters / 2.0, fix.metersRemaining, 2.0)
    }

    @Test
    fun travellingPastTheEndArrivesAndStopsInsteadOfOvershooting() {
        val simulator = RouteSimulator(listOf(start, end), tenMetresPerSecond)
        val fix = simulator.fixAt(simulator.totalSeconds + 600.0)
        assertTrue(fix.arrived)
        assertEquals(0f, fix.speedMetersPerSecond, 0.0f)
        assertEquals(0.0, fix.metersRemaining, 0.001)
        assertEquals(end.latitude, fix.point.latitude, 1e-6)
        assertEquals(end.longitude, fix.point.longitude, 1e-6)
    }

    @Test
    fun aMultiLegRouteFollowsEachLegInOrder() {
        val corner = GeoPoint(12.9816, 77.6046)
        val simulator = RouteSimulator(listOf(start, end, corner), tenMetresPerSecond)
        val firstLeg = RouteSimulator.metersBetween(start, end)
        val onTheCorner = simulator.fixAt(firstLeg / tenMetresPerSecond)
        assertEquals(1.0, RouteSimulator.metersBetween(onTheCorner.point, end), 1.5)
        val secondLeg = simulator.fixAt((firstLeg + 20.0) / tenMetresPerSecond)
        assertEquals("The second leg runs east.", 90f, secondLeg.bearingDegrees, 1f)
        assertTrue(secondLeg.point.longitude > end.longitude)
    }

    @Test
    fun repeatedAndInsufficientPointsAreHandledWithoutDividingByZero() {
        val withDuplicates = RouteSimulator(listOf(start, start, end, end), tenMetresPerSecond)
        assertEquals(RouteSimulator.metersBetween(start, end), withDuplicates.totalMeters, 0.5)
        assertThrows(IllegalArgumentException::class.java) { RouteSimulator(listOf(start), tenMetresPerSecond) }
        assertThrows(IllegalArgumentException::class.java) { RouteSimulator(listOf(start, start), tenMetresPerSecond) }
    }

    @Test
    fun crossingTheAntimeridianTakesTheShortWayRound() {
        val before = GeoPoint(0.0, 179.99)
        val after = GeoPoint(0.0, -179.99)
        val simulator = RouteSimulator(listOf(before, after), tenMetresPerSecond)
        assertEquals("0.02 degrees at the equator is about 2.2 km.", 2_226.0, simulator.totalMeters, 50.0)
        val halfway = simulator.fixAt(simulator.totalSeconds / 2.0)
        assertTrue("Longitude must stay inside the valid range.", halfway.point.longitude in -180.0..180.0)
        assertEquals(180.0, kotlin.math.abs(halfway.point.longitude), 0.001)
    }

    @Test
    fun speedIsClampedSoAZeroOrAbsurdValueCannotStallOrTeleportTheFix() {
        val stalled = RouteSimulator(listOf(start, end), 0.0)
        assertTrue("A zero speed must still make progress.", stalled.totalSeconds > 0.0)
        val capped = RouteSimulator(listOf(start, end), 10_000.0)
        assertTrue(capped.fixAt(1.0).point.latitude in start.latitude..end.latitude)
    }
}
