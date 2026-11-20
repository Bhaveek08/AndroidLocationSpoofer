package dev.codex.locationlab

import org.json.JSONArray
import org.json.JSONObject

internal data class BackupPlace(val name: String, val latitude: Double, val longitude: Double)

internal data class SavedPlacesBackup(val home: BackupPlace?, val favorites: List<BackupPlace>)

internal object SavedPlacesBackupCodec {
    private const val PRODUCT = "GeoShift"
    private const val SCHEMA_VERSION = 1
    private const val MAX_FAVORITES = 20
    private const val MAX_NAME_LENGTH = 60

    fun encode(backup: SavedPlacesBackup): String {
        require(backup.favorites.size <= MAX_FAVORITES) { "A backup can contain up to $MAX_FAVORITES saved places." }
        return JSONObject()
            .put("product", PRODUCT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("home", backup.home?.toJson() ?: JSONObject.NULL)
            .put("favorites", JSONArray().apply { backup.favorites.forEach { put(it.toJson()) } })
            .toString(2)
    }

    fun decode(json: String): SavedPlacesBackup {
        require(json.length <= 128_000) { "This backup file is too large." }
        val root = JSONObject(json)
        require(root.getString("product") == PRODUCT) { "This is not a GeoShift saved-places backup." }
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "This backup version is not supported." }
        val home = root.optJSONObject("home")?.toBackupPlace()
        val favoritesJson = root.optJSONArray("favorites") ?: JSONArray()
        require(favoritesJson.length() <= MAX_FAVORITES) { "A backup can contain up to $MAX_FAVORITES saved places." }
        val favorites = (0 until favoritesJson.length()).map { index ->
            favoritesJson.getJSONObject(index).toBackupPlace()
        }
        return SavedPlacesBackup(home, favorites)
    }

    private fun BackupPlace.toJson() = JSONObject()
        .put("name", name)
        .put("latitude", latitude)
        .put("longitude", longitude)

    private fun JSONObject.toBackupPlace(): BackupPlace {
        val name = getString("name").trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) {
            "Place names must contain 1–$MAX_NAME_LENGTH characters."
        }
        val point = GeoPoint.parse(get("latitude").toString(), get("longitude").toString())
        return BackupPlace(name, point.latitude, point.longitude)
    }
}
