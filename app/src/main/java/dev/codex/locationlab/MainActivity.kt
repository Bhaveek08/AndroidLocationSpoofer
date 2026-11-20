package dev.codex.locationlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val geocoderExecutor = Executors.newSingleThreadExecutor()
    private lateinit var addressInput: EditText
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var statusView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var accuracySpinner: Spinner
    private lateinit var favoritesButton: Button
    private var pendingStart = false
    private var resumed = false

    private val statusPoll = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                refreshStatus()
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshStatus()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        handler.removeCallbacks(statusPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        geocoderExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && pendingStart) {
            pendingStart = false
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startSession()
            } else {
                showStatus("Location permission is needed", "Allow precise location so the app can verify Android's returned mock fix.")
            }
        }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF3F7F6.toInt()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        scroll.addView(content)

        content.addView(text("LOCATION LAB", 13f, 0xFF176B5B.toInt(), bold = true))
        content.addView(text("Choose a location", 28f, 0xFF172C29.toInt(), bold = true), margin(top = 4, bottom = 4))
        content.addView(text("Search a place or enter coordinates. Android will label the injected fix as a mock location.", 15f, 0xFF50625E.toInt()), margin(bottom = 18))

        addressInput = edit("Search a city, address, or place")
        val searchRow = horizontal()
        searchRow.addView(addressInput, weightParams())
        searchRow.addView(button("Search") { searchAddress() }, margin(start = 8))
        content.addView(searchRow)

        val locationRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        latitudeInput = edit("Latitude", signedDecimal = true).apply { setTextSize(16f) }
        longitudeInput = edit("Longitude", signedDecimal = true).apply { setTextSize(16f) }
        locationRow.addView(latitudeInput, weightParams())
        locationRow.addView(longitudeInput, weightParams(start = 10))
        content.addView(locationRow, margin(top = 10))

        content.addView(button("Open map preview") { openMapPreview() }, margin(top = 10))
        val homeActions = horizontal()
        homeActions.addView(button("Save as Home") { saveHome() }, weightParams())
        homeActions.addView(button("Use Home") { useHome() }, weightParams(start = 8))
        content.addView(homeActions, margin(top = 6, bottom = 20))

        content.addView(text("ACCURACY", 12f, 0xFF53635F.toInt(), bold = true), margin(bottom = 6))
        accuracySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Precise · 5 m", "Balanced · 15 m", "Broad · 50 m")
            )
            setSelection(getPreferences(Context.MODE_PRIVATE).getInt(KEY_ACCURACY_INDEX, 0).coerceIn(0, 2))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    getPreferences(Context.MODE_PRIVATE).edit().putInt(KEY_ACCURACY_INDEX, position).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        content.addView(accuracySpinner)

        val favoriteRow = horizontal()
        favoritesButton = button("Saved places") { showFavorites() }
        favoriteRow.addView(favoritesButton, weightParams())
        favoriteRow.addView(button("Save current place") { addFavorite() }, weightParams(start = 8))
        content.addView(favoriteRow, margin(top = 12, bottom = 20))

        val setupCard = verticalCard()
        setupCard.addView(text("One-time Android setup", 17f, 0xFF172C29.toInt(), bold = true))
        setupCard.addView(text("Developer options → Select mock location app → Location Lab", 14f, 0xFF50625E.toInt()), margin(top = 5, bottom = 10))
        setupCard.addView(button("Open Developer options") { openDeveloperOptions() })
        content.addView(setupCard, margin(bottom = 16))

        val controlRow = horizontal()
        controlRow.addView(button("Start mock location") { startSession() }.apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF176B5B.toInt())
        }, weightParams())
        controlRow.addView(button("Stop") { stopSession() }, weightParams(start = 10))
        content.addView(controlRow)

        val statusCard = verticalCard()
        statusView = text("Checking status…", 17f, 0xFF172C29.toInt(), bold = true)
        statusDetailView = text("", 14f, 0xFF50625E.toInt())
        statusCard.addView(statusView)
        statusCard.addView(statusDetailView, margin(top = 6))
        content.addView(statusCard, margin(top = 16))

        content.addView(text("A successful Android readback confirms the system returned this mock fix to Location Lab. Other apps can detect mock locations or use separate signals.", 12f, 0xFF61716D.toInt()), margin(top = 14))
        return scroll
    }

    private fun searchAddress() {
        val query = addressInput.text.toString().trim()
        if (query.isEmpty()) {
            addressInput.error = "Enter a place to search"
            return
        }
        if (!Geocoder.isPresent()) {
            showStatus("Address search unavailable", "Enter latitude and longitude directly on this device.")
            return
        }
        showStatus("Searching…", "Looking up “$query”.")
        geocoderExecutor.execute {
            try {
                @Suppress("DEPRECATION")
                val results = Geocoder(this, Locale.getDefault()).getFromLocationName(query, 6).orEmpty()
                runOnUiThread {
                    if (results.isEmpty()) {
                        showStatus("No matches found", "Try a more specific address or enter coordinates.")
                    } else {
                        chooseAddress(results)
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread { showStatus("Search failed", failure.localizedMessage ?: "Check your connection and try again.") }
            }
        }
    }

    private fun chooseAddress(results: List<Address>) {
        val labels = results.map { address ->
            val title = address.getAddressLine(0) ?: listOfNotNull(address.locality, address.countryName).joinToString(", ")
            "$title\n${"%.5f".format(Locale.US, address.latitude)}, ${"%.5f".format(Locale.US, address.longitude)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose a result")
            .setItems(labels) { _, which ->
                val result = results[which]
                latitudeInput.setText(formatCoordinate(result.latitude))
                longitudeInput.setText(formatCoordinate(result.longitude))
                addressInput.setText(result.getAddressLine(0) ?: addressInput.text)
                showStatus("Location selected", labels[which].replace("\n", " · "))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSession() {
        val point = try {
            GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.")
            return
        }

        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        if (!isSelectedMockApp()) {
            showStatus("Select Location Lab as the mock app", "Open Developer options and choose Location Lab under Select mock location app.")
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationOn = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationOn) {
            showStatus("Turn on Location", "Android location services must be enabled for apps to receive provider updates.")
            AlertDialog.Builder(this)
                .setMessage("Turn on Android Location services, then tap Start again.")
                .setPositiveButton("Open Location settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val accuracy = when (accuracySpinner.selectedItemPosition) { 1 -> 15f; 2 -> 50f; else -> 5f }
        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LATITUDE, point.latitude)
            .putExtra(MockLocationService.EXTRA_LONGITUDE, point.longitude)
            .putExtra(MockLocationService.EXTRA_ACCURACY, accuracy)
        showStatus("Starting…", "Requesting an Android mock GPS and network fix.")
        try {
            startForegroundService(intent)
        } catch (failure: Exception) {
            showStatus("Could not start", failure.localizedMessage ?: failure.javaClass.simpleName)
        }
    }

    private fun stopSession() {
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
        showStatus("Stopping…", "Removing the mock providers from Android.")
    }

    private fun isSelectedMockApp(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 36) {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName, null)
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun saveHome() {
        val point = try {
            GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.")
            return
        }
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString(KEY_HOME_LAT, point.latitude.toString())
            .putString(KEY_HOME_LON, point.longitude.toString())
            .putString(KEY_HOME_LABEL, addressInput.text.toString().trim().ifEmpty { "Home" })
            .apply()
        showStatus("Home saved", "${point.latitude}, ${point.longitude}")
    }

    private fun useHome() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val lat = prefs.getString(KEY_HOME_LAT, null)
        val lon = prefs.getString(KEY_HOME_LON, null)
        if (lat == null || lon == null) {
            showStatus("No Home saved", "Choose coordinates, then tap Save as Home.")
            return
        }
        latitudeInput.setText(lat)
        longitudeInput.setText(lon)
        addressInput.setText(prefs.getString(KEY_HOME_LABEL, "Home"))
    }

    private fun addFavorite() {
        val point = try {
            GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.")
            return
        }
        val nameInput = EditText(this).apply { hint = "Place name"; setText(addressInput.text.toString().trim()) }
        AlertDialog.Builder(this)
            .setTitle("Save this place")
            .setView(nameInput)
            .setPositiveButton("Save") { _, _ ->
                val label = nameInput.text.toString().trim().ifEmpty { "Saved place" }
                val places = readFavorites().toMutableList()
                places.removeAll { it.name.equals(label, ignoreCase = true) }
                places.add(0, SavedPlace(label, point))
                writeFavorites(places.take(MAX_FAVORITES))
                showStatus("Place saved", label)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFavorites() {
        val favorites = readFavorites()
        if (favorites.isEmpty()) {
            addFavorite()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Saved places")
            .setItems(favorites.map { "${it.name}  ·  ${it.point.latitude}, ${it.point.longitude}" }.toTypedArray()) { _, which ->
                val selected = favorites[which]
                addressInput.setText(selected.name)
                latitudeInput.setText(formatCoordinate(selected.point.latitude))
                longitudeInput.setText(formatCoordinate(selected.point.longitude))
            }
            .setNeutralButton("Add current place") { _, _ -> addFavorite() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun readFavorites(): List<SavedPlace> {
        val array = JSONArray(getPreferences(Context.MODE_PRIVATE).getString(KEY_FAVORITES, "[]"))
        return (0 until array.length()).mapNotNull { index ->
            try {
                val item = array.getJSONObject(index)
                SavedPlace(item.getString("name"), GeoPoint(item.getDouble("lat"), item.getDouble("lon")))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun writeFavorites(places: List<SavedPlace>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(JSONObject().put("name", place.name).put("lat", place.point.latitude).put("lon", place.point.longitude))
        }
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    private fun openMapPreview() {
        val point = try {
            GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.")
            return
        }
        val label = Uri.encode(addressInput.text.toString().trim().ifEmpty { "Selected location" })
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)")))
        } catch (_: Exception) {
            showStatus("No map app found", "Enter coordinates directly or install a map app to preview places.")
        }
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val prefs = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        when (prefs.getString(MockLocationService.KEY_STATE, "stopped")) {
            "active" -> {
                statusView.setText(R.string.status_mock_fix_title)
                val timestamp = prefs.getLong(MockLocationService.KEY_UPDATED, 0L)
                val formattedTime = if (timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(timestamp) else ""
                statusDetailView.text = getString(
                    R.string.status_mock_fix_detail,
                    prefs.getString(MockLocationService.KEY_DETAIL, ""),
                    formattedTime
                )
                statusView.setTextColor(0xFF176B5B.toInt())
            }
            "error" -> {
                statusView.setText(R.string.status_mock_location_failed)
                statusDetailView.text = prefs.getString(MockLocationService.KEY_DETAIL, "Check Developer options and permissions.")
                statusView.setTextColor(0xFFB3261E.toInt())
            }
            "starting" -> showStatus("Starting…", prefs.getString(MockLocationService.KEY_DETAIL, "Setting up providers.") ?: "Setting up providers.")
            else -> {
                val ready = isSelectedMockApp()
                statusView.text = if (ready) "Ready to start" else "Setup needed"
                statusDetailView.text = if (ready) "Choose a location, then tap Start mock location." else "Select Location Lab in Developer options to enable Android mock providers."
                statusView.setTextColor(if (ready) 0xFF176B5B.toInt() else 0xFF705C00.toInt())
            }
        }
        handler.removeCallbacks(statusPoll)
        if (resumed) handler.postDelayed(statusPoll, 1_000L)
    }

    private fun showStatus(title: String, detail: String) {
        if (!::statusView.isInitialized) return
        statusView.text = title
        statusView.setTextColor(0xFF172C29.toInt())
        statusDetailView.text = detail
    }

    private fun verticalCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun edit(hintText: String, signedDecimal: Boolean = false) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(true)
        inputType = if (signedDecimal) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = getDrawable(android.R.drawable.edit_text)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun weightParams(start: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        if (start > 0) marginStart = dp(start)
    }

    private fun margin(top: Int = 0, bottom: Int = 0, start: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
        marginStart = dp(start)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun formatCoordinate(value: Double) = String.format(Locale.US, "%.8f", value)

    private data class SavedPlace(val name: String, val point: GeoPoint)

    companion object {
        private const val REQUEST_PERMISSIONS = 70
        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LON = "home_lon"
        private const val KEY_HOME_LABEL = "home_label"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ACCURACY_INDEX = "accuracy_index"
        private const val MAX_FAVORITES = 20
    }
}
