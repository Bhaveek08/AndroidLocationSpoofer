package dev.codex.locationlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.animation.LayoutTransition
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
    private lateinit var favoritesButton: TextView
    private lateinit var startButton: TextView
    private lateinit var centerButton: TextView
    private lateinit var detailsGroup: LinearLayout
    private lateinit var collapseToggle: TextView
    private lateinit var locationSheet: LinearLayout
    private lateinit var placesStrip: LinearLayout
    private lateinit var mapStatusChip: TextView
    private lateinit var mapAttribution: TextView
    private lateinit var mapZoomControls: LinearLayout
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var currentSource: GeoJsonSource? = null
    private var mockSource: GeoJsonSource? = null
    private var pendingStart = false
    private var pendingLocate = false
    private var resumed = false
    private var realLocation: GeoPoint? = null
    private var currentLocationListener: LocationListener? = null
    private var currentLocationCancellation: android.os.CancellationSignal? = null
    private var locationRequestGeneration = 0
    private var locationLookupActive = false
    private var hasFreshRealFix = false
    private var suppressMapSync = false
    private val debouncedMapSync = Runnable { syncMapToFields() }
    private val currentLocationTimeout = Runnable {
        locationLookupActive = false
        stopCurrentLocationUpdates()
        if (realLocation == null) showStatus("Location not found", "Check that Android Location is on, then try again.")
        else showStatus("Showing last real fix", "Couldn't refresh your location. Tap the target button to retry.")
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
                    .header("User-Agent", "LocationLab/0.3.0 (+https://github.com/Bhaveek08/AndroidLocationSpoofer)")
                    .build())
            }.build()
        )
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        realLocation = loadLastRealLocation()
        setContentView(buildUi())
        refreshStatus()
        locateCurrentLocation()
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
            if (hasLocationPermission()) {
                locateCurrentLocation()
            } else {
                showStatus("Location permission is needed", "Allow location access to center the map on you.")
            }
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFE8F1EF.toInt()); tag = "app-root" }
        mapView = mapView.apply {
            tag = "location-map"
            contentDescription = "Interactive map. Drag to move, pinch or use plus and minus to zoom, tap to choose a mock location."
            getMapAsync { mapLibre ->
                map = mapLibre
                mapLibre.uiSettings.isRotateGesturesEnabled = false
                mapLibre.uiSettings.isTiltGesturesEnabled = false
                mapLibre.uiSettings.isZoomGesturesEnabled = true
                mapLibre.uiSettings.isDoubleTapGesturesEnabled = true
                mapLibre.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                mapLibre.uiSettings.setAttributionMargins(dp(8), 0, 0, dp(320))
                val styleJson = assets.open("map-style.json").bufferedReader().use { it.readText() }
                mapLibre.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    contentDescription = "Interactive OpenStreetMap ready. Tap to choose a mock location."
                    currentSource = GeoJsonSource(SOURCE_CURRENT, FeatureCollection.fromFeatures(emptyArray()))
                    mockSource = GeoJsonSource(SOURCE_MOCK, FeatureCollection.fromFeatures(emptyArray()))
                    style.addSource(currentSource!!)
                    style.addSource(mockSource!!)
                    style.addLayer(CircleLayer("current-location-halo", SOURCE_CURRENT).withProperties(
                        circleRadius(23f), circleColor(0x332F80ED), circleStrokeColor(0x552F80ED), circleStrokeWidth(1f)
                    ))
                    style.addLayer(CircleLayer("current-location-layer", SOURCE_CURRENT).withProperties(
                        circleRadius(9f), circleColor(0xFF2473D4.toInt()), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)
                    ))
                    style.addLayer(CircleLayer("mock-location-halo", SOURCE_MOCK).withProperties(
                        circleRadius(17f), circleColor(0x2230B88E), circleStrokeColor(0x8830B88E.toInt()), circleStrokeWidth(2f)
                    ))
                    style.addImage(MOCK_ICON, createMockPin())
                    style.addLayer(SymbolLayer("mock-location-layer", SOURCE_MOCK).withProperties(
                        iconImage(MOCK_ICON), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)
                    ))
                    if (realLocation == null) mapLibre.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 2.5))
                    mapLibre.addOnMapClickListener { point ->
                        val selected = GeoPoint(point.latitude, point.longitude)
                        suppressMapSync = true
                        latitudeInput.setText(formatCoordinate(selected.latitude))
                        longitudeInput.setText(formatCoordinate(selected.longitude))
                        suppressMapSync = false
                        updateMockPin(selected)
                        mapView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        showStatus("Map point selected", "${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}")
                        true
                    }
                    syncMapToFields()
                    realLocation?.let { updateCurrentPin(it) }
                }
            }
            isClickable = true
        }
        root.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        addressInput = edit("Search a city, address or place").apply {
            tag = "address-input"
            textSize = 15f
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> searchAddress(); true }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(6), dp(5))
            background = rounded(Color.WHITE, 20)
            elevation = dp(5).toFloat()
        }
        searchRow.addView(text("⌕", 25f, 0xFF526660.toInt(), bold = true), LinearLayout.LayoutParams(dp(34), dp(44)).apply { gravity = Gravity.CENTER_VERTICAL })
        searchRow.addView(addressInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        searchRow.addView(actionChip("Find", 0xFF176B5B.toInt(), Color.WHITE) { searchAddress() }, LinearLayout.LayoutParams(dp(64), dp(44)))

        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = text("LOCATION LAB", 13f, Color.WHITE, bold = true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xE612302A.toInt(), 20)
            elevation = dp(4).toFloat()
        }
        mapStatusChip = text("Finding you…", 12f, 0xFF174A41.toInt(), bold = true).apply {
            tag = "location-status"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xF2FFFFFF.toInt(), 20)
            elevation = dp(4).toFloat()
        }
        brandRow.addView(brand)
        brandRow.addView(mapStatusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        val topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(brandRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(searchRow)
        }
        root.addView(topOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })

        mapZoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 18)
            elevation = dp(7).toFloat()
            addView(iconControl("+", "Zoom in", 30f) { map?.animateCamera(CameraUpdateFactory.zoomIn()) }.apply { tag = "zoom-in"; minWidth = dp(50); minHeight = dp(50) })
            val divider = View(this@MainActivity).apply { setBackgroundColor(0xFFE3EAE7.toInt()) }
            addView(divider, LinearLayout.LayoutParams(dp(34), dp(1)))
            addView(iconControl("−", "Zoom out", 28f) { map?.animateCamera(CameraUpdateFactory.zoomOut()) }.apply { tag = "zoom-out"; minWidth = dp(50); minHeight = dp(50) })
        }
        val zoomParams = FrameLayout.LayoutParams(dp(52), dp(104), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(142); marginEnd = dp(16)
        }
        root.addView(mapZoomControls, zoomParams)
        centerButton = iconControl("◎", "Center on my current location", 25f) { locateCurrentLocation() }.apply {
            tag = "show-current-location"
            background = rounded(Color.WHITE, 18)
            elevation = dp(7).toFloat()
        }
        root.addView(centerButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply { topMargin = dp(258); marginEnd = dp(16) })

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            background = roundedTopGradient(26)
            elevation = dp(18).toFloat()
            layoutTransition = LayoutTransition().apply { setDuration(220); enableTransitionType(LayoutTransition.CHANGING) }
            tag = "location-control-sheet"
        }
        locationSheet = sheet
        val handle = View(this).apply { background = rounded(0xFFD4DFDB.toInt(), 20) }
        handle.setOnClickListener { toggleDetails() }
        handle.contentDescription = "Expand or collapse location controls"
        handle.isClickable = true
        handle.isFocusable = true
        sheet.addView(handle, LinearLayout.LayoutParams(dp(44), dp(8)).apply { gravity = Gravity.CENTER; bottomMargin = dp(8) })

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("Set mock location", 19f, 0xFF173A33.toInt(), bold = true))
        titleStack.addView(text("Tap to move the pin · pinch to zoom", 12f, 0xFF687A75.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        titleRow.addView(titleStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        collapseToggle = text("⌃", 23f, 0xFF176B5B.toInt(), bold = true).apply {
            tag = "details-toggle"
            gravity = Gravity.CENTER
            contentDescription = "Expand more location controls"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0xFFEAF3F0.toInt(), 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleDetails() }
        }
        titleRow.addView(collapseToggle, LinearLayout.LayoutParams(dp(48), dp(42)))
        titleRow.isClickable = true
        titleRow.setOnClickListener { toggleDetails() }
        sheet.addView(titleRow)

        val placesHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        placesHeader.addView(text("SAVED PLACES", 10f, 0xFF71817C.toInt(), bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        placesHeader.addView(actionChip("＋ Save", 0xFFEAF3F0.toInt(), 0xFF176B5B.toInt()) { addFavorite() }, LinearLayout.LayoutParams(dp(86), dp(34)))
        sheet.addView(placesHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(11) })

        val placesScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            tag = "saved-places-scroll"
        }
        placesStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "saved-places-strip" }
        placesScroll.addView(placesStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        sheet.addView(placesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(3) })
        refreshSavedPlacesStrip()

        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        legend.addView(colorDot(0xFF2473D4.toInt()), LinearLayout.LayoutParams(dp(9), dp(9)))
        legend.addView(text("Your real fix", 11f, 0xFF4E635C.toInt(), bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        legend.addView(colorDot(0xFF087E69.toInt()), LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(14) })
        legend.addView(text("Target pin", 11f, 0xFF4E635C.toInt(), bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })

        val locationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        latitudeInput = edit("Latitude", signedDecimal = true).apply { setTextSize(14f); tag = "latitude-input"; contentDescription = "Selected latitude" }
        longitudeInput = edit("Longitude", signedDecimal = true).apply { setTextSize(14f); tag = "longitude-input"; contentDescription = "Selected longitude" }
        locationRow.addView(latitudeInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        locationRow.addView(longitudeInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(9) })
        sheet.addView(locationRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        installCoordinateWatchers()

        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shortcuts.addView(actionChip("⌂  Home", 0xFFEAF3F0.toInt(), 0xFF176B5B.toInt()) { useHome() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        favoritesButton = actionChip("♡  Saved", 0xFFEAF3F0.toInt(), 0xFF176B5B.toInt()) { showFavorites() }
        shortcuts.addView(favoritesButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        shortcuts.addView(actionChip("•••  More", 0xFFF0F3F2.toInt(), 0xFF40564F.toInt()) { showMoreOptions() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusView = text("Checking status…", 12f, 0xFF176B5B.toInt(), bold = true).apply { tag = "status-title" }
        statusDetailView = text("", 11f, 0xFF687A75.toInt()).apply { tag = "status-detail"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        statusRow.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        statusRow.addView(statusDetailView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })

        detailsGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(shortcuts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
            addView(statusRow)
            visibility = View.GONE
            tag = "extra-location-controls"
        }
        sheet.addView(detailsGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startButton = actionChip("▶   Start spoofing", 0xFF176B5B.toInt(), Color.WHITE) { toggleSpoofing() }.apply {
            textSize = 15f
            tag = "spoof-toggle"
            contentDescription = "Start spoofing"
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF126557.toInt(), 0xFF1B8A70.toInt())).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(4).toFloat()
            minHeight = dp(58)
        }
        actions.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        sheet.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        mapAttribution = text("© OpenStreetMap", 10f, 0xFF30443E.toInt()).apply {
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = rounded(0xF2FFFFFF.toInt(), 8)
            elevation = dp(4).toFloat()
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) } catch (_: Exception) { } }
        }
        root.addView(mapAttribution, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(12) })
        var bottomSystemInset = 0
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val panelHeight = sheet.height + dp(10)
            (mapAttribution.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.bottomMargin = panelHeight; mapAttribution.layoutParams = lp }
            map?.uiSettings?.setAttributionMargins(dp(8), 0, 0, panelHeight + bottomSystemInset)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            bottomSystemInset = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                topInset = bars.top
                bars.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    topInset = insets.systemWindowInsetTop
                    insets.systemWindowInsetBottom
                }
            }
            (topOverlay.layoutParams as FrameLayout.LayoutParams).also { it.topMargin = topInset + dp(12) }.let { topOverlay.layoutParams = it }
            (mapZoomControls.layoutParams as FrameLayout.LayoutParams).also { it.topMargin = topInset + dp(142) }.let { mapZoomControls.layoutParams = it }
            (centerButton.layoutParams as FrameLayout.LayoutParams).also { it.topMargin = topInset + dp(258) }.let { centerButton.layoutParams = it }
            sheet.setPadding(dp(18), dp(10), dp(18), dp(12) + bottomSystemInset)
            insets
        }
        return root
    }

    private fun showMoreOptions() {
        val options = arrayOf("Save selected point as Home", "Save selected place", "Set mock accuracy", "Developer setup", "Open in map app")
        AlertDialog.Builder(this).setTitle("More options").setItems(options) { _, which ->
            when (which) {
                0 -> saveHome()
                1 -> addFavorite()
                2 -> showAccuracyPicker()
                3 -> openDeveloperOptions()
                4 -> openMapPreview()
            }
        }.show()
    }

    private fun showAccuracyPicker() {
        val labels = arrayOf("Precise · 5 m", "Balanced · 15 m", "Broad · 50 m")
        AlertDialog.Builder(this).setTitle("Mock fix accuracy")
            .setSingleChoiceItems(labels, getPreferences(Context.MODE_PRIVATE).getInt(KEY_ACCURACY_INDEX, 0)) { dialog, which ->
                getPreferences(Context.MODE_PRIVATE).edit().putInt(KEY_ACCURACY_INDEX, which).apply()
                dialog.dismiss()
                showStatus("Accuracy updated", labels[which])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun installCoordinateWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressMapSync) return
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
        if (selected == null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 16.0))
        else if (kotlin.math.abs(selected.latitude - point.latitude) > 0.02 || kotlin.math.abs(selected.longitude - point.longitude) > 0.02) fitLocations(selected, point)
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
        val size = dp(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF087E69.toInt()
            setShadowLayer(dp(2).toFloat(), 0f, dp(2).toFloat(), 0x55000000)
        }
        val path = Path().apply {
            moveTo(size * .5f, size * .96f); cubicTo(size * .42f, size * .73f, size * .12f, size * .48f, size * .12f, size * .32f)
            cubicTo(size * .12f, size * .08f, size * .88f, size * .08f, size * .88f, size * .32f)
            cubicTo(size * .88f, size * .48f, size * .58f, size * .73f, size * .5f, size * .96f); close()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3).toFloat()
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = 0xFF087E69.toInt()
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size * .5f, size * .31f, size * .145f, paint)
        paint.color = 0xFF087E69.toInt()
        canvas.drawCircle(size * .5f, size * .31f, size * .075f, paint)
        return bitmap
    }

    private fun locateCurrentLocation() {
        if (!hasLocationPermission()) {
            pendingLocate = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_PERMISSIONS)
            return
        }
        val sessionState = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
            .getString(MockLocationService.KEY_STATE, "stopped")
        if (sessionState == "active" || sessionState == "starting") {
            locationLookupActive = false
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
        val requestId = ++locationRequestGeneration
        locationLookupActive = true
        showStatus("Finding your location…", "Centering the map on a fresh non-mock fix.")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellation = android.os.CancellationSignal()
                currentLocationCancellation = cancellation
                val mainExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
                enabledProviders.forEach { provider ->
                    manager.getCurrentLocation(provider, cancellation, mainExecutor) { location ->
                        if (requestId == locationRequestGeneration && location != null) acceptRealLocation(location)
                    }
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (requestId == locationRequestGeneration) acceptRealLocation(location)
                    }
                }
                currentLocationListener = listener
                enabledProviders.forEach { provider -> manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            }
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
        locationLookupActive = false
        locationRequestGeneration++
        currentLocationCancellation?.cancel()
        currentLocationCancellation = null
        val listener = currentLocationListener ?: return
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        } catch (_: Exception) { }
        currentLocationListener = null
    }

    private fun acceptRealLocation(location: Location) {
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        if (isMock || !location.latitude.isFinite() || !location.longitude.isFinite()) return
        val point = GeoPoint(location.latitude, location.longitude)
        realLocation = point
        hasFreshRealFix = true
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_REAL_LAT, point.latitude.toString())
            .putString(KEY_LAST_REAL_LON, point.longitude.toString())
            .apply()
        updateCurrentPin(point)
        showStatus("You are here", "Fresh real fix · ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}")
        stopCurrentLocationUpdates()
    }

    private fun loadLastRealLocation(): GeoPoint? {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val latitude = prefs.getString(KEY_LAST_REAL_LAT, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LAST_REAL_LON, null)?.toDoubleOrNull() ?: return null
        return runCatching { GeoPoint.parse(latitude.toString(), longitude.toString()) }.getOrNull()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

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

        val accuracy = when (getPreferences(Context.MODE_PRIVATE).getInt(KEY_ACCURACY_INDEX, 0)) { 1 -> 15f; 2 -> 50f; else -> 5f }
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
        refreshSavedPlacesStrip()
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
        refreshSavedPlacesStrip()
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
                setSpoofButton("active")
                if (::mapStatusChip.isInitialized) mapStatusChip.text = "MOCK ACTIVE"
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
                setSpoofButton("stopped")
                if (::mapStatusChip.isInitialized) mapStatusChip.text = "SETUP NEEDED"
                statusView.setText(R.string.status_mock_location_failed)
                statusDetailView.text = prefs.getString(MockLocationService.KEY_DETAIL, "Check Developer options and permissions.")
                statusView.setTextColor(0xFFB3261E.toInt())
            }
            "starting" -> {
                setSpoofButton("starting")
                if (::mapStatusChip.isInitialized) mapStatusChip.text = "STARTING…"
                showStatus("Starting…", prefs.getString(MockLocationService.KEY_DETAIL, "Setting up providers.") ?: "Setting up providers.")
            }
            else -> {
                setSpoofButton("stopped")
                val ready = isSelectedMockApp()
                if (::mapStatusChip.isInitialized && !locationLookupActive) {
                    mapStatusChip.text = when {
                        hasFreshRealFix -> "REAL FIX READY"
                        realLocation != null -> "LAST REAL FIX"
                        else -> "LOCATION NEEDED"
                    }
                }
                statusView.text = if (ready) "Ready to start" else "Setup needed"
                statusDetailView.text = if (ready) "Choose a location, then tap Start mock location." else "Select Location Lab in Developer options to enable Android mock providers."
                statusView.setTextColor(if (ready) 0xFF176B5B.toInt() else 0xFF705C00.toInt())
            }
        }
        handler.removeCallbacks(statusPoll)
        if (resumed) handler.postDelayed(statusPoll, 1_000L)
    }

    private fun toggleSpoofing() {
        val state = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
            .getString(MockLocationService.KEY_STATE, "stopped")
        if (state == "active" || state == "starting") stopSession() else startSession()
    }

    private fun setSpoofButton(state: String) {
        if (!::startButton.isInitialized) return
        when (state) {
            "active" -> {
                startButton.text = "■   Stop spoofing"
                startButton.contentDescription = "Stop spoofing"
                startButton.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFB8443C.toInt(), 0xFFD95D50.toInt())).apply { cornerRadius = dp(16).toFloat() }
            }
            "starting" -> {
                startButton.text = "◌   Starting…  ·  Tap to stop"
                startButton.contentDescription = "Stop starting mock location"
                startButton.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF866529.toInt(), 0xFFAA8538.toInt())).apply { cornerRadius = dp(16).toFloat() }
            }
            else -> {
                startButton.text = "▶   Start spoofing"
                startButton.contentDescription = "Start spoofing"
                startButton.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF126557.toInt(), 0xFF1B8A70.toInt())).apply { cornerRadius = dp(16).toFloat() }
            }
        }
    }

    private fun showStatus(title: String, detail: String) {
        if (!::statusView.isInitialized) return
        statusView.text = title
        statusView.setTextColor(0xFF172C29.toInt())
        statusDetailView.text = detail
        if (::mapStatusChip.isInitialized) {
            mapStatusChip.text = when (title) {
                "Finding your location…" -> "Locating…"
                "You are here", "Current location found" -> "REAL FIX READY"
                "Showing last real fix" -> "LAST REAL FIX"
                "Location not found", "Turn on Location" -> "LOCATION NEEDED"
                "Map point selected", "Location selected", "Home loaded" -> "TARGET SET"
                "Searching…" -> "SEARCHING…"
                "Starting…" -> "STARTING…"
                else -> mapStatusChip.text
            }
        }
    }

    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun actionChip(label: String, backgroundColor: Int, foregroundColor: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextColor(foregroundColor)
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = rounded(backgroundColor, 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
    }

    private fun iconControl(symbol: String, label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = symbol
        contentDescription = label
        textSize = size
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFF21443C.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
    }

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

    private fun roundedTopGradient(radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.WHITE, 0xFFF2F8F6.toInt())
    ).apply {
        val radius = dp(radiusDp).toFloat()
        setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
    }

    private fun toggleDetails() {
        val expanded = detailsGroup.visibility != View.VISIBLE
        detailsGroup.visibility = if (expanded) View.VISIBLE else View.GONE
        collapseToggle.text = if (expanded) "⌄" else "⌃"
        collapseToggle.contentDescription = if (expanded) "Collapse extra location controls" else "Expand extra location controls"
        locationSheet.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun refreshSavedPlacesStrip() {
        if (!::placesStrip.isInitialized) return
        placesStrip.removeAllViews()
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val items = mutableListOf<Pair<String, SavedPlace>>()
        val homeLat = prefs.getString(KEY_HOME_LAT, null)?.toDoubleOrNull()
        val homeLon = prefs.getString(KEY_HOME_LON, null)?.toDoubleOrNull()
        if (homeLat != null && homeLon != null) {
            val homePoint = runCatching { GeoPoint.parse(homeLat.toString(), homeLon.toString()) }.getOrNull()
            if (homePoint != null) items += "⌂  ${prefs.getString(KEY_HOME_LABEL, "Home") ?: "Home"}" to SavedPlace(prefs.getString(KEY_HOME_LABEL, "Home") ?: "Home", homePoint)
        }
        readFavorites().forEach { items += "●  ${it.name}" to it }
        if (items.isEmpty()) {
            placesStrip.addView(text("Choose a point, then tap + Save", 12f, 0xFF7A8984.toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "No saved places yet. Choose a point on the map, then save it."
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            return
        }
        items.forEach { (label, place) ->
            val chip = TextView(this).apply {
                text = label
                val testableName = place.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                tag = "saved-place-$testableName"
                contentDescription = "Use saved place ${place.name}"
                textSize = 12f
                maxLines = 1
                maxWidth = dp(180)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(0xFF214B42.toInt())
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = rounded(Color.WHITE, 16, 0xFFDCE9E4.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    addressInput.setText(place.name)
                    latitudeInput.setText(formatCoordinate(place.point.latitude))
                    longitudeInput.setText(formatCoordinate(place.point.longitude))
                    showStatus("Location selected", place.name)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
            placesStrip.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) })
        }
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
        private const val KEY_LAST_REAL_LAT = "last_real_lat"
        private const val KEY_LAST_REAL_LON = "last_real_lon"
        private const val MAX_FAVORITES = 20
        private const val SOURCE_CURRENT = "current-location-source"
        private const val SOURCE_MOCK = "mock-location-source"
        private const val MOCK_ICON = "mock-location-pin"
        private const val CURRENT_LOCATION_TIMEOUT_MS = 20_000L
    }
}
