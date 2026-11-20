package dev.codex.locationlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoPointTest {
    @Test
    fun acceptsSignedDecimalCoordinatesAndWhitespace() {
        assertEquals(GeoPoint(12.9716, 77.5946), GeoPoint.parse(" 12.9716 ", "77.5946"))
        assertEquals(GeoPoint(-90.0, -180.0), GeoPoint.parse("-90", "-180"))
    }

    @Test
    fun rejectsMissingOrNonNumericCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint.parse("", "5") }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint.parse("north", "5") }
    }

    @Test
    fun rejectsCoordinatesOutsideWgs84Range() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint.parse("90.0001", "0") }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint.parse("0", "180.0001") }
    }
}
