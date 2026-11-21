package dev.codex.locationlab

import org.json.JSONObject
import java.util.Locale

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double
)

data class DirectionsRoute(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>
)

object DirectionsRouteParser {
    fun parse(jsonText: String): DirectionsRoute {
        val response = JSONObject(jsonText)
        require(response.optString("code") == "Ok") { response.optString("message", "No route found.") }
        val route = response.getJSONArray("routes").optJSONObject(0)
            ?: error("The route service returned no route.")
        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coordinates.length()).map { index ->
            val coordinate = coordinates.getJSONArray(index)
            require(coordinate.length() >= 2) { "The route contains an invalid coordinate." }
            GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
        }
        require(points.size >= 2) { "The route did not include enough points to draw." }

        val steps = mutableListOf<RouteStep>()
        val legs = route.optJSONArray("legs")
        for (legIndex in 0 until (legs?.length() ?: 0)) {
            val leg = legs!!.optJSONObject(legIndex) ?: continue
            val legSteps = leg.optJSONArray("steps") ?: continue
            for (stepIndex in 0 until legSteps.length()) {
                val step = legSteps.optJSONObject(stepIndex) ?: continue
                val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
                steps += RouteStep(
                    instruction = describeManeuver(
                        maneuver.optString("type"),
                        maneuver.optString("modifier"),
                        step.optString("name"),
                        maneuver.optInt("exit", 0)
                    ),
                    distanceMeters = step.optDouble("distance", 0.0).coerceAtLeast(0.0),
                    durationSeconds = step.optDouble("duration", 0.0).coerceAtLeast(0.0)
                )
            }
        }

        return DirectionsRoute(
            points = points,
            distanceMeters = route.getDouble("distance").coerceAtLeast(0.0),
            durationSeconds = route.getDouble("duration").coerceAtLeast(0.0),
            steps = steps
        )
    }

    private fun describeManeuver(type: String, modifier: String, roadName: String, exit: Int): String {
        val road = roadName.trim().ifEmpty { "the road" }
        val direction = modifier.trim().replace('-', ' ')
        return when (type) {
            "depart" -> "Head ${direction.ifEmpty { "straight" }} on $road"
            "arrive" -> "Arrive at your destination"
            "turn", "end of road", "fork", "merge", "on ramp", "off ramp", "exit roundabout" ->
                "${type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} ${direction.ifEmpty { "ahead" }} onto $road"
            "roundabout", "rotary" -> "At the roundabout, take ${if (exit > 0) "exit $exit" else "the exit"} onto $road"
            "continue", "new name" -> "Continue ${direction.ifEmpty { "straight" }} on $road"
            "notification", "" -> "Continue on $road"
            else -> "${type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} on $road"
        }
    }
}

fun formatRouteDistance(meters: Double): String = if (meters >= 1_000.0) {
    String.format(Locale.getDefault(), "%.1f km", meters / 1_000.0)
} else {
    String.format(Locale.getDefault(), "%.0f m", meters)
}

fun formatRouteDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt().coerceAtLeast(0)
    return if (minutes >= 60) {
        String.format(Locale.getDefault(), "%d hr %d min", minutes / 60, minutes % 60)
    } else {
        "$minutes min"
    }
}
