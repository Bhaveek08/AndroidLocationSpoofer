package dev.codex.locationlab

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One simulated fix along a route. */
data class SimulatedFix(
    val point: GeoPoint,
    val bearingDegrees: Float,
    val speedMetersPerSecond: Float,
    val metersRemaining: Double,
    val arrived: Boolean
)

/**
 * Walks a polyline at a constant ground speed. Pure math with no Android types, so the
 * movement logic is covered by JVM unit tests instead of needing a device.
 */
class RouteSimulator(points: List<GeoPoint>, speedMetersPerSecond: Double) {
    private val points: List<GeoPoint> = points.filterIndexed { index, point ->
        index == 0 || point != points[index - 1]
    }
    private val speed = speedMetersPerSecond.coerceIn(MIN_SPEED, MAX_SPEED)

    /** Distance from the start of the route to each point, so a lookup is a binary search. */
    private val cumulative: DoubleArray = DoubleArray(this.points.size).also { out ->
        for (index in 1 until this.points.size) {
            out[index] = out[index - 1] + metersBetween(this.points[index - 1], this.points[index])
        }
    }

    val totalMeters: Double = cumulative.lastOrNull() ?: 0.0
    val totalSeconds: Double = if (speed <= 0.0) 0.0 else totalMeters / speed

    init {
        require(this.points.size >= 2) { "A simulated route needs at least two distinct points." }
    }

    fun fixAt(elapsedSeconds: Double): SimulatedFix {
        val travelled = (elapsedSeconds.coerceAtLeast(0.0) * speed).coerceIn(0.0, totalMeters)
        val arrived = travelled >= totalMeters
        // ponytail: linear scan; routes are a few thousand points at most. Binary search if that changes.
        var upper = 1
        while (upper < points.lastIndex && cumulative[upper] < travelled) upper++
        val start = points[upper - 1]
        val end = points[upper]
        val segmentMeters = cumulative[upper] - cumulative[upper - 1]
        val progress = if (segmentMeters <= 0.0) 1.0 else ((travelled - cumulative[upper - 1]) / segmentMeters).coerceIn(0.0, 1.0)
        return SimulatedFix(
            point = GeoPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * progress,
                longitude = start.longitude + interpolateLongitude(start.longitude, end.longitude) * progress
            ).normalized(),
            bearingDegrees = bearingBetween(start, end),
            speedMetersPerSecond = if (arrived) 0f else speed.toFloat(),
            metersRemaining = (totalMeters - travelled).coerceAtLeast(0.0),
            arrived = arrived
        )
    }

    private fun GeoPoint.normalized(): GeoPoint {
        var lon = longitude
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return GeoPoint(latitude.coerceIn(-90.0, 90.0), lon)
    }

    companion object {
        private const val MIN_SPEED = 0.1
        private const val MAX_SPEED = 90.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Shortest signed longitude delta, so a route crossing the antimeridian still interpolates forwards. */
        private fun interpolateLongitude(from: Double, to: Double): Double {
            val delta = to - from
            return when {
                delta > 180.0 -> delta - 360.0
                delta < -180.0 -> delta + 360.0
                else -> delta
            }
        }

        fun metersBetween(first: GeoPoint, second: GeoPoint): Double {
            val firstLat = Math.toRadians(first.latitude)
            val secondLat = Math.toRadians(second.latitude)
            val latitudeDelta = secondLat - firstLat
            val longitudeDelta = Math.toRadians(interpolateLongitude(first.longitude, second.longitude))
            val haversine = sin(latitudeDelta / 2).let { it * it } +
                cos(firstLat) * cos(secondLat) * sin(longitudeDelta / 2).let { it * it }
            return 2 * EARTH_RADIUS_METERS * kotlin.math.asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        }

        fun bearingBetween(from: GeoPoint, to: GeoPoint): Float {
            val fromLat = Math.toRadians(from.latitude)
            val toLat = Math.toRadians(to.latitude)
            val longitudeDelta = Math.toRadians(interpolateLongitude(from.longitude, to.longitude))
            if (abs(longitudeDelta) < 1e-12 && abs(toLat - fromLat) < 1e-12) return 0f
            val y = sin(longitudeDelta) * cos(toLat)
            val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(longitudeDelta)
            return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
        }
    }
}
