package dev.codex.locationlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionsRouteParserTest {
    private val okResponse = """{
        "code":"Ok",
        "routes":[{
            "distance":1250.0,
            "duration":420.0,
            "geometry":{"type":"LineString","coordinates":[[77.5946,12.9716],[77.6000,12.9750]]},
            "legs":[{"steps":[
                {"distance":300.0,"duration":90.0,"name":"MG Road","maneuver":{"type":"depart","modifier":"straight"}},
                {"distance":950.0,"duration":330.0,"name":"Brigade Road","maneuver":{"type":"turn","modifier":"right"}},
                {"distance":0.0,"duration":0.0,"name":"","maneuver":{"type":"arrive"}}
            ]}]
        }]
    }"""

    @Test
    fun keepsGeoJsonCoordinateOrderAndTurnSteps() {
        val route = DirectionsRouteParser.parse(okResponse)
        assertEquals(2, route.points.size)
        assertEquals(12.9716, route.points.first().latitude, 1e-6)
        assertEquals(77.6000, route.points.last().longitude, 1e-6)
        assertEquals(1250.0, route.distanceMeters, 0.0)
        assertEquals(420.0, route.durationSeconds, 0.0)
        assertEquals(3, route.steps.size)
        assertTrue(route.steps[0].instruction.contains("MG Road"))
        assertTrue(route.steps[1].instruction.contains("right"))
        assertEquals("Arrive at your destination", route.steps[2].instruction)
    }

    @Test
    fun aParsedRouteCanBeDrivenBySimulation() {
        val route = DirectionsRouteParser.parse(okResponse)
        val simulator = RouteSimulator(route.points, 10.0)
        assertTrue(simulator.totalMeters > 0.0)
        assertTrue(simulator.fixAt(simulator.totalSeconds).arrived)
    }

    @Test
    fun rejectsServiceErrorsAndTruncatedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectionsRouteParser.parse("""{"code":"NoRoute","message":"No route found."}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectionsRouteParser.parse("""{"code":"Ok","routes":[{"distance":1.0,"duration":1.0,"geometry":{"coordinates":[[1.0,2.0]]}}]}""")
        }
    }

    @Test
    fun formatsDistanceAndDurationForDisplay() {
        assertEquals("950 m", formatRouteDistance(950.0))
        assertEquals("1.3 km", formatRouteDistance(1_250.0))
        assertEquals("7 min", formatRouteDuration(420.0))
        assertEquals("1 hr 5 min", formatRouteDuration(3_900.0))
    }
}
