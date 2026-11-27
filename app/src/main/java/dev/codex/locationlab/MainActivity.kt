package dev.codex.locationlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.animation.LayoutTransition
import android.animation.ValueAnimator
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
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.IOException

private object UiPalette {
    val canvas = 0xFF0B1114.toInt()
    val surface = 0xFF141D21.toInt()
    val surfaceRaised = 0xFF1B282D.toInt()
    val ink = 0xFFE8F0F2.toInt()
    val muted = 0xFF9AADB4.toInt()
    val outline = 0xFF2D3D43.toInt()
    val primary = 0xFF087D70.toInt()
    val primaryDeep = 0xFF52D8C2.toInt()
    val primaryBright = 0xFF13A98E.toInt()
    val primaryActionStart = 0xFF087D70.toInt()
    val primaryContainer = 0xFF153A36.toInt()
    val target = 0xFFFF806C.toInt()
    val targetSoft = 0xFF422722.toInt()
    val current = 0xFF69A8FF.toInt()
    val warning = 0xFFE7B45E.toInt()
    val error = 0xFFFF7770.toInt()
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val geocoderExecutor = Executors.newSingleThreadExecutor()
    private val routeClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private lateinit var httpClient: OkHttpClient
    private lateinit var addressInput: AutoCompleteTextView
    private lateinit var mapPickModeButton: TextView
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var statusView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var favoritesButton: TextView
    private lateinit var startButton: TextView
    private lateinit var centerButton: ImageButton
    private lateinit var detailsGroup: LinearLayout
    private lateinit var collapseToggle: ImageButton
    private lateinit var locationSheet: LinearLayout
    private lateinit var placesStrip: LinearLayout
    private lateinit var savedPlacesTitle: TextView
    private lateinit var realLocationLegend: TextView
    private lateinit var mapStatusChip: TextView
    private lateinit var driveHud: LinearLayout
    private lateinit var driveHeadline: TextView
    private lateinit var driveSubline: TextView
    private lateinit var driveProgressFill: View
    private lateinit var driveFollowChip: TextView
    private lateinit var travelButton: TextView
    private lateinit var mapAttribution: TextView
    private lateinit var mapZoomControls: LinearLayout
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var currentSource: GeoJsonSource? = null
    private var mockSource: GeoJsonSource? = null
    private var routeSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null
    private var accuracySource: GeoJsonSource? = null
    private var travelledSource: GeoJsonSource? = null
    private var vehicleSource: GeoJsonSource? = null
    private var followDrive = true
    private var lastDrivePoint: GeoPoint? = null
    private var routeLengthMeters = 0.0
    private var currentRoute: DirectionsRoute? = null
    private var routeDestination: GeoPoint? = null
    private var mapPickMode = MapPickMode.SPOOF
    private var suggestionGeneration = 0
    private var suggestionTask: Runnable? = null
    private var suppressPlaceSuggestions = false
    private var activeRouteCall: Call? = null
    private var routeLookupGeneration = 0
    private var mockPinLayer: SymbolLayer? = null
    private var currentHaloLayer: CircleLayer? = null
    private var pendingStart = false
    private var pendingStartForFreshFix = false
    private var pendingLocate = false
    private var resumed = false
    private var realLocation: GeoPoint? = null
    private var realLocationFixTimeMs = 0L
    private var realLocationAccuracyMeters: Float? = null
    private var currentLocationListener: LocationListener? = null
    private val currentLocationCancellations = mutableListOf<android.os.CancellationSignal>()
    private var locationRequestGeneration = 0
    private var locationLookupActive = false
    private var hasFreshRealFix = false
    private var spoofButtonState = "stopped"
    private var startedWithoutRealFix = false
    private var pendingDrive: DrivePlan? = null
    private var resamplingRealFix = false
    private val startAnywayAfterLookup = Runnable {
        if (!pendingStartForFreshFix) return@Runnable
        pendingStartForFreshFix = false
        stopCurrentLocationUpdates()
        startSession()
    }
    private var suppressMapSync = false
    private val debouncedMapSync = Runnable { syncMapToFields() }
    private val currentLocationTimeout = Runnable {
        locationLookupActive = false
        stopCurrentLocationUpdates()
        if (pendingStartForFreshFix) {
            pendingStartForFreshFix = false
            handler.removeCallbacks(startAnywayAfterLookup)
            showStatus("No real fix yet", "Starting anyway. The blue marker stays empty until you stop and find your location.", chip = "NO REAL FIX")
            handler.post { if (!isFinishing) startSession() }
        } else if (resamplingRealFix) {
            finishRealFixResample("Real location unavailable", "Mock providers are being restored. Nothing was shared.")
        } else if (realLocation == null) showStatus("Location not found", "Check that Android Location is on, then try again.", chip = "LOCATION NEEDED")
        else showStatus("Showing last real fix", "Couldn't refresh. The blue marker is ${realFixAgeLabel()}; stop mock mode before getting a new real fix.", chip = "LAST REAL FIX")
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
        httpClient = routeClient.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "GeoShift/0.9.0 (+https://github.com/Bhaveek08/AndroidLocationSpoofer)")
                    .build())
            }.build()
        HttpRequestUtil.setOkHttpClient(httpClient)
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
        if (pendingStartForFreshFix) locateCurrentLocation()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
        handler.removeCallbacks(statusPoll)
        if (::mapView.isInitialized) mapView.onPause()
        stopCurrentLocationUpdates()
        // A paused session is a window where apps can see the real location. Never leave it open
        // because the user navigated away mid-refresh.
        if (resamplingRealFix) finishRealFixResample("Mock providers restored", "The real-location refresh ended when GeoShift lost focus.")
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        handler.removeCallbacks(debouncedMapSync)
        handler.removeCallbacks(currentLocationTimeout)
        handler.removeCallbacks(startAnywayAfterLookup)
        suggestionTask?.let(handler::removeCallbacks)
        stopCurrentLocationUpdates()
        if (resamplingRealFix) finishRealFixResample("Mock providers restored", "The real-location refresh ended when GeoShift closed.")
        routeClient.dispatcher.cancelAll()
        activeRouteCall = null
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
                showStatus("Location permission is needed", "Allow precise location so the app can verify Android's returned mock fix.", chip = "ALLOW LOCATION")
            }
        }
        else if (requestCode == REQUEST_PERMISSIONS && pendingLocate) {
            pendingLocate = false
            if (hasLocationPermission()) {
                locateCurrentLocation()
            } else {
                showStatus("Location permission is needed", "Allow location access to center the map on you.", chip = "ALLOW LOCATION")
            }
        }
    }

    @Deprecated("Deprecated in Android; document intents are still broadly supported.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT_PLACES -> exportSavedPlaces(uri)
            REQUEST_IMPORT_PLACES -> importSavedPlaces(uri)
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(UiPalette.canvas); tag = "app-root" }
        mapView = mapView.apply {
            tag = "location-map"
            contentDescription = "Interactive map. Drag to move, pinch or use plus and minus to zoom, tap to choose a mock location."
            getMapAsync { mapLibre ->
                map = mapLibre
                mapLibre.uiSettings.isRotateGesturesEnabled = false
                mapLibre.uiSettings.isTiltGesturesEnabled = false
                mapLibre.uiSettings.isZoomGesturesEnabled = true
                mapLibre.uiSettings.isDoubleTapGesturesEnabled = true
                mapLibre.uiSettings.isQuickZoomGesturesEnabled = true
                mapLibre.uiSettings.isFlingVelocityAnimationEnabled = true
                mapLibre.uiSettings.isScaleVelocityAnimationEnabled = true
                mapLibre.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                mapLibre.uiSettings.setAttributionMargins(dp(8), 0, 0, dp(320))
                mapLibre.setStyle(Style.Builder().fromUri(DARK_MAP_STYLE_URL)) { style ->
                    contentDescription = "Interactive dark map ready. Tap to choose a mock location."
                    val empty = { FeatureCollection.fromFeatures(emptyArray()) }
                    currentSource = GeoJsonSource(SOURCE_CURRENT, empty())
                    mockSource = GeoJsonSource(SOURCE_MOCK, empty())
                    routeSource = GeoJsonSource(SOURCE_ROUTE, empty())
                    destinationSource = GeoJsonSource(SOURCE_DESTINATION, empty())
                    accuracySource = GeoJsonSource(SOURCE_ACCURACY, empty())
                    travelledSource = GeoJsonSource(SOURCE_TRAVELLED, empty())
                    vehicleSource = GeoJsonSource(SOURCE_VEHICLE, empty())
                    listOf(accuracySource, routeSource, travelledSource, destinationSource, currentSource, mockSource, vehicleSource)
                        .forEach { source -> source?.let(style::addSource) }
                    // Accuracy first so every marker draws on top of it.
                    style.addLayer(FillLayer("real-accuracy-fill", SOURCE_ACCURACY).withProperties(
                        fillColor(0x1E69A8FF), fillOutlineColor(0x5569A8FF)
                    ))
                    // A dark casing under the route reads cleanly over both land and water.
                    style.addLayer(LineLayer("directions-route-casing", SOURCE_ROUTE).withProperties(
                        lineColor("#05231F"), lineWidth(9f), lineOpacity(0.85f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                    ))
                    style.addLayer(LineLayer("directions-route-layer", SOURCE_ROUTE).withProperties(
                        lineColor("#59D8B9"), lineWidth(5.5f), lineOpacity(0.96f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                    ))
                    style.addLayer(LineLayer("directions-route-travelled", SOURCE_TRAVELLED).withProperties(
                        lineColor("#3A5F5A"), lineWidth(5.5f), lineOpacity(0.95f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                    ))
                    currentHaloLayer = CircleLayer("current-location-halo", SOURCE_CURRENT).withProperties(
                        circleRadius(22f), circleColor(0x333478E5), circleStrokeColor(0x553478E5), circleStrokeWidth(1f)
                    ).also(style::addLayer)
                    style.addLayer(CircleLayer("current-location-layer", SOURCE_CURRENT).withProperties(
                        circleRadius(9f), circleColor(UiPalette.current), circleStrokeColor(UiPalette.canvas), circleStrokeWidth(3f)
                    ))
                    style.addLayer(CircleLayer("mock-location-halo", SOURCE_MOCK).withProperties(
                        circleRadius(18f), circleColor(0x33E86F56), circleStrokeColor(0x77E86F56), circleStrokeWidth(2f)
                    ))
                    style.addLayer(CircleLayer("route-destination-halo", SOURCE_DESTINATION).withProperties(
                        circleRadius(17f), circleColor(0x33FFD166.toInt()), circleStrokeColor(0xCCFFD166.toInt()), circleStrokeWidth(2f)
                    ))
                    style.addLayer(CircleLayer("route-destination-pin", SOURCE_DESTINATION).withProperties(
                        circleRadius(8f), circleColor(0xFFFFD166.toInt()), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)
                    ))
                    style.addImage(MOCK_ICON, createMockPin())
                    style.addImage(VEHICLE_ICON, createHeadingMarker())
                    mockPinLayer = SymbolLayer("mock-location-layer", SOURCE_MOCK).withProperties(
                        iconImage(MOCK_ICON), iconSize(1f), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true)
                    ).also(style::addLayer)
                    // The heading marker is driven by a per-feature bearing so it turns with the route.
                    style.addLayer(SymbolLayer("drive-heading-layer", SOURCE_VEHICLE).withProperties(
                        iconImage(VEHICLE_ICON), iconSize(1f), iconAllowOverlap(true), iconIgnorePlacement(true),
                        iconRotate(org.maplibre.android.style.expressions.Expression.get(FEATURE_BEARING)),
                        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
                    ))
                    realLocation?.let { point ->
                        currentSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
                        updateAccuracyCircle(point, realLocationAccuracyMeters)
                        mapLibre.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 15.5))
                    } ?: mapLibre.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 2.5))
                    runCatching { GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString()) }.getOrNull()?.let { point ->
                        mockSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
                    }
                    routeDestination?.let(::updateDestinationPin)
                    mapLibre.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && followDrive) setFollowDrive(false)
                    }
                    mapLibre.addOnMapClickListener { point ->
                        val selected = GeoPoint(point.latitude, point.longitude)
                        mapView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (mapPickMode == MapPickMode.DESTINATION) {
                            setRouteDestination(selected, "Map pin")
                        } else {
                            selectSpoofPoint(selected)
                            showStatus("Spoof point selected", "${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}", chip = "TARGET SET")
                        }
                        true
                    }
                    syncMapToFields()
                    realLocation?.let { updateCurrentPin(it) }
                    currentRoute?.let(::renderRouteOnMap)
                }
            }
            isClickable = true
        }
        root.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        addressInput = AutoCompleteTextView(this).apply {
            tag = "address-input"
            textSize = 15f
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            threshold = 2
            setSingleLine(true)
            setTextColor(UiPalette.ink)
            setHintTextColor(UiPalette.muted)
            setAdapter(ArrayAdapter<PlaceSuggestion>(this@MainActivity, android.R.layout.simple_dropdown_item_1line, mutableListOf()))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!suppressPlaceSuggestions) schedulePlaceSuggestions(s?.toString().orEmpty(), this@apply) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, _, _ -> searchAddress(); true }
            setOnItemClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? PlaceSuggestion)?.let(::selectSearchSuggestion)
            }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(6), dp(5))
            background = rounded(UiPalette.surface, 20, UiPalette.outline)
            elevation = dp(5).toFloat()
        }
        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(UiPalette.muted)
            contentDescription = null
        }
        searchRow.addView(searchIcon, LinearLayout.LayoutParams(dp(34), dp(48)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(4) })
        searchRow.addView(addressInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchRow.addView(actionChip("Search", UiPalette.primary, Color.WHITE) { searchAddress() }, LinearLayout.LayoutParams(dp(78), dp(48)))
        searchRow.addView(actionChip("Route", UiPalette.surfaceRaised, UiPalette.ink, iconResource = R.drawable.ic_directions) {
            showDirectionsDialog()
        }.apply { tag = "directions-button" }, LinearLayout.LayoutParams(dp(82), dp(48)).apply { marginStart = dp(6) })

        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = text("GEOSHIFT", 12f, Color.WHITE, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xF212363A.toInt(), 20)
            elevation = dp(4).toFloat()
        }
        mapStatusChip = text("Finding you…", 11f, UiPalette.ink, bold = true).apply {
            tag = "location-status"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xF21B282D.toInt(), 20, UiPalette.outline)
            elevation = dp(4).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Location status. Tap to open details."
            setOnClickListener {
                if (::detailsGroup.isInitialized && detailsGroup.visibility != View.VISIBLE) toggleDetails()
            }
        }
        brandRow.addView(brand)
        brandRow.addView(mapStatusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        val topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(brandRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(searchRow)
            mapPickModeButton = actionChip("Tap: spoof", UiPalette.surfaceRaised, UiPalette.primaryDeep, onClick = {
                setMapPickMode(if (mapPickMode == MapPickMode.SPOOF) MapPickMode.DESTINATION else MapPickMode.SPOOF)
            }).apply { tag = "map-pick-mode"; contentDescription = "Toggle map taps between spoof target and directions destination" }
            addView(mapPickModeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { topMargin = dp(8) })
        }
        root.addView(topOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })

        mapZoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(UiPalette.surface, 18, UiPalette.outline)
            elevation = dp(7).toFloat()
            addView(iconControl(R.drawable.ic_add, "Zoom in") { map?.animateCamera(CameraUpdateFactory.zoomIn(), 280) }.apply { tag = "zoom-in"; setMinimumWidth(dp(50)); setMinimumHeight(dp(50)) })
            val divider = View(this@MainActivity).apply { setBackgroundColor(UiPalette.outline) }
            addView(divider, LinearLayout.LayoutParams(dp(34), dp(1)))
            addView(iconControl(R.drawable.ic_remove, "Zoom out") { map?.animateCamera(CameraUpdateFactory.zoomOut(), 280) }.apply { tag = "zoom-out"; setMinimumWidth(dp(50)); setMinimumHeight(dp(50)) })
        }
        val zoomParams = FrameLayout.LayoutParams(dp(52), dp(104), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(142); marginEnd = dp(16)
        }
        root.addView(mapZoomControls, zoomParams)
        centerButton = iconControl(R.drawable.ic_my_location, "Center on my current location") { locateCurrentLocation() }.apply {
            tag = "show-current-location"
            background = rippleSurface(rounded(UiPalette.surface, 18, UiPalette.outline), 18)
            elevation = dp(7).toFloat()
        }
        root.addView(centerButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply { topMargin = dp(258); marginEnd = dp(16) })

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
            background = roundedTopGradient(28)
            foreground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setCornerRadii(floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f))
                setStroke(dp(1), UiPalette.outline)
            }
            elevation = dp(18).toFloat()
            layoutTransition = LayoutTransition().apply { setDuration(220); enableTransitionType(LayoutTransition.CHANGING) }
            tag = "location-control-sheet"
        }
        locationSheet = sheet
        val handle = FrameLayout(this).apply {
            contentDescription = "Swipe up or tap to expand controls"
            isClickable = true
            isFocusable = true
            addView(View(this@MainActivity).apply { background = rounded(0xFF52666D.toInt(), 20) }, FrameLayout.LayoutParams(dp(42), dp(5), Gravity.CENTER))
        }
        handle.setOnClickListener { toggleDetails() }
        var handleDownY = 0f
        var handleDragged = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { handleDownY = event.rawY; handleDragged = false; true }
                android.view.MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(event.rawY - handleDownY) > dp(10)) handleDragged = true; true }
                android.view.MotionEvent.ACTION_UP -> {
                    val delta = event.rawY - handleDownY
                    if (handleDragged && kotlin.math.abs(delta) > dp(24)) {
                        val expanded = detailsGroup.visibility == View.VISIBLE
                        if ((delta < 0 && !expanded) || (delta > 0 && expanded)) view.performClick()
                    } else view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        sheet.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(1) })

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("Choose a place", 19f, UiPalette.ink, bold = true))
        titleStack.addView(text("Tap anywhere · pinch to explore", 12f, UiPalette.muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        titleRow.addView(titleStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        collapseToggle = ImageButton(this).apply {
            tag = "details-toggle"
            setImageResource(R.drawable.ic_expand_less)
            imageTintList = ColorStateList.valueOf(UiPalette.primary)
            contentDescription = "Expand more location controls"
            background = rippleSurface(rounded(UiPalette.primaryContainer, 16), 16)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { toggleDetails() }
        }
        titleRow.addView(collapseToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleRow.isClickable = true
        titleRow.setOnClickListener { toggleDetails() }
        sheet.addView(titleRow)

        val driveHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        driveHeader.addView(
            text("TRAVELLING", 10f, UiPalette.primaryDeep, bold = true).apply { letterSpacing = 0.1f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        driveFollowChip = actionChip("Following", UiPalette.primaryContainer, UiPalette.primaryDeep, iconResource = R.drawable.ic_my_location) {
            setFollowDrive(!followDrive)
        }.apply { tag = "drive-follow"; textSize = 11f; minHeight = dp(36) }
        driveHeader.addView(driveFollowChip, LinearLayout.LayoutParams(dp(116), dp(36)))
        driveHeadline = text("—", 20f, UiPalette.ink, bold = true).apply { tag = "drive-headline" }
        driveSubline = text("", 11f, UiPalette.muted)
        val progressTrack = FrameLayout(this).apply {
            background = rounded(UiPalette.outline, 4)
            addView(View(this@MainActivity).apply {
                background = rounded(UiPalette.primaryDeep, 4)
                tag = "drive-progress"
            }.also { driveProgressFill = it }, FrameLayout.LayoutParams(0, dp(6)))
        }
        driveHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "drive-hud"
            visibility = View.GONE
            setPadding(dp(14), dp(11), dp(14), dp(12))
            background = rounded(0xFF11282B.toInt(), 20, UiPalette.primaryContainer)
            addView(driveHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(driveHeadline, margin(top = 4))
            addView(progressTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(9) })
            addView(driveSubline, margin(top = 7))
        }
        sheet.addView(driveHud, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(11) })

        val placesHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        savedPlacesTitle = text("SAVED PLACES", 10f, UiPalette.muted, bold = true).apply { letterSpacing = 0.08f }
        placesHeader.addView(savedPlacesTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        placesHeader.addView(actionChip("Save place", UiPalette.primaryContainer, UiPalette.primaryDeep, onClick = { addFavorite() }, iconResource = R.drawable.ic_bookmark), LinearLayout.LayoutParams(dp(124), dp(48)))
        sheet.addView(placesHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(11) })

        val placesScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            tag = "saved-places-scroll"
        }
        placesStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "saved-places-strip" }
        placesScroll.addView(placesStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(58)))
        sheet.addView(placesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(3) })
        refreshSavedPlacesStrip()

        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        legend.addView(colorDot(UiPalette.current), LinearLayout.LayoutParams(dp(9), dp(9)))
        realLocationLegend = text("Real · waiting", 11f, UiPalette.muted, bold = true).apply { tag = "real-location-age" }
        legend.addView(realLocationLegend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        updateRealLocationLegend()
        legend.addView(colorDot(UiPalette.target), LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(14) })
        legend.addView(text("Mock target", 11f, UiPalette.muted, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })

        val locationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        latitudeInput = edit("Latitude", signedDecimal = true).apply { setTextSize(14f); tag = "latitude-input"; contentDescription = "Selected latitude" }
        longitudeInput = edit("Longitude", signedDecimal = true).apply { setTextSize(14f); tag = "longitude-input"; contentDescription = "Selected longitude" }
        locationRow.addView(latitudeInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        locationRow.addView(longitudeInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(9) })
        sheet.addView(locationRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        installCoordinateWatchers()

        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shortcuts.addView(actionChip("Home", UiPalette.primaryContainer, UiPalette.primaryDeep, onClick = { useHome() }, iconResource = R.drawable.ic_home), LinearLayout.LayoutParams(0, dp(48), 1f))
        favoritesButton = actionChip("Saved", UiPalette.primaryContainer, UiPalette.primaryDeep, onClick = { showFavorites() }, iconResource = R.drawable.ic_bookmark)
        shortcuts.addView(favoritesButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        shortcuts.addView(actionChip("More", UiPalette.surfaceRaised, UiPalette.ink, onClick = { showMoreOptions() }, iconResource = R.drawable.ic_more), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusView = text("Checking status…", 12f, UiPalette.primaryDeep, bold = true).apply { tag = "status-title" }
        statusDetailView = text("", 11f, UiPalette.muted).apply { tag = "status-detail"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
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

        travelButton = actionChip("Travel to destination", UiPalette.primaryContainer, UiPalette.primaryDeep, iconResource = R.drawable.ic_directions) {
            currentRoute?.let(::offerDriveRoute) ?: showDirectionsDialog()
        }.apply {
            tag = "travel-route"
            textSize = 14f
            visibility = View.GONE
            minHeight = dp(50)
        }
        sheet.addView(travelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        startButton = actionChip("Start spoofing", UiPalette.primary, Color.WHITE) { toggleSpoofing() }.apply {
            textSize = 15f
            tag = "spoof-toggle"
            contentDescription = "Start spoofing"
            background = buttonGradient(UiPalette.primaryActionStart, UiPalette.primaryBright)
            elevation = dp(4).toFloat()
            minHeight = dp(58)
        }
        setButtonIcon(R.drawable.ic_play)
        actions.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        sheet.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        // In landscape the expanded sheet is taller than the screen; a scroll container keeps the
        // Start action reachable instead of clipping it off the bottom.
        val sheetScroll = ScrollView(this).apply {
            tag = "location-control-scroll"
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(sheetScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        mapAttribution = text("OpenFreeMap · OpenMapTiles · OSM", 10f, UiPalette.ink).apply {
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = rounded(0xF21B282D.toInt(), 8, UiPalette.outline)
            elevation = dp(4).toFloat()
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openfreemap.org/"))) } catch (_: Exception) { } }
        }
        root.addView(mapAttribution, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(12) })
        var bottomSystemInset = 0
        sheetScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val panelHeight = sheetScroll.height + dp(10)
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
            sheet.setPadding(dp(20), dp(10), dp(20), dp(12) + bottomSystemInset)
            insets
        }
        return root
    }

    private fun showMoreOptions() {
        val options = buildList<Pair<String, () -> Unit>> {
            currentRoute?.let { route -> add("Travel the planned route" to { offerDriveRoute(route) }) }
            add("Save selected point as Home" to ::saveHome)
            add("Save selected place" to ::addFavorite)
            add("Set mock accuracy" to ::showAccuracyPicker)
            add("Privacy checkup" to ::showPrivacyCheckup)
            add("Developer setup" to ::openDeveloperOptions)
            add("Location accuracy settings" to ::openLocationSettings)
            add("Open in map app" to ::openMapPreview)
            add("Export saved places" to {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "GeoShift-saved-places.json")
                }, REQUEST_EXPORT_PLACES)
            })
            add("Import saved places" to {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }, REQUEST_IMPORT_PLACES)
            })
        }
        AlertDialog.Builder(this).setTitle("More options")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun showPrivacyCheckup() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) locationManager.isLocationEnabled else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        val precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val approximate = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val mockEnabled = isSelectedMockApp()
        val session = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        val spoofActive = session.getString(MockLocationService.KEY_STATE, MockLocationService.STATE_STOPPED) == MockLocationService.STATE_ACTIVE
        val checks = listOf(
            "GeoShift permission: ${if (precise) "precise" else if (approximate) "approximate" else "not granted"}",
            "Android Location: ${if (locationEnabled) "on" else "off"}",
            "GeoShift selected for mock location: ${if (mockEnabled) "yes" else "no"}",
            "Mock session: ${if (spoofActive) "active" else "stopped"}",
            "Real-location marker: ${if (realLocation == null) "unavailable" else "saved fix · ${realFixAgeLabel()}"}"
        )
        val message = buildString {
            checks.forEach { append("• ").append(it).append('\n') }
            append("\nGeoShift replaces Android's gps, network, and fused fixes only. Apps can still detect a mock fix, or place you from Wi‑Fi, cell, IP, cached, or account signals. If an app keeps showing your real area, turn off Google Location Accuracy and Wi‑Fi/Bluetooth scanning in Android Location settings. This checkup cannot verify or falsify those sources.")
        }
        val settingsActions = arrayOf(
            "Android Location settings",
            "Installed app settings",
            "GeoShift app permissions",
            "Google Account location controls",
            "Forget the saved real location"
        )
        AlertDialog.Builder(this)
            .setTitle("Privacy checkup")
            .setMessage(message)
            .setNeutralButton("Open settings…") { _, _ -> showPrivacySettingsShortcuts(settingsActions) }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showPrivacySettingsShortcuts(settingsActions: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle("Location settings")
            .setItems(settingsActions) { _, which ->
                when (which) {
                    0 -> openLocationSettings()
                    1 -> openInstalledAppSettings()
                    2 -> openAppSettings()
                    3 -> openGoogleAccountLocationControls()
                    4 -> forgetSavedRealLocation()
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun forgetSavedRealLocation() {
        AlertDialog.Builder(this)
            .setTitle("Forget the saved real location?")
            .setMessage("GeoShift will delete the cached real fix from this device. The blue marker disappears and route starts from “${ORIGIN_MY_LOCATION}” stop working until you find your location again.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Forget") { _, _ ->
                getPreferences(Context.MODE_PRIVATE).edit()
                    .remove(KEY_LAST_REAL_LAT).remove(KEY_LAST_REAL_LON)
                    .remove(KEY_LAST_REAL_TIME).remove(KEY_LAST_REAL_ACCURACY)
                    .apply()
                realLocation = null
                realLocationFixTimeMs = 0L
                realLocationAccuracyMeters = null
                hasFreshRealFix = false
                currentSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                updateAccuracyCircle(null, null)
                updateRealLocationLegend()
                showStatus("Saved real location deleted", "Nothing about your real position is stored on this device now.")
            }
            .show()
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .onFailure { showStatus("Settings unavailable", "Open Android Settings and search for Location.") }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { showStatus("Settings unavailable", "Open GeoShift's App info page in Android Settings.") }
    }

    private fun openInstalledAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
            .onFailure { showStatus("Settings unavailable", "Open Android Settings, then review Location access under Apps.") }
    }

    private fun openGoogleAccountLocationControls() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/data-and-privacy"))) }
            .onFailure { showStatus("Google settings unavailable", "Open Google Account settings and review Location History and Location Sharing.") }
    }

    private fun exportSavedPlaces(uri: Uri) {
        runCatching {
            val prefs = getPreferences(Context.MODE_PRIVATE)
            val home = runCatching {
                val lat = prefs.getString(KEY_HOME_LAT, null) ?: return@runCatching null
                val lon = prefs.getString(KEY_HOME_LON, null) ?: return@runCatching null
                val point = GeoPoint.parse(lat, lon)
                BackupPlace(prefs.getString(KEY_HOME_LABEL, "Home") ?: "Home", point.latitude, point.longitude)
            }.getOrNull()
            val favorites = readFavorites().map { BackupPlace(it.name, it.point.latitude, it.point.longitude) }
            val json = SavedPlacesBackupCodec.encode(SavedPlacesBackup(home, favorites))
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("Could not open the selected file for writing.")
        }.onSuccess {
            Toast.makeText(this, "Saved places exported. Your real-location history was not included.", Toast.LENGTH_LONG).show()
        }.onFailure { failure ->
            Toast.makeText(this, failure.message ?: "Could not export saved places.", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSavedPlaces(uri: Uri) {
        val backup = runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 128_000) { "This backup file is too large." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } ?: error("Could not open the selected backup file.")
            SavedPlacesBackupCodec.decode(String(bytes, Charsets.UTF_8))
        }.getOrElse { failure ->
            Toast.makeText(this, failure.message ?: "Could not read saved places.", Toast.LENGTH_LONG).show()
            return
        }
        val homeText = backup.home?.name ?: "No Home place"
        AlertDialog.Builder(this)
            .setTitle("Restore saved places?")
            .setMessage("Replace your current Home and saved places with $homeText and ${backup.favorites.size} saved place(s)? The backup does not contain your real-location history.")
            .setPositiveButton("Restore") { _, _ ->
                val prefs = getPreferences(Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (backup.home == null) {
                    editor.remove(KEY_HOME_LAT).remove(KEY_HOME_LON).remove(KEY_HOME_LABEL)
                } else {
                    editor.putString(KEY_HOME_LAT, backup.home.latitude.toString())
                        .putString(KEY_HOME_LON, backup.home.longitude.toString())
                        .putString(KEY_HOME_LABEL, backup.home.name)
                }
                val favorites = JSONArray().apply {
                    backup.favorites.forEach { place ->
                        put(JSONObject().put("name", place.name).put("lat", place.latitude).put("lon", place.longitude))
                    }
                }
                editor.putString(KEY_FAVORITES, favorites.toString()).apply()
                refreshSavedPlacesStrip()
                Toast.makeText(this, "Saved places restored.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    /**
     * Geocoding runs on an executor that onDestroy shuts down. A debounced suggestion task can fire
     * after that, and submitting to a terminated executor throws on the main thread, so every
     * submission goes through here.
     */
    private fun runGeocoder(block: () -> Unit) {
        if (isFinishing || geocoderExecutor.isShutdown) return
        try {
            geocoderExecutor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The activity was destroyed between the check and the submission.
        }
    }

    private fun colorDot(color: Int) = View(this).apply { background = rounded(color, 50) }

    private fun syncMapToFields() {
        if (!::mapView.isInitialized) return
        val lat = latitudeInput.text.toString().toDoubleOrNull() ?: return
        val lon = longitudeInput.text.toString().toDoubleOrNull() ?: return
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        updateMockPin(GeoPoint(lat, lon), moveCamera = true)
        refreshSavedPlacesStrip()
    }

    private fun updateMockPin(point: GeoPoint?, moveCamera: Boolean = false, animate: Boolean = true) {
        if (point == null) mockSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        else {
            mockSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
            if (animate) popTargetPin()
        }
        if (moveCamera && point != null) moveCameraToSelection(point)
    }

    private fun popTargetPin() {
        val layer = mockPinLayer ?: return
        if (!motionAllowed()) {
            layer.setProperties(iconSize(1f))
            return
        }
        ValueAnimator.ofFloat(0.72f, 1f).apply {
            duration = 300L
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { layer.setProperties(iconSize(it.animatedValue as Float)) }
            start()
        }
    }

    private fun pulseCurrentLocation() {
        val layer = currentHaloLayer ?: return
        if (!motionAllowed()) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                if (progress >= .999f) layer.setProperties(circleRadius(22f), circleOpacity(.2f))
                else layer.setProperties(circleRadius(15f + progress * 14f), circleOpacity(.42f * (1f - progress)))
            }
            start()
        }
    }

    private fun motionAllowed() = android.provider.Settings.Global.getFloat(
        contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) > 0f

    private fun updateCurrentPin(point: GeoPoint) {
        currentSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
        updateAccuracyCircle(point, realLocationAccuracyMeters)
        pulseCurrentLocation()
        val selected = selectedSpoofPoint()
        if (selected == null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 16.0), 480)
        else if (distanceMeters(selected, point) > 120.0) fitLocations(selected, point)
    }

    private fun moveCameraToSelection(point: GeoPoint) {
        val current = realLocation
        if (current != null && distanceMeters(point, current) > 120.0) {
            fitLocations(point, current)
        } else {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 14.5), 480)
        }
    }

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val earthRadiusMeters = 6_371_000.0
        val firstLat = Math.toRadians(first.latitude)
        val secondLat = Math.toRadians(second.latitude)
        val latitudeDelta = secondLat - firstLat
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val haversine = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
            kotlin.math.cos(firstLat) * kotlin.math.cos(secondLat) *
            kotlin.math.sin(longitudeDelta / 2).let { it * it }
        return 2 * earthRadiusMeters * kotlin.math.asin(kotlin.math.sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private fun fitLocations(first: GeoPoint, second: GeoPoint) {
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(first.latitude, first.longitude)).include(LatLng(second.latitude, second.longitude)).build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(52)), 520)
    }

    private fun createMockPin(): Bitmap {
        val size = dp(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UiPalette.target
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
        paint.color = UiPalette.target
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size * .5f, size * .31f, size * .145f, paint)
        paint.color = UiPalette.target
        canvas.drawCircle(size * .5f, size * .31f, size * .075f, paint)
        return bitmap
    }

    /** A triangular heading marker, rotated per fix, used while a route is being travelled. */
    private fun createHeadingMarker(): Bitmap {
        val size = dp(40)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x33000000
        canvas.drawCircle(size / 2f, size / 2f, size * .46f, paint)
        paint.color = UiPalette.canvas
        canvas.drawCircle(size / 2f, size / 2f, size * .40f, paint)
        val arrow = Path().apply {
            moveTo(size * .5f, size * .14f)
            lineTo(size * .80f, size * .84f)
            lineTo(size * .5f, size * .66f)
            lineTo(size * .20f, size * .84f)
            close()
        }
        paint.color = UiPalette.target
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.WHITE
        canvas.drawPath(arrow, paint)
        return bitmap
    }

    /**
     * MapLibre circle radii are in screen pixels, so a true accuracy ring has to be a polygon in
     * real coordinates; otherwise it would stay the same size as the user zooms.
     */
    private fun metreCircle(center: GeoPoint, radiusMeters: Double): Feature {
        val steps = 48
        val latitudeRadius = radiusMeters / 111_320.0
        val longitudeRadius = radiusMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
        val ring = (0..steps).map { step ->
            val angle = 2.0 * Math.PI * step / steps
            Point.fromLngLat(
                center.longitude + longitudeRadius * kotlin.math.cos(angle),
                (center.latitude + latitudeRadius * kotlin.math.sin(angle)).coerceIn(-90.0, 90.0)
            )
        }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }

    private fun updateAccuracyCircle(point: GeoPoint?, accuracyMeters: Float?) {
        val source = accuracySource ?: return
        val radius = accuracyMeters?.toDouble()?.takeIf { it > 1.0 }
        if (point == null || radius == null) source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        else source.setGeoJson(metreCircle(point, radius))
    }

    private fun updateHeadingMarker(point: GeoPoint?, bearingDegrees: Float) {
        val source = vehicleSource ?: return
        if (point == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
            addNumberProperty(FEATURE_BEARING, bearingDegrees)
        })
    }

    /** Dims the part of the route already covered, so progress is readable at a glance. */
    private fun updateTravelledRoute(remainingMeters: Double) {
        val source = travelledSource ?: return
        val points = currentRoute?.points
        if (points == null || points.size < 2 || remainingMeters < 0.0) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        var budget = (routeLengthMeters - remainingMeters).coerceAtLeast(0.0)
        val travelled = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val leg = RouteSimulator.metersBetween(points[index - 1], points[index])
            if (leg <= budget) {
                travelled += points[index]
                budget -= leg
            } else {
                val progress = if (leg <= 0.0) 0.0 else budget / leg
                travelled += GeoPoint(
                    points[index - 1].latitude + (points[index].latitude - points[index - 1].latitude) * progress,
                    points[index - 1].longitude + (points[index].longitude - points[index - 1].longitude) * progress
                )
                break
            }
        }
        if (travelled.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(
            travelled.map { Point.fromLngLat(it.longitude, it.latitude) }
        )))
    }

    private fun locateCurrentLocation() {
        if (!hasLocationPermission()) {
            pendingLocate = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_PERMISSIONS)
            return
        }
        val sessionState = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
            .getString(MockLocationService.KEY_STATE, MockLocationService.STATE_STOPPED)
        if (!resamplingRealFix && (sessionState == MockLocationService.STATE_ACTIVE || sessionState == MockLocationService.STATE_STARTING)) {
            locationLookupActive = false
            offerRealFixResample()
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> try { manager.isProviderEnabled(provider) } catch (_: Exception) { false } }
        if (enabledProviders.isEmpty()) {
            showStatus("Turn on Location", "Enable Android Location services, then tap the blue map button again.", chip = "LOCATION NEEDED")
            return
        }
        if (realLocation == null) {
            val canReadLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val lastKnownRealFix = if (canReadLocation) enabledProviders.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.filter { location ->
                val ageMs = System.currentTimeMillis() - location.time
                @Suppress("DEPRECATION")
                val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
                !isMock && ageMs in 0..LAST_KNOWN_FIX_MAX_AGE_MS &&
                    location.latitude.isFinite() && location.longitude.isFinite()
            }.maxByOrNull { it.time } else null
            lastKnownRealFix?.let(::acceptRealLocation)
        }
        stopCurrentLocationUpdates()
        val requestId = ++locationRequestGeneration
        locationLookupActive = true
        if (pendingStartForFreshFix) {
            showStatus("Finding your real location…", "Saving a fresh non-mock fix before mock mode starts.", chip = "SAVING REAL FIX…")
        } else {
            showStatus("Finding your location…", "Centering the map on a fresh non-mock fix.", chip = "LOCATING…")
        }
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (requestId == locationRequestGeneration) acceptRealLocation(location)
                }
            }
            currentLocationListener = listener
            enabledProviders.forEach { provider ->
                runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mainExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
                enabledProviders.forEach { provider ->
                    val cancellation = android.os.CancellationSignal()
                    currentLocationCancellations += cancellation
                    manager.getCurrentLocation(provider, cancellation, mainExecutor) { location ->
                        if (requestId == locationRequestGeneration && location != null) acceptRealLocation(location)
                    }
                }
            }
            handler.removeCallbacks(currentLocationTimeout)
            handler.postDelayed(currentLocationTimeout, CURRENT_LOCATION_TIMEOUT_MS)
        } catch (failure: SecurityException) {
            stopCurrentLocationUpdates()
            showStatus("Location permission is needed", failure.localizedMessage ?: "Allow precise location and try again.", chip = "ALLOW LOCATION")
        } catch (failure: Exception) {
            stopCurrentLocationUpdates()
            showStatus("Could not find location", failure.localizedMessage ?: "Try again with Location enabled.", chip = "LOCATION NEEDED")
        }
    }

    private fun stopCurrentLocationUpdates() {
        handler.removeCallbacks(currentLocationTimeout)
        locationLookupActive = false
        locationRequestGeneration++
        currentLocationCancellations.forEach { cancellation -> runCatching { cancellation.cancel() } }
        currentLocationCancellations.clear()
        currentLocationListener?.let { listener ->
            try {
                (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
            } catch (_: Exception) { }
        }
        currentLocationListener = null
    }

    private fun acceptRealLocation(location: Location) {
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        if (isMock || !location.latitude.isFinite() || !location.longitude.isFinite()) return
        val point = GeoPoint(location.latitude, location.longitude)
        realLocation = point
        realLocationFixTimeMs = location.time
        realLocationAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        hasFreshRealFix = System.currentTimeMillis() - location.time in 0..FRESH_FIX_AGE_MS
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_REAL_LAT, point.latitude.toString())
            .putString(KEY_LAST_REAL_LON, point.longitude.toString())
            .putLong(KEY_LAST_REAL_TIME, realLocationFixTimeMs)
            .putFloat(KEY_LAST_REAL_ACCURACY, realLocationAccuracyMeters ?: -1f)
            .apply()
        updateCurrentPin(point)
        updateRealLocationLegend()
        showStatus(
            if (hasFreshRealFix) "You are here" else "Showing last location",
            (if (hasFreshRealFix) "Fresh real fix" else "Recent real fix") + " · ±${realLocationAccuracyMeters?.let { formatRouteDistance(it.toDouble()) } ?: "unknown accuracy"} · ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}",
            chip = if (hasFreshRealFix) "REAL FIX READY" else "LAST REAL FIX"
        )
        stopCurrentLocationUpdates()
        if (pendingStartForFreshFix) {
            pendingStartForFreshFix = false
            handler.removeCallbacks(startAnywayAfterLookup)
            handler.post { if (!isFinishing) startSession() }
        } else if (resamplingRealFix) {
            finishRealFixResample("Real location updated", "Mock providers are being restored now.")
        }
    }

    /**
     * Android stops reporting real fixes while test providers are registered, so the only honest way
     * to refresh the blue marker mid-session is to remove them briefly. That window is visible to
     * other apps, so it is always confirmed first and always re-armed afterwards.
     */
    private fun offerRealFixResample() {
        AlertDialog.Builder(this)
            .setTitle("Refresh your real location?")
            .setMessage("GeoShift must remove Android's mock providers for a few seconds to read a real fix. During that window other apps can see where you actually are. Mock mode is restored automatically.")
            .setNegativeButton("Keep spoofing", null)
            .setPositiveButton("Pause and refresh") { _, _ ->
                resamplingRealFix = true
                startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_PAUSE))
                showStatus("Reading your real location…", "Mock providers are paused. Your real location is briefly visible to apps.", chip = "PAUSED · REAL FIX")
                // Give Android a moment to tear the test providers down before asking for a fix.
                handler.postDelayed({ if (!isFinishing && resamplingRealFix) locateCurrentLocation() }, 700L)
            }
            .show()
    }

    private fun finishRealFixResample(title: String, detail: String) {
        resamplingRealFix = false
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_RESUME))
        showStatus(title, detail, chip = "RESUMING MOCK…")
    }

    private fun loadLastRealLocation(): GeoPoint? {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val latitude = prefs.getString(KEY_LAST_REAL_LAT, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LAST_REAL_LON, null)?.toDoubleOrNull() ?: return null
        val point = runCatching { GeoPoint.parse(latitude.toString(), longitude.toString()) }.getOrNull() ?: return null
        realLocationFixTimeMs = prefs.getLong(KEY_LAST_REAL_TIME, 0L)
        realLocationAccuracyMeters = prefs.getFloat(KEY_LAST_REAL_ACCURACY, -1f).takeIf { it >= 0f }
        hasFreshRealFix = realLocationFixTimeMs > 0L && System.currentTimeMillis() - realLocationFixTimeMs in 0..FRESH_FIX_AGE_MS
        return point
    }

    private fun realFixAgeLabel(): String {
        if (realLocationFixTimeMs <= 0L) return "an undated saved fix"
        val age = (System.currentTimeMillis() - realLocationFixTimeMs).coerceAtLeast(0L)
        return when {
            age < 60_000L -> "${(age / 1_000L).coerceAtLeast(1L)} sec ago"
            age < 3_600_000L -> "${age / 60_000L} min ago"
            age < 86_400_000L -> "${age / 3_600_000L} hr ago"
            else -> "${age / 86_400_000L} days ago"
        }
    }

    private fun shortRealFixAge(): String {
        val age = (System.currentTimeMillis() - realLocationFixTimeMs).coerceAtLeast(0L)
        return when {
            realLocationFixTimeMs <= 0L -> "age ?"
            age < 60_000L -> "${(age / 1_000L).coerceAtLeast(1L)}s"
            age < 3_600_000L -> "${age / 60_000L}m"
            age < 86_400_000L -> "${age / 3_600_000L}h"
            else -> "${age / 86_400_000L}d"
        }
    }

    private fun realLocationSnapshotDetail(): String = if (realLocation == null) {
        "No real fix was saved before mock mode. Android only returns the mock fix while it is active; stop spoofing to find your real location."
    } else {
        "Blue marker is your last verified real fix (${realFixAgeLabel()}). Android cannot report live real GPS while mock providers are active — tap the blue locate button to pause them briefly and refresh it."
    }

    private fun updateRealLocationLegend() {
        if (!::realLocationLegend.isInitialized) return
        realLocationLegend.text = if (realLocation == null) "Real · unavailable" else "Real · ${realFixAgeLabel()}"
        realLocationLegend.contentDescription = realLocationSnapshotDetail()
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
        showStatus("Searching…", "Looking up “$query”.", chip = "SEARCHING…")
        runGeocoder {
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

    private fun schedulePlaceSuggestions(query: String, input: AutoCompleteTextView) {
        suggestionTask?.let(handler::removeCallbacks)
        val requestId = ++suggestionGeneration
        val normalized = query.trim()
        if (normalized.length < 2 || parseCoordinateQuery(normalized) != null || !Geocoder.isPresent()) {
            input.dismissDropDown()
            (input.adapter as? ArrayAdapter<PlaceSuggestion>)?.clear()
            return
        }
        val task = Runnable {
            runGeocoder {
                val results = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(this, Locale.getDefault()).getFromLocationName(normalized, 5).orEmpty()
                        .mapNotNull { address ->
                            if (!address.hasLatitude() || !address.hasLongitude()) null else PlaceSuggestion(
                                address.getAddressLine(0) ?: listOfNotNull(address.locality, address.adminArea, address.countryName).joinToString(", "),
                                GeoPoint(address.latitude, address.longitude)
                            )
                        }.distinctBy { it.point }
                }.getOrDefault(emptyList())
                runOnUiThread {
                    if (requestId != suggestionGeneration || isFinishing || input.text.toString().trim() != normalized) return@runOnUiThread
                    val adapter = input.adapter as? ArrayAdapter<PlaceSuggestion> ?: return@runOnUiThread
                    adapter.clear()
                    adapter.addAll(results)
                    adapter.notifyDataSetChanged()
                    if (results.isNotEmpty() && input.hasFocus()) input.showDropDown() else input.dismissDropDown()
                }
            }
        }
        suggestionTask = task
        handler.postDelayed(task, 420L)
    }

    private fun selectSearchSuggestion(suggestion: PlaceSuggestion) {
        suppressPlaceSuggestions = true
        addressInput.setText(suggestion.label)
        suppressPlaceSuggestions = false
        if (mapPickMode == MapPickMode.DESTINATION) {
            setRouteDestination(suggestion.point, suggestion.label)
        } else {
            selectSpoofPoint(suggestion.point)
            showStatus("Spoof point selected", suggestion.label, chip = "TARGET SET")
        }
        addressInput.dismissDropDown()
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(suggestion.point.latitude, suggestion.point.longitude), 15.0), 520)
    }

    private fun selectedSpoofPoint(): GeoPoint? =
        if (!::latitudeInput.isInitialized) null
        else runCatching { GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString()) }.getOrNull()

    private fun selectSpoofPoint(point: GeoPoint) {
        suppressMapSync = true
        latitudeInput.setText(formatCoordinate(point.latitude))
        longitudeInput.setText(formatCoordinate(point.longitude))
        suppressMapSync = false
        updateMockPin(point)
    }

    private fun setMapPickMode(mode: MapPickMode) {
        mapPickMode = mode
        if (::mapPickModeButton.isInitialized) {
            mapPickModeButton.text = if (mode == MapPickMode.SPOOF) "Tap: spoof" else "Tap: destination"
            mapPickModeButton.setTextColor(if (mode == MapPickMode.SPOOF) UiPalette.primaryDeep else 0xFFFFD166.toInt())
            mapPickModeButton.contentDescription = if (mode == MapPickMode.SPOOF) "Map taps set the spoof location. Tap to switch to destination selection." else "Map taps set the directions destination. Tap to switch to spoof location selection."
        }
        showStatus(if (mode == MapPickMode.SPOOF) "Spoof target mode" else "Directions destination mode", if (mode == MapPickMode.SPOOF) "Tap the map to move the coral spoof marker." else "Tap the map or search to set the gold route destination marker.")
    }

    private fun setRouteDestination(point: GeoPoint, label: String) {
        routeDestination = point
        updateDestinationPin(point)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 15.0), 480)
        showStatus("Destination selected", "$label · ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}", chip = "TARGET SET")
    }

    private fun updateDestinationPin(point: GeoPoint?) {
        val source = destinationSource ?: return
        if (point == null) source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        else source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
    }

    private fun showDirectionsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val originInput = edit("Start location or coordinates").apply {
            tag = "directions-origin"
            setText(ORIGIN_SPOOF_POINT)
            setSelection(text.length)
        }
        val originPresets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        originPresets.addView(actionChip(ORIGIN_SPOOF_POINT, UiPalette.primaryContainer, UiPalette.primaryDeep, iconResource = R.drawable.ic_place) {
            originInput.setText(ORIGIN_SPOOF_POINT)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        originPresets.addView(actionChip(ORIGIN_MY_LOCATION, UiPalette.surfaceRaised, UiPalette.ink, iconResource = R.drawable.ic_my_location) {
            originInput.setText(ORIGIN_MY_LOCATION)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        val destinationInput = AutoCompleteTextView(this).apply {
            tag = "directions-destination"
            hint = "Destination or coordinates"
            setTextColor(UiPalette.ink)
            setHintTextColor(UiPalette.muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(UiPalette.surfaceRaised, 14, UiPalette.outline)
            threshold = 2
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setAdapter(ArrayAdapter<PlaceSuggestion>(this@MainActivity, android.R.layout.simple_dropdown_item_1line, mutableListOf()))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { schedulePlaceSuggestions(s?.toString().orEmpty(), this@apply) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnItemClickListener { parent, _, position, _ ->
                (parent.getItemAtPosition(position) as? PlaceSuggestion)?.let { suggestion ->
                    setText("${suggestion.point.latitude}, ${suggestion.point.longitude}")
                    setRouteDestination(suggestion.point, suggestion.label)
                }
            }
            routeDestination?.let { setText("${it.latitude}, ${it.longitude}") }
        }
        content.addView(text("START", 10f, UiPalette.muted, bold = true), margin(bottom = 4))
        content.addView(originPresets, margin(bottom = 6))
        content.addView(originInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        content.addView(text("DESTINATION", 10f, UiPalette.muted, bold = true), margin(top = 12, bottom = 4))
        content.addView(destinationInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        content.addView(
            text("“${ORIGIN_SPOOF_POINT}” routes from the coral marker, so nothing about where you really are leaves the device. “${ORIGIN_MY_LOCATION}” uses your last verified real fix instead. GeoShift asks before sending either to the routing service.", 11f, UiPalette.muted),
            margin(top = 12)
        )
        AlertDialog.Builder(this)
            .setTitle("Directions")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Find route") { _, _ -> requestDirections(originInput.text.toString().trim(), destinationInput.text.toString().trim()) }
            .show()
    }

    private fun requestDirections(originQuery: String, destinationQuery: String) {
        if (originQuery.isBlank() || destinationQuery.isBlank()) {
            showStatus("Enter both locations", "Add a start and destination to find a route.")
            return
        }
        if (originQuery.equals(ORIGIN_MY_LOCATION, ignoreCase = true) && realLocation == null) {
            showStatus("Real location unavailable", "Stop mock mode and wait for a real fix, or start from the spoof point instead.")
            return
        }
        if (originQuery.equals(ORIGIN_SPOOF_POINT, ignoreCase = true) && selectedSpoofPoint() == null) {
            showStatus("Choose a spoof point", "Tap the map or pick a saved place first, then plan the route from it.", chip = "NO TARGET")
            return
        }
        if (!Geocoder.isPresent() && (originQuery.containsLetters() || destinationQuery.containsLetters())) {
            showStatus("Address search unavailable", "Enter coordinates as latitude, longitude on this device.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Send locations for directions?")
            .setMessage(
                "Finding this route sends the start and destination to Android's geocoder and the public OSRM routing service." +
                    if (originQuery.equals(ORIGIN_MY_LOCATION, ignoreCase = true))
                        " The start is your last saved real fix, so your real location leaves the device for this request."
                    else " The start is not your real location, so your real position is not sent."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send and find route") { _, _ -> lookupDirections(originQuery, destinationQuery) }
            .show()
    }

    private fun lookupDirections(originQuery: String, destinationQuery: String) {
        activeRouteCall?.cancel()
        val requestId = ++routeLookupGeneration
        showStatus("Finding directions…", "Resolving both locations and requesting an in-app route.", chip = "ROUTE SEARCH…")
        runGeocoder {
            val resolved = runCatching {
                resolveRoutePoint(originQuery, allowMyLocation = true) to
                    resolveRoutePoint(destinationQuery, allowMyLocation = false)
            }
            resolved.onFailure { failure ->
                runOnUiThread {
                    if (requestId == routeLookupGeneration && !isFinishing) {
                        showStatus("Could not find a location", failure.message ?: "Try a more specific address or coordinates.")
                    }
                }
            }.onSuccess { (origin, destination) ->
                val coordinates = String.format(
                    Locale.US,
                    "%.6f,%.6f;%.6f,%.6f",
                    origin.longitude, origin.latitude, destination.longitude, destination.latitude
                )
                val url = "$ROUTING_BASE_URL/route/v1/driving/$coordinates?steps=true&alternatives=false&overview=full&geometries=geojson"
                val request = Request.Builder().url(url).get().build()
                activeRouteCall = httpClient.newCall(request).also { call ->
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, failure: IOException) {
                            runOnUiThread {
                                if (requestId == routeLookupGeneration && !isFinishing) {
                                    showStatus("Route unavailable", failure.localizedMessage ?: "Check your connection and try again.")
                                }
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val parsed = response.use {
                                runCatching {
                                    if (!it.isSuccessful) throw IOException("Routing service returned HTTP ${it.code}.")
                                    DirectionsRouteParser.parse(it.body?.string().orEmpty())
                                }
                            }
                            runOnUiThread {
                                if (requestId != routeLookupGeneration || isFinishing) return@runOnUiThread
                                parsed.onSuccess { route ->
                                    showRouteOnMap(route)
                                    showRouteStepsDialog(route)
                                    showStatus("Route ready", "${formatRouteDistance(route.distanceMeters)} · ${formatRouteDuration(route.durationSeconds)} · shown on the map", chip = "ROUTE READY")
                                }.onFailure { failure ->
                                    showStatus("No route found", failure.message ?: "Try a different start or destination.")
                                }
                            }
                        }
                    })
                }
            }
        }
    }

    private fun resolveRoutePoint(query: String, allowMyLocation: Boolean): GeoPoint {
        if (allowMyLocation && query.equals(ORIGIN_SPOOF_POINT, true)) {
            return selectedSpoofPoint() ?: error("Choose a spoof point on the map first.")
        }
        if (allowMyLocation && (query.equals(ORIGIN_MY_LOCATION, true) || query.equals("Current location", true))) {
            return realLocation ?: error("No verified real location is saved. Stop mock mode or enter a start location.")
        }
        parseCoordinateQuery(query)?.let { return it }
        @Suppress("DEPRECATION")
        val results = Geocoder(this, Locale.getDefault()).getFromLocationName(query, 1).orEmpty()
        val address = results.firstOrNull() ?: error("No address match for “$query”. Try a more specific address.")
        return GeoPoint(address.latitude, address.longitude)
    }

    private fun parseCoordinateQuery(query: String): GeoPoint? {
        val match = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").matchEntire(query) ?: return null
        return runCatching { GeoPoint.parse(match.groupValues[1], match.groupValues[2]) }.getOrNull()
    }

    private fun String.containsLetters() = any(Char::isLetter)

    private fun showRouteOnMap(route: DirectionsRoute) {
        currentRoute = route
        routeLengthMeters = runCatching { RouteSimulator(route.points, 1.0).totalMeters }.getOrDefault(0.0)
        renderRouteOnMap(route)
        setFollowDrive(true)
        refreshDriveControls()
    }

    private fun renderRouteOnMap(route: DirectionsRoute) {
        if (route.points.size < 2) return
        routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(
            route.points.map { point -> Point.fromLngLat(point.longitude, point.latitude) }
        )))
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().apply {
            route.points.forEach { include(LatLng(it.latitude, it.longitude)) }
        }.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(64)), 650)
    }

    private fun showRouteStepsDialog(route: DirectionsRoute) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        content.addView(text(
            "${formatRouteDistance(route.distanceMeters)}  ·  ${formatRouteDuration(route.durationSeconds)}  ·  Driving",
            15f, UiPalette.primaryDeep, bold = true
        ), margin(bottom = 10))
        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (route.steps.isEmpty()) {
            steps.addView(text("Follow the highlighted route to your destination.", 14f, UiPalette.ink))
        } else {
            route.steps.forEachIndexed { index, step ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val number = text("${index + 1}", 12f, UiPalette.primaryDeep, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(UiPalette.primaryContainer, 24)
                }
                row.addView(number, LinearLayout.LayoutParams(dp(32), dp(32)))
                val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                textColumn.addView(text(step.instruction, 14f, UiPalette.ink, bold = true))
                textColumn.addView(text(
                    "${formatRouteDistance(step.distanceMeters)} · ${formatRouteDuration(step.durationSeconds)}",
                    11f, UiPalette.muted
                ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
                row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
                steps.addView(row)
                if (index < route.steps.lastIndex) steps.addView(View(this).apply { setBackgroundColor(UiPalette.outline) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(steps) }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        content.addView(text("Route data © OpenStreetMap contributors · OSRM. Public routing is best effort; check road conditions and signs.", 10f, UiPalette.muted), margin(top = 10))
        AlertDialog.Builder(this)
            .setTitle("Directions")
            .setView(content)
            .setNeutralButton("Clear route") { _, _ -> clearRoute() }
            .setNegativeButton("Close", null)
            .setPositiveButton("Travel this route") { _, _ -> offerDriveRoute(route) }
            .show()
    }

    private fun offerDriveRoute(route: DirectionsRoute) {
        if (route.points.size < 2) {
            showStatus("Route too short", "Plan a route with at least two points before driving it.")
            return
        }
        val labels = DRIVE_SPEED_LABELS
        AlertDialog.Builder(this)
            .setTitle("Travel at which speed?")
            .setItems(labels) { _, which ->
                pendingDrive = DrivePlan(route.points, DRIVE_SPEEDS_KPH[which])
                selectSpoofPoint(route.points.first())
                startSession()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearRoute() {
        routeLookupGeneration++
        activeRouteCall?.cancel()
        activeRouteCall = null
        currentRoute = null
        routeLengthMeters = 0.0
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        travelledSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        updateHeadingMarker(null, 0f)
        refreshDriveControls()
        showStatus("Route cleared", "The directions line was removed from the map.")
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
                val title = result.getAddressLine(0) ?: listOfNotNull(result.locality, result.countryName).joinToString(", ")
                val suggestion = PlaceSuggestion(title, GeoPoint(result.latitude, result.longitude))
                addressInput.setText(title)
                selectSearchSuggestion(suggestion)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSession() {
        val point = try {
            GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.", chip = "INVALID POINT")
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
            showStatus("Location permission is needed", "Allow precise location so Android can verify the mock providers. Notification permission keeps the active-state notice visible.")
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        if (!isSelectedMockApp()) {
            showStatus("Select GeoShift as the mock app", "Open Developer options and choose GeoShift under Select mock location app.", chip = "SELECT MOCK APP")
            AlertDialog.Builder(this)
                .setTitle("Select GeoShift as mock app")
                .setMessage("Android only allows the selected mock-location app to set test provider locations.")
                .setPositiveButton("Open Developer options") { _, _ -> openDeveloperOptions() }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationOn = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationOn) {
            showStatus("Turn on Location", "Android location services must be enabled for apps to receive provider updates.", chip = "LOCATION NEEDED")
            AlertDialog.Builder(this)
                .setMessage("Turn on Android Location services, then tap Start again.")
                .setPositiveButton("Open Location settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // A real fix is useful (it is what the blue marker and route origins use) but it must never
        // block spoofing: indoors the lookup can simply never succeed.
        if (realLocation == null && !startedWithoutRealFix) {
            pendingStartForFreshFix = true
            startedWithoutRealFix = true
            setSpoofButton("starting")
            showStatus("Finding your real location…", "Saving it for the blue marker and route starts. Spoofing begins in a few seconds either way.", chip = "SAVING REAL FIX…")
            locateCurrentLocation()
            handler.postDelayed(startAnywayAfterLookup, START_ANYWAY_DELAY_MS)
            return
        }
        pendingStartForFreshFix = false
        startedWithoutRealFix = false
        handler.removeCallbacks(startAnywayAfterLookup)
        stopCurrentLocationUpdates()

        val accuracy = when (getPreferences(Context.MODE_PRIVATE).getInt(KEY_ACCURACY_INDEX, 0)) { 1 -> 15f; 2 -> 50f; else -> 5f }
        val drive = pendingDrive
        pendingDrive = null
        val origin = drive?.points?.firstOrNull() ?: point
        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LATITUDE, origin.latitude)
            .putExtra(MockLocationService.EXTRA_LONGITUDE, origin.longitude)
            .putExtra(MockLocationService.EXTRA_ACCURACY, accuracy)
        if (drive != null) {
            intent.putExtra(MockLocationService.EXTRA_ROUTE, drive.flatCoordinates())
            intent.putExtra(MockLocationService.EXTRA_SPEED_KPH, drive.speedKph)
        }
        showStatus(
            "Starting…",
            if (drive == null) "Requesting Android mock GPS, network, and fused fixes."
            else "Travelling the planned route at ${drive.speedKph.toInt()} km/h.",
            chip = if (drive == null) "STARTING…" else "STARTING TRIP…"
        )
        try {
            startForegroundService(intent)
        } catch (failure: Exception) {
            showStatus("Could not start", failure.localizedMessage ?: failure.javaClass.simpleName, chip = "START FAILED")
        }
    }

    private fun stopSession() {
        resamplingRealFix = false
        pendingDrive = null
        handler.removeCallbacks(startAnywayAfterLookup)
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
        showStatus("Stopping…", "Removing the mock providers from Android.", chip = "STOPPING…")
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
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.", chip = "INVALID POINT")
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
        val point = runCatching { GeoPoint.parse(lat, lon) }.getOrNull() ?: return
        selectSavedPlace(SavedPlace(prefs.getString(KEY_HOME_LABEL, "Home") ?: "Home", point))
    }

    private fun addFavorite() {
        val point = try {
            GeoPoint.parse(latitudeInput.text.toString(), longitudeInput.text.toString())
        } catch (failure: IllegalArgumentException) {
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.", chip = "INVALID POINT")
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
        val places = mutableListOf<Pair<Boolean, SavedPlace>>()
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val homeLat = prefs.getString(KEY_HOME_LAT, null)?.toDoubleOrNull()
        val homeLon = prefs.getString(KEY_HOME_LON, null)?.toDoubleOrNull()
        if (homeLat != null && homeLon != null) {
            runCatching { GeoPoint.parse(homeLat.toString(), homeLon.toString()) }.getOrNull()?.let {
                places += true to SavedPlace(prefs.getString(KEY_HOME_LABEL, "Home") ?: "Home", it)
            }
        }
        readFavorites().forEach { places += false to it }
        if (places.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No saved places yet")
                .setMessage("Save your current map pin to make it available here.")
                .setPositiveButton("Save this place") { _, _ -> addFavorite() }
                .setNegativeButton("Close", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose a saved place")
            .setItems(places.map { (isHome, place) -> "${if (isHome) "HOME" else "SAVED"}  ·  ${place.name}  ·  ${formatCoordinate(place.point.latitude)}, ${formatCoordinate(place.point.longitude)}" }.toTypedArray()) { _, which ->
                selectSavedPlace(places[which].second)
            }
            .setNeutralButton("Add current place") { _, _ -> addFavorite() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun selectSavedPlace(place: SavedPlace) {
        suppressMapSync = true
        addressInput.setText(place.name)
        latitudeInput.setText(formatCoordinate(place.point.latitude))
        longitudeInput.setText(formatCoordinate(place.point.longitude))
        suppressMapSync = false
        updateMockPin(place.point, moveCamera = true)
        refreshSavedPlacesStrip()
        showStatus("Location selected", place.name, chip = "TARGET SET")
    }

    private fun readFavorites(): List<SavedPlace> {
        val array = runCatching {
            JSONArray(getPreferences(Context.MODE_PRIVATE).getString(KEY_FAVORITES, "[]"))
        }.getOrElse { JSONArray() }
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
            showStatus("Check the coordinates", failure.message ?: "Enter valid coordinates.", chip = "INVALID POINT")
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
        updateRealLocationLegend()
        val prefs = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
        when (prefs.getString(MockLocationService.KEY_STATE, MockLocationService.STATE_STOPPED)) {
            MockLocationService.STATE_ACTIVE -> {
                val remaining = prefs.getFloat(MockLocationService.KEY_REMAINING_METERS, -1f)
                val driving = prefs.getBoolean(MockLocationService.KEY_SIMULATING, false)
                val arrived = prefs.getBoolean(MockLocationService.KEY_ARRIVED, false)
                if (driving) renderDriveProgress(prefs) else hideDriveHud()
                setSpoofButton(if (driving) "driving" else "active")
                if (::mapStatusChip.isInitialized) {
                    mapStatusChip.text = when {
                        driving && arrived -> "ARRIVED"
                        driving && remaining >= 0f -> "DRIVING · ${formatRouteDistance(remaining.toDouble())} LEFT"
                        realLocation == null -> "MOCK · NO REAL FIX"
                        else -> "MOCK · REAL PIN ${shortRealFixAge()}"
                    }
                    mapStatusChip.contentDescription = realLocationSnapshotDetail()
                    mapStatusChip.setTextColor(if (driving) UiPalette.target else UiPalette.primaryDeep)
                }
                statusView.setText(R.string.status_mock_fix_title)
                val timestamp = prefs.getLong(MockLocationService.KEY_UPDATED, 0L)
                val formattedTime = if (timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(timestamp) else ""
                val mockDetail = getString(
                    R.string.status_mock_fix_detail,
                    prefs.getString(MockLocationService.KEY_DETAIL, ""),
                    formattedTime
                )
                statusDetailView.text = "$mockDetail\n${realLocationSnapshotDetail()}"
                statusView.setTextColor(UiPalette.primaryDeep)
            }
            MockLocationService.STATE_PAUSED -> {
                setSpoofButton("starting")
                hideDriveHud()
                if (::mapStatusChip.isInitialized) {
                    mapStatusChip.text = "PAUSED · REAL FIX"
                    mapStatusChip.setTextColor(UiPalette.warning)
                }
                statusView.text = "Mock providers paused"
                statusDetailView.text = prefs.getString(MockLocationService.KEY_DETAIL, "Reading a real fix.")
                statusView.setTextColor(UiPalette.warning)
            }
            MockLocationService.STATE_ERROR -> {
                setSpoofButton("stopped")
                hideDriveHud()
                if (::mapStatusChip.isInitialized) {
                    mapStatusChip.text = "SETUP NEEDED"
                    mapStatusChip.setTextColor(UiPalette.error)
                }
                statusView.setText(R.string.status_mock_location_failed)
                statusDetailView.text = prefs.getString(MockLocationService.KEY_DETAIL, "Check Developer options and permissions.")
                statusView.setTextColor(UiPalette.error)
                if (detailsGroup.visibility != View.VISIBLE) toggleDetails()
            }
            MockLocationService.STATE_STARTING -> {
                setSpoofButton("starting")
                if (::mapStatusChip.isInitialized) mapStatusChip.text = "STARTING…"
                showStatus("Starting…", prefs.getString(MockLocationService.KEY_DETAIL, "Setting up providers.") ?: "Setting up providers.")
            }
            else -> {
                hideDriveHud()
                val ready = isSelectedMockApp()
                if (pendingStartForFreshFix) {
                    setSpoofButton("starting")
                    statusView.text = "Finding your real location"
                    statusDetailView.text = "Saving a non-mock fix for the blue marker. Spoofing starts in a few seconds whether or not it arrives."
                    statusView.setTextColor(UiPalette.warning)
                    if (::mapStatusChip.isInitialized) mapStatusChip.text = "SAVING REAL FIX…"
                } else {
                    setSpoofButton("stopped")
                    if (::mapStatusChip.isInitialized && !locationLookupActive) {
                        mapStatusChip.text = when {
                            hasFreshRealFix -> "REAL FIX READY"
                            realLocation != null -> "LAST REAL FIX"
                            else -> "LOCATION NEEDED"
                        }
                        mapStatusChip.setTextColor(if (realLocation != null) UiPalette.ink else UiPalette.warning)
                    }
                    statusView.text = if (ready) "Ready to start" else "Setup needed"
                    statusDetailView.text = if (ready) "Choose a place, then tap Start spoofing." else "Select GeoShift in Developer options to enable Android mock providers."
                    statusView.setTextColor(if (ready) UiPalette.primaryDeep else UiPalette.warning)
                }
            }
        }
        handler.removeCallbacks(statusPoll)
        if (resumed) handler.postDelayed(statusPoll, 1_000L)
    }

    private fun setFollowDrive(follow: Boolean) {
        followDrive = follow
        if (!::driveFollowChip.isInitialized) return
        driveFollowChip.text = if (follow) "Following" else "Follow"
        driveFollowChip.contentDescription =
            if (follow) "The map follows the trip. Tap to stop following." else "The map stays where you put it. Tap to follow the trip."
        if (follow) lastDrivePoint?.let { point ->
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), DRIVE_FOLLOW_ZOOM), 600)
        }
    }

    private fun refreshDriveControls() {
        if (!::travelButton.isInitialized) return
        val driving = driveHud.visibility == View.VISIBLE
        val hasRoute = currentRoute != null
        // Always offered when not already travelling, so the feature is visible on a first launch
        // instead of hiding behind the directions dialog.
        travelButton.visibility = if (driving) View.GONE else View.VISIBLE
        travelButton.text = if (hasRoute) "Travel to destination" else "Plan a route to travel"
    }

    /** Mirrors the running simulation onto the map and the HUD once per second. */
    private fun renderDriveProgress(prefs: android.content.SharedPreferences) {
        val total = prefs.getFloat(MockLocationService.KEY_TOTAL_METERS, -1f)
        val remaining = prefs.getFloat(MockLocationService.KEY_REMAINING_METERS, -1f)
        val arrived = prefs.getBoolean(MockLocationService.KEY_ARRIVED, false)
        val speedKph = prefs.getFloat(MockLocationService.KEY_SPEED_KPH, 0f)
        val bearing = prefs.getFloat(MockLocationService.KEY_BEARING, 0f)
        val point = runCatching {
            GeoPoint.parse(
                prefs.getString(MockLocationService.KEY_CURRENT_LAT, null) ?: return,
                prefs.getString(MockLocationService.KEY_CURRENT_LON, null) ?: return
            )
        }.getOrNull() ?: return

        val previous = lastDrivePoint
        lastDrivePoint = point
        updateMockPin(point, animate = false)
        updateHeadingMarker(point, bearing)
        if (remaining >= 0f) updateTravelledRoute(remaining.toDouble())
        val movedMeters = previous?.let { RouteSimulator.metersBetween(it, point) } ?: Double.MAX_VALUE
        if (followDrive && movedMeters > 1.0) map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), DRIVE_FOLLOW_ZOOM),
            900
        )

        if (!::driveHud.isInitialized) return
        driveHud.visibility = View.VISIBLE
        val etaSeconds = if (speedKph > 0f && remaining > 0f) remaining / (speedKph / 3.6f) else 0f
        driveHeadline.text = when {
            arrived -> "Arrived"
            remaining >= 0f -> "${formatRouteDistance(remaining.toDouble())} left · ${formatRouteDuration(etaSeconds.toDouble())}"
            else -> "Travelling"
        }
        driveSubline.text = if (arrived) {
            "Holding ${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}. Apps keep reporting the destination."
        } else {
            "${speedKph.toInt()} km/h · heading ${bearing.toInt()}° · apps see only this trip"
        }
        val fraction = if (total > 0f && remaining >= 0f) ((total - remaining) / total).coerceIn(0f, 1f) else 0f
        driveHud.post {
            val trackWidth = (driveProgressFill.parent as? View)?.width ?: return@post
            driveProgressFill.layoutParams = driveProgressFill.layoutParams.apply {
                width = (trackWidth * fraction).toInt().coerceAtLeast(if (fraction > 0f) dp(6) else 0)
            }
            driveProgressFill.requestLayout()
        }
        refreshDriveControls()
    }

    private fun hideDriveHud() {
        val hudShown = ::driveHud.isInitialized && driveHud.visibility == View.VISIBLE
        if (!hudShown && lastDrivePoint == null) {
            refreshDriveControls()
            return
        }
        lastDrivePoint = null
        if (::driveHud.isInitialized && driveHud.visibility != View.GONE) driveHud.visibility = View.GONE
        updateHeadingMarker(null, 0f)
        travelledSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        refreshDriveControls()
    }

    private fun toggleSpoofing() {
        if (pendingStartForFreshFix) {
            pendingStartForFreshFix = false
            startedWithoutRealFix = false
            handler.removeCallbacks(startAnywayAfterLookup)
            stopCurrentLocationUpdates()
            setSpoofButton("stopped")
            showStatus("Start canceled", "The real-location lookup stopped before mock mode began.", chip = "CANCELED")
            return
        }
        val state = getSharedPreferences(MockLocationService.PREFS, Context.MODE_PRIVATE)
            .getString(MockLocationService.KEY_STATE, MockLocationService.STATE_STOPPED)
        if (state in RUNNING_STATES) stopSession() else startSession()
    }

    private fun setSpoofButton(state: String) {
        if (!::startButton.isInitialized) return
        if (spoofButtonState == state) return
        spoofButtonState = state
        when (state) {
            "active" -> {
                startButton.text = "Stop spoofing"
                setButtonIcon(R.drawable.ic_stop)
                startButton.contentDescription = "Stop spoofing"
                startButton.background = buttonGradient(0xFFA93F43.toInt(), 0xFFD65F55.toInt())
                animateButtonState()
            }
            "driving" -> {
                startButton.text = "Stop travelling"
                setButtonIcon(R.drawable.ic_stop)
                startButton.contentDescription = "Stop spoofing"
                startButton.background = buttonGradient(0xFFA93F43.toInt(), 0xFFD65F55.toInt())
                animateButtonState()
            }
            "starting" -> {
                startButton.text = "Starting…  ·  Tap to stop"
                setButtonIcon(R.drawable.ic_stop)
                startButton.contentDescription = "Stop starting mock location"
                startButton.background = buttonGradient(0xFF8F5E27.toInt(), 0xFFB78035.toInt())
            }
            else -> {
                startButton.text = "Start spoofing"
                setButtonIcon(R.drawable.ic_play)
                startButton.contentDescription = "Start spoofing"
                startButton.background = buttonGradient(UiPalette.primaryActionStart, UiPalette.primaryBright)
            }
        }
    }

    private fun animateButtonState() {
        if (android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return
        startButton.animate().cancel()
        startButton.scaleX = 0.97f
        startButton.scaleY = 0.97f
        startButton.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
    }

    /**
     * Single place that updates the sheet status and the map chip. The chip label is passed in
     * rather than derived from the title, so renaming a message cannot silently break the chip.
     */
    private fun showStatus(title: String, detail: String, chip: String? = null) {
        if (!::statusView.isInitialized) return
        statusView.text = title
        statusView.setTextColor(UiPalette.ink)
        statusDetailView.text = detail
        if (::mapStatusChip.isInitialized) {
            mapStatusChip.text = chip ?: title.uppercase(Locale.getDefault()).take(22)
            mapStatusChip.setTextColor(UiPalette.ink)
            mapStatusChip.contentDescription = "$title. Tap location status to see details."
        }
        val setupFailure = title in setOf(
            "Location permission is needed", "Select GeoShift as the mock app", "Could not start",
            "Could not find location", "Location not found", "Turn on Location", "Choose a spoof point"
        )
        if (setupFailure && ::detailsGroup.isInitialized && detailsGroup.visibility != View.VISIBLE) {
            toggleDetails()
        }
    }

    private fun actionChip(label: String, backgroundColor: Int, foregroundColor: Int, iconResource: Int? = null, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextColor(foregroundColor)
        setPadding(dp(8), dp(7), dp(8), dp(7))
        minHeight = dp(48)
        if (iconResource != null) {
            val icon = requireNotNull(getDrawable(iconResource)).mutate()
            icon.setTint(foregroundColor)
            icon.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(6)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x5535D2B5), rounded(backgroundColor, 14), rounded(UiPalette.surfaceRaised, 14))
        isClickable = true
        isFocusable = true
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
    }

    private fun iconControl(iconResource: Int, label: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(iconResource)
        imageTintList = ColorStateList.valueOf(UiPalette.ink)
        contentDescription = label
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = rippleSurface(ColorDrawable(Color.TRANSPARENT), 16)
        setMinimumWidth(dp(48))
        setMinimumHeight(dp(48))
        isClickable = true
        isFocusable = true
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
    }

    private fun rippleSurface(content: android.graphics.drawable.Drawable, radiusDp: Int) = RippleDrawable(
        ColorStateList.valueOf(0x5535D2B5), content, rounded(UiPalette.surfaceRaised, radiusDp)
    )

    private fun buttonGradient(vararg colors: Int) = RippleDrawable(
        ColorStateList.valueOf(0x55FFFFFF),
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = dp(18).toFloat() },
        rounded(UiPalette.surfaceRaised, 18)
    )

    private fun setButtonIcon(resource: Int) {
        val icon = requireNotNull(getDrawable(resource)).mutate()
        icon.setTint(Color.WHITE)
        icon.setBounds(0, 0, dp(20), dp(20))
        startButton.setCompoundDrawablesRelative(icon, null, null, null)
        startButton.compoundDrawablePadding = dp(9)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
    }

    private fun edit(hintText: String, signedDecimal: Boolean = false) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(true)
        if (signedDecimal) {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            // Several IMEs omit the minus key for numberSigned, which makes southern and western
            // coordinates impossible to type. A digits key listener accepts it on any keyboard.
            keyListener = android.text.method.DigitsKeyListener.getInstance(Locale.US, true, true)
        } else {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(UiPalette.ink)
        setHintTextColor(UiPalette.muted)
        background = rounded(UiPalette.surfaceRaised, 13, UiPalette.outline)
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
        intArrayOf(UiPalette.surfaceRaised, UiPalette.canvas)
    ).apply {
        val radius = dp(radiusDp).toFloat()
        setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
    }

    private fun toggleDetails() {
        val expanded = detailsGroup.visibility != View.VISIBLE
        detailsGroup.visibility = if (expanded) View.VISIBLE else View.GONE
        collapseToggle.setImageResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        collapseToggle.contentDescription = if (expanded) "Collapse extra location controls" else "Expand extra location controls"
        locationSheet.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun refreshSavedPlacesStrip() {
        if (!::placesStrip.isInitialized) return
        placesStrip.removeAllViews()
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val items = mutableListOf<Pair<Boolean, SavedPlace>>()
        val homeLat = prefs.getString(KEY_HOME_LAT, null)?.toDoubleOrNull()
        val homeLon = prefs.getString(KEY_HOME_LON, null)?.toDoubleOrNull()
        if (homeLat != null && homeLon != null) {
            val homePoint = runCatching { GeoPoint.parse(homeLat.toString(), homeLon.toString()) }.getOrNull()
            if (homePoint != null) items += true to SavedPlace(prefs.getString(KEY_HOME_LABEL, "Home") ?: "Home", homePoint)
        }
        readFavorites().forEach { items += false to it }
        if (::savedPlacesTitle.isInitialized) savedPlacesTitle.text = "SAVED PLACES  ·  ${items.size}"
        val selectedPoint = selectedSpoofPoint()
        if (items.isEmpty()) {
            placesStrip.addView(text("Choose a point, then tap + Save place", 12f, UiPalette.muted).apply {
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "No saved places yet. Choose a point on the map, then save it."
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            return
        }
        items.forEach { (isHome, place) ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val testableName = place.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                tag = "saved-place-$testableName"
                contentDescription = "Use saved place ${place.name}, ${formatCoordinate(place.point.latitude)}, ${formatCoordinate(place.point.longitude)}"
                setPadding(dp(10), dp(6), dp(13), dp(6))
                val isSelected = selectedPoint == place.point
                val cardColor = when { isSelected -> UiPalette.targetSoft; isHome -> UiPalette.primaryContainer; else -> UiPalette.surface }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x332C7C69),
                    rounded(cardColor, 16, if (isSelected) UiPalette.target else if (isHome) 0xFF286457.toInt() else UiPalette.outline), rounded(UiPalette.surfaceRaised, 16)
                )
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                val icon = ImageView(this@MainActivity).apply {
                    setImageResource(if (isHome) R.drawable.ic_home else R.drawable.ic_place)
                    imageTintList = ColorStateList.valueOf(if (isHome) UiPalette.primaryDeep else UiPalette.target)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = rounded(if (isHome) 0xFF1B4840.toInt() else UiPalette.targetSoft, 12)
                }
                addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
                val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(text(place.name, 12f, UiPalette.ink, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                labels.addView(text("${formatCoordinate(place.point.latitude)}, ${formatCoordinate(place.point.longitude)}", 9f, UiPalette.muted).apply { maxLines = 1 })
                addView(labels, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                setOnClickListener {
                    selectSavedPlace(place)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
                contentDescription = "Use ${place.name} at ${formatCoordinate(place.point.latitude)}, ${formatCoordinate(place.point.longitude)}${if (isSelected) ", selected" else ""}; long press to rename or remove"
                setOnLongClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    manageSavedPlace(isHome, place)
                    true
                }
            }
            placesStrip.addView(chip, LinearLayout.LayoutParams(dp(186), dp(52)).apply { marginEnd = dp(8) })
        }
    }

    /** Six decimals is about 0.1 m; trailing zeros only make the coordinate harder to read. */
    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun manageSavedPlace(isHome: Boolean, place: SavedPlace) {
        val actions = arrayOf("Rename", "Remove")
        AlertDialog.Builder(this)
            .setTitle(place.name)
            .setItems(actions) { _, selected ->
                if (selected == 0) {
                    val input = EditText(this).apply { hint = "Place name"; setText(place.name); selectAll() }
                    AlertDialog.Builder(this).setTitle("Rename saved place").setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val newName = input.text.toString().trim().ifEmpty { place.name }
                            if (isHome) {
                                getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_HOME_LABEL, newName).apply()
                                refreshSavedPlacesStrip()
                            } else {
                                val places = readFavorites().toMutableList()
                                val index = places.indexOfFirst { it.name == place.name && it.point == place.point }
                                if (index >= 0) places[index] = place.copy(name = newName)
                                writeFavorites(places)
                            }
                            showStatus("Place renamed", newName)
                        }.setNegativeButton("Cancel", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Remove ${place.name}?")
                        .setMessage("This removes the saved shortcut. It does not change your active location.")
                        .setPositiveButton("Remove") { _, _ ->
                            if (isHome) {
                                getPreferences(Context.MODE_PRIVATE).edit()
                                    .remove(KEY_HOME_LAT).remove(KEY_HOME_LON).remove(KEY_HOME_LABEL).apply()
                                refreshSavedPlacesStrip()
                            } else {
                                writeFavorites(readFavorites().filterNot { it.name == place.name && it.point == place.point })
                            }
                            showStatus("Place removed", place.name)
                        }.setNegativeButton("Keep", null).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private data class SavedPlace(val name: String, val point: GeoPoint)

    /** A route handed to the service so the mock fix walks it instead of standing still. */
    private data class DrivePlan(val points: List<GeoPoint>, val speedKph: Double) {
        fun flatCoordinates() = DoubleArray(points.size * 2).also { out ->
            points.forEachIndexed { index, point ->
                out[index * 2] = point.latitude
                out[index * 2 + 1] = point.longitude
            }
        }
    }
    private data class PlaceSuggestion(val label: String, val point: GeoPoint) {
        override fun toString() = label
    }
    private enum class MapPickMode { SPOOF, DESTINATION }

    companion object {
        private const val REQUEST_PERMISSIONS = 70
        private const val REQUEST_EXPORT_PLACES = 71
        private const val REQUEST_IMPORT_PLACES = 72
        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LON = "home_lon"
        private const val KEY_HOME_LABEL = "home_label"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ACCURACY_INDEX = "accuracy_index"
        private const val KEY_LAST_REAL_LAT = "last_real_lat"
        private const val KEY_LAST_REAL_LON = "last_real_lon"
        private const val KEY_LAST_REAL_TIME = "last_real_time"
        private const val KEY_LAST_REAL_ACCURACY = "last_real_accuracy"
        private const val MAX_FAVORITES = 20
        private const val LAST_KNOWN_FIX_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val FRESH_FIX_AGE_MS = 60 * 1000L
        private const val DARK_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        private const val ROUTING_BASE_URL = "https://router.project-osrm.org"
        private const val SOURCE_CURRENT = "current-location-source"
        private const val SOURCE_MOCK = "mock-location-source"
        private const val SOURCE_ROUTE = "directions-route-source"
        private const val SOURCE_DESTINATION = "route-destination-source"
        private const val SOURCE_ACCURACY = "real-accuracy-source"
        private const val SOURCE_TRAVELLED = "directions-travelled-source"
        private const val SOURCE_VEHICLE = "drive-heading-source"
        private const val MOCK_ICON = "mock-location-pin"
        private const val VEHICLE_ICON = "drive-heading-marker"
        private const val FEATURE_BEARING = "bearing"
        private const val CURRENT_LOCATION_TIMEOUT_MS = 20_000L
        private const val START_ANYWAY_DELAY_MS = 8_000L
        private const val DRIVE_FOLLOW_ZOOM = 16.5
        private val RUNNING_STATES = setOf(
            MockLocationService.STATE_ACTIVE,
            MockLocationService.STATE_STARTING,
            MockLocationService.STATE_PAUSED
        )
        private const val ORIGIN_SPOOF_POINT = "Spoof point"
        private const val ORIGIN_MY_LOCATION = "My location"
        private val DRIVE_SPEED_LABELS = arrayOf("Walking · 5 km/h", "City driving · 40 km/h", "Highway · 100 km/h")
        private val DRIVE_SPEEDS_KPH = doubleArrayOf(5.0, 40.0, 100.0)
    }
}
