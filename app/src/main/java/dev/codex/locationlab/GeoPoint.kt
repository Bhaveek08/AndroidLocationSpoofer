package dev.codex.locationlab

/** A validated WGS84 coordinate accepted by Android's Location API. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    companion object {
        fun parse(latitude: String, longitude: String): GeoPoint {
            val lat = latitude.trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("Enter a valid latitude.")
            val lon = longitude.trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("Enter a valid longitude.")
            require(lat in -90.0..90.0) { "Latitude must be between -90 and 90." }
            require(lon in -180.0..180.0) { "Longitude must be between -180 and 180." }
            return GeoPoint(lat, lon)
        }
    }
}
