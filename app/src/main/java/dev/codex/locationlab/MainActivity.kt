package dev.codex.locationlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import okhttp3.OkHttpClient
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var currentSource: GeoJsonSource? = null
    private var mockSource: GeoJsonSource? = null
    private var pendingStart = false
    private var pendingLocate = false
    private var resumed = false
    private var realLocation: GeoPoint? = null
    private var currentLocationListener: LocationListener? = null
    private val debouncedMapSync = Runnable { syncMapToFields() }
    private val currentLocationTimeout = Runnable {
        stopCurrentLocationUpdates()
        if (realLocation == null) showStatus("Location not found", "Check that Android Location is on, then try again.")
    }

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
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "LocationLab/0.2.0 (+https://github.com/Bhaveek08/AndroidLocationSpoofer)")
                    .build())
            }.build()
        )
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::mapView.isInitialized) mapView.onResume()
        refreshStatus()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        handler.removeCallbacks(statusPoll)
        if (::mapView.isInitialized) mapView.onPause()
        stopCurrentLocationUpdates()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        handler.removeCallbacks(debouncedMapSync)
        handler.removeCallbacks(currentLocationTimeout)
        stopCurrentLocationUpdates()
        if (::mapView.isInitialized) mapView.onDestroy()
        geocoderExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onStop() { if (::mapView.isInitialized) mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
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
        if (requestCode == REQUEST_PERMISSIONS && pendingLocate) {
            pendingLocate = false
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locateCurrentLocation()
            } else {
                showStatus("Location permission is needed", "Allow precise location to show your device position on the map.")
            }
        }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF2F6F5.toInt()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(30))
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    topInset = insets.systemWindowInsetTop
                    bottomInset = insets.systemWindowInsetBottom
                }
            }
            content.setPadding(dp(16), topInset + dp(12), dp(16), bottomInset + dp(20))
            insets
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(19), dp(20), dp(20))
            background = rounded(0xFF124E43.toInt(), 24)
        }
        hero.addView(text("LOCATION LAB  ·  MOCK GPS", 12f, 0xFFB9DED4.toInt(), bold = true))
        hero.addView(text("Pick a place", 29f, Color.WHITE, bold = true), margin(top = 5, bottom = 4))
        hero.addView(text("Choose it on the map or search an address. Android labels these as mock fixes.", 14f, 0xFFE0EEEA.toInt()))
        content.addView(hero, margin(bottom = 14))

        val searchCard = verticalCard()
        searchCard.addView(text("SEARCH AN ADDRESS", 11f, 0xFF60716C.toInt(), bold = true), margin(bottom = 7))

        addressInput = edit("Search a city, address, or place")
        val searchRow = horizontal()
        searchRow.addView(addressInput, weightParams())
        searchRow.addView(button("Find") { searchAddress() }.apply { setTextColor(0xFFFFFFFF.toInt()); background = rounded(0xFF176B5B.toInt(), 14) }, LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        searchCard.addView(searchRow)
        content.addView(searchCard, margin(bottom = 12))

        val mapCard = verticalCard()
        mapCard.addView(text("Choose on the map", 18f, 0xFF183A34.toInt(), bold = true))
        mapCard.addView(text("Drag to explore · tap to drop a pin", 13f, 0xFF60716C.toInt()), margin(top = 3, bottom = 10))
        val legend = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        legend.addView(colorDot(0xFF2473D4.toInt()), LinearLayout.LayoutParams(dp(10), dp(10)))
        legend.addView(text("Last real fix", 12f, 0xFF445B55.toInt(), bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        legend.addView(colorDot(0xFF087E69.toInt()), LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(14) })
        legend.addView(text("Mock point", 12f, 0xFF445B55.toInt(), bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        mapCard.addView(legend, margin(bottom = 9))
        mapView = mapView.apply {
            tag = "location-map"
            contentDescription = "Interactive map. Tap to choose a location. OpenStreetMap tiles."
            getMapAsync { mapLibre ->
                map = mapLibre
                mapLibre.uiSettings.isRotateGesturesEnabled = false
                mapLibre.uiSettings.isTiltGesturesEnabled = false
                val styleJson = assets.open("map-style.json").bufferedReader().use { it.readText() }
                mapLibre.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    mapView.contentDescription = "Interactive OpenStreetMap ready. Tap to choose a mock location."
                    currentSource = GeoJsonSource(SOURCE_CURRENT, FeatureCollection.fromFeatures(emptyArray()))
                    mockSource = GeoJsonSource(SOURCE_MOCK, FeatureCollection.fromFeatures(emptyArray()))
                    style.addSource(currentSource!!)
                    style.addSource(mockSource!!)
                    style.addLayer(CircleLayer("current-location-layer", SOURCE_CURRENT).withProperties(
                        circleRadius(9f), circleColor(0xFF2473D4.toInt()), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)
                    ))
                    style.addImage(MOCK_ICON, createMockPin())
                    style.addLayer(SymbolLayer("mock-location-layer", SOURCE_MOCK).withProperties(
                        iconImage(MOCK_ICON), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)
                    ))
                    mapLibre.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 2.5))
                    mapLibre.addOnMapClickListener { point ->
                        latitudeInput.setText(formatCoordinate(point.latitude))
                        longitudeInput.setText(formatCoordinate(point.longitude))
                        showStatus("Map point selected", "${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}")
                        true
                    }
                    syncMapToFields()
                    realLocation?.let { updateCurrentPin(it) }
                }
            }
            isClickable = true
        }
        val mapFrame = FrameLayout(this)
        mapFrame.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        val attribution = text("© OpenStreetMap", 10f, 0xFF30443E.toInt()).apply {
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = rounded(0xEEFFFFFF.toInt(), 6)
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) } catch (_: Exception) { } }
        }
        mapFrame.addView(attribution, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        mapCard.addView(mapFrame)
        mapCard.addView(button("Show current location") { locateCurrentLocation() }.apply {
            setTextColor(0xFF175CAD.toInt())
            background = rounded(0xFFE7F0FC.toInt(), 14)
        }, margin(top = 8))
        mapCard.addView(text("Blue shows your last real fix; it pauses while mock mode is active.", 12f, 0xFF60716C.toInt()), margin(top = 8))
        content.addView(mapCard, margin(bottom = 12))

        val locationRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        latitudeInput = edit("Latitude", signedDecimal = true).apply { setTextSize(15f); tag = "latitude-input" }
        longitudeInput = edit("Longitude", signedDecimal = true).apply { setTextSize(15f); tag = "longitude-input" }
        locationRow.addView(latitudeInput, weightParams())
        locationRow.addView(longitudeInput, weightParams(start = 10))
        val coordinateCard = verticalCard()
        coordinateCard.addView(text("SELECTED COORDINATES", 11f, 0xFF60716C.toInt(), bold = true), margin(bottom = 7))
        coordinateCard.addView(locationRow)
        coordinateCard.addView(button("Open in map app") { openMapPreview() }.apply {
            setTextColor(0xFF176B5B.toInt())
            background = rounded(0xFFE6F2EE.toInt(), 14)
        }, margin(top = 7))
        content.addView(coordinateCard, margin(bottom = 12))
        installCoordinateWatchers()

        val homeCard = verticalCard()
        homeCard.addView(text("HOME & SAVED PLACES", 11f, 0xFF60716C.toInt(), bold = true), margin(bottom = 7))
        val homeActions = horizontal()
        homeActions.addView(button("Save as Home") { saveHome() }.apply { setTextColor(0xFF176B5B.toInt()); background = rounded(0xFFE6F2EE.toInt(), 14) }, weightParams())
        homeActions.addView(button("Use Home") { useHome() }.apply { background = rounded(0xFFF0F3F2.toInt(), 14) }, weightParams(start = 8))
        homeCard.addView(homeActions)
        val favoriteRow = horizontal()
        favoritesButton = button("Saved places") { showFavorites() }.apply { background = rounded(0xFFF0F3F2.toInt(), 14) }
        favoriteRow.addView(favoritesButton, weightParams())
        favoriteRow.addView(button("Save current") { addFavorite() }.apply { background = rounded(0xFFF0F3F2.toInt(), 14) }, weightParams(start = 8))
        homeCard.addView(favoriteRow, margin(top = 7))
        content.addView(homeCard, margin(bottom = 12))

        val optionsCard = verticalCard()
        optionsCard.addView(text("FIX ACCURACY", 11f, 0xFF60716C.toInt(), bold = true), margin(bottom = 5))
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
        optionsCard.addView(accuracySpinner)
        content.addView(optionsCard, margin(bottom = 12))

        val setupCard = verticalCard()
        setupCard.addView(text("One-time setup", 16f, 0xFF183A34.toInt(), bold = true))
        setupCard.addView(text("Developer options → Select mock location app → Location Lab", 13f, 0xFF60716C.toInt()), margin(top = 4, bottom = 8))
        setupCard.addView(button("Open Developer options") { openDeveloperOptions() }.apply { background = rounded(0xFFF0F3F2.toInt(), 14) })
        content.addView(setupCard, margin(bottom = 12))

        val controlRow = horizontal()
        controlRow.addView(button("Start mock location") { startSession() }.apply {
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF176B5B.toInt(), 16)
            textSize = 15f
        }, weightParams())
        controlRow.addView(button("Stop") { stopSession() }.apply { background = rounded(0xFFEDE9E8.toInt(), 16) }, weightParams(start = 8))
        content.addView(controlRow, margin(bottom = 12))

        val statusCard = verticalCard()
        statusView = text("Checking status…", 17f, 0xFF172C29.toInt(), bold = true)
        statusDetailView = text("", 14f, 0xFF50625E.toInt())
        statusCard.addView(statusView)
        statusCard.addView(statusDetailView, margin(top = 6))
        statusCard.tag = "status-card"
        statusCard.background = rounded(0xFFE7F2EE.toInt(), 18)
        content.addView(statusCard, margin(bottom = 12))

        content.addView(text("Android labels mock fixes. Other apps may detect them or use network, Wi-Fi, and cell signals.", 12f, 0xFF61716D.toInt()), margin(bottom = 6))
        return scroll
    }

    private fun installCoordinateWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(debouncedMapSync)
                handler.postDelayed(debouncedMapSync, 400L)
            }
        }
        latitudeInput.addTextChangedListener(watcher)
        longitudeInput.addTextChangedListener(watcher)
    }

    private fun colorDot(color: Int) = View(this).apply { background = rounded(color, 50) }

    private fun syncMapToFields() {
        if (!::mapView.isInitialized) return
        val lat = latitudeInput.text.toString().toDoubleOrNull() ?: return
        val lon = longitudeInput.text.toString().toDoubleOrNull() ?: return
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        updateMockPin(GeoPoint(lat, lon), moveCamera = true)
    }

    private fun updateMockPin(point: GeoPoint?, moveCamera: Boolean = false) {
        if (point == null) mockSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        else mockSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
        if (moveCamera && point != null) moveCameraToSelection(point)
    }

    private fun updateCurrentPin(point: GeoPoint) {
        currentSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
        val selected = runCatching { GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString()) }.getOrNull()
        if (selected != null) fitLocations(selected, point)
    }

    private fun moveCameraToSelection(point: GeoPoint) {
        val current = realLocation
        if (current != null && (kotlin.math.abs(current.latitude - point.latitude) > 0.02 || kotlin.math.abs(current.longitude - point.longitude) > 0.02)) {
            fitLocations(point, current)
        } else {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 14.5))
        }
    }

    private fun fitLocations(first: GeoPoint, second: GeoPoint) {
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(first.latitude, first.longitude)).include(LatLng(second.latitude, second.longitude)).build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(52)))
    }

    private fun createMockPin(): Bitmap {
        val size = dp(40)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF087E69.toInt() }
        val path = Path().apply {
            moveTo(size * .5f, size * .96f); cubicTo(size * .42f, size * .73f, size * .12f, size * .48f, size * .12f, size * .32f)
            cubicTo(size * .12f, size * .08f, size * .88f, size * .08f, size * .88f, size * .32f)
            cubicTo(size * .88f, size * .48f, size * .58f, size * .73f, size * .5f, size * .96f); close()
        }
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size * .5f, size * .31f, size * .115f, paint)
        return bitmap
    }

    private fun locateCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingLocate = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_PERMISSIONS)
            return
        }
        val sessionState = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
            .getString(MockLocationService.KEY_STATE, "stopped")
        if (sessionState == "active" || sessionState == "starting") {
            showStatus("Mock session is active", "Stop mock mode before refreshing the blue real-location marker.")
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> try { manager.isProviderEnabled(provider) } catch (_: Exception) { false } }
        if (enabledProviders.isEmpty()) {
            showStatus("Turn on Location", "Enable Android Location services, then tap the blue map button again.")
            return
        }
        stopCurrentLocationUpdates()
        realLocation = null
        showStatus("Finding your location…", "Waiting for a non-mock GPS or network fix.")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                @Suppress("DEPRECATION")
                val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
                if (isMock || !location.latitude.isFinite() || !location.longitude.isFinite()) return
                val point = GeoPoint(location.latitude, location.longitude)
                realLocation = point
                updateCurrentPin(point)
                showStatus("Current location found", "Blue dot · ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}")
                stopCurrentLocationUpdates()
            }
        }
        currentLocationListener = listener
        try {
            enabledProviders.forEach { provider -> manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            handler.removeCallbacks(currentLocationTimeout)
            handler.postDelayed(currentLocationTimeout, CURRENT_LOCATION_TIMEOUT_MS)
        } catch (failure: SecurityException) {
            stopCurrentLocationUpdates()
            showStatus("Location permission is needed", failure.localizedMessage ?: "Allow precise location and try again.")
        } catch (failure: Exception) {
            stopCurrentLocationUpdates()
            showStatus("Could not find location", failure.localizedMessage ?: "Try again with Location enabled.")
        }
    }

    private fun stopCurrentLocationUpdates() {
        handler.removeCallbacks(currentLocationTimeout)
        val listener = currentLocationListener ?: return
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        } catch (_: Exception) { }
        currentLocationListener = null
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

        stopCurrentLocationUpdates()

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
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(Color.WHITE, 19)
        elevation = dp(2).toFloat()
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
        background = rounded(Color.WHITE, 13, 0xFFD8E2DE.toInt())
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(46)
        textSize = 14f
        setTextColor(0xFF24433D.toInt())
        background = rounded(0xFFF0F3F2.toInt(), 14)
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

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

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
        private const val SOURCE_CURRENT = "current-location-source"
        private const val SOURCE_MOCK = "mock-location-source"
        private const val MOCK_ICON = "mock-location-pin"
        private const val CURRENT_LOCATION_TIMEOUT_MS = 20_000L
    }
}
