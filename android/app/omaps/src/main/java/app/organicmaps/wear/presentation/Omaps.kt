package app.organicmaps.wear.presentation

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import app.organicmaps.sdk.location.SensorListener
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.UiEvent
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.presentation.navigation.NavigationScreen
import app.organicmaps.wear.presentation.navigation.StatsScreen
import app.organicmaps.wear.presentation.search.SearchScreen
import app.organicmaps.wear.presentation.theme.OrganicMapsTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.view.KeyEvent

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import app.organicmaps.sdk.settings.RoadType
import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.wear.presentation.navigation.RoutingOptionsRow

import androidx.wear.input.WearableButtons
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.compose.runtime.CompositionLocalProvider
import app.organicmaps.wear.LocalAmbientMode
import androidx.fragment.app.FragmentActivity

class Omaps : FragmentActivity() {
    private var availableButtons = emptySet<Int>()

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            NavigationStateHolder.update { it.copy(isAmbient = true) }
        }
        override fun onExitAmbient() {
            NavigationStateHolder.update { it.copy(isAmbient = false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        // Ambient mode via AmbientLifecycleObserver (no AmbientCallbackProvider interface needed,
        // unlike the deprecated AmbientModeSupport). Guarded by FEATURE_WATCH + try/catch so the
        // wear runtime never class-loads on a generic (non-watch) emulator.
        try {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
            }
        } catch (e: Throwable) {
            android.util.Log.w("Omaps", "AmbientMode not available or library missing: ${e.message}")
        }

        // Check for available hardware buttons
        val buttons = mutableSetOf<Int>()
        val isWatch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        if (isWatch) {
            try {
                val buttonCount = WearableButtons.getButtonCount(this)
                if (buttonCount > 0) {
                    val keyCodes = listOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)
                    keyCodes.forEach { code ->
                        if (WearableButtons.getButtonInfo(this, code) != null) {
                            buttons.add(code)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Omaps", "WearableButtons not available: ${e.message}")
            }
        }
        availableButtons = buttons
        android.util.Log.d("Omaps", "Available hardware buttons: $availableButtons")

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = permissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }
        
        // Initialize state from prefs
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)

        fun getSafeInt(key: String, default: Int): Int {
            val value = prefs.all[key] ?: return default
            return when (value) {
                is Int -> value
                is Long -> value.toInt()
                is String -> value.toIntOrNull() ?: default
                else -> default
            }
        }

        fun getSafeBoolean(key: String, default: Boolean): Boolean {
            val value = prefs.all[key] ?: return default
            return when (value) {
                is Boolean -> value
                is String -> value.toBoolean()
                else -> default
            }
        }

        val isMapEnabled = getSafeBoolean("mapEnabled", true)
        val isOfflineMapsEnabled = getSafeBoolean("watchLocalMode", false)
        val routerType = getSafeInt("routerType", 0)
        val poiMask = getSafeInt("poiCategoriesMask", 0x3F)
        
        val is3dEnabled = getSafeBoolean("pref_wear_os_3d", true)
        val is3dBldEnabled = getSafeBoolean("pref_wear_os_3d_buildings", true)
        val isAutoZoomEnabled = getSafeBoolean("pref_wear_os_auto_zoom", true)
        val units = getSafeInt("pref_wear_os_munits", 0)
        val style = prefs.getString("pref_wear_os_map_style", "default") ?: "default"
        
        val avoidTolls = getSafeBoolean("pref_wear_os_avoid_tolls", false)
        val avoidMotorways = getSafeBoolean("pref_wear_os_avoid_motorways", false)
        val avoidFerries = getSafeBoolean("pref_wear_os_avoid_ferries", false)
        val avoidUnpaved = getSafeBoolean("pref_wear_os_avoid_unpaved", false)

        val transitEnabled = getSafeBoolean("pref_wear_os_transit", false)
        val bikingEnabled = getSafeBoolean("pref_wear_os_biking", false)
        val hikingEnabled = getSafeBoolean("pref_wear_os_hiking", false)
        val isolinesEnabled = getSafeBoolean("pref_wear_os_isolines", false)

        val lastLat = prefs.getFloat("last_known_lat", 0.0f).toDouble()
        val lastLon = prefs.getFloat("last_known_lon", 0.0f).toDouble()
        
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            mapEnabled = isMapEnabled,
            watchLocalMode = isOfflineMapsEnabled,
            routerType = routerType,
            poiCategoriesMask = poiMask,
            measurementUnits = units,
            mapStyle = style,
            avoidTolls = avoidTolls,
            avoidMotorways = avoidMotorways,
            avoidFerries = avoidFerries,
            avoidUnpaved = avoidUnpaved,
            transitEnabled = transitEnabled,
            bikingEnabled = bikingEnabled,
            hikingEnabled = hikingEnabled,
            isolinesEnabled = isolinesEnabled,
            lat = lastLat,
            lon = lastLon
        ), force = true)

        setContent {
            WearApp()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val navState = NavigationStateHolder.state.value
        if (navState.mapEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_NAVIGATE_IN -> {
                    app.organicmaps.sdk.Map.zoomIn()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_NAVIGATE_OUT -> {
                    app.organicmaps.sdk.Map.zoomOut()
                    return true
                }
                KeyEvent.KEYCODE_STEM_1 -> {
                    return true
                }
                KeyEvent.KEYCODE_STEM_2 -> {
                    if (navState.isMapUnlocked) {
                        repeat(5) {
                            val mode = app.organicmaps.sdk.location.LocationState.getMode()
                            if (mode == app.organicmaps.sdk.location.LocationState.FOLLOW || mode == app.organicmaps.sdk.location.LocationState.FOLLOW_AND_ROTATE) return@repeat
                            app.organicmaps.sdk.location.LocationState.nativeSwitchToNextMode()
                        }
                    } else {
                        app.organicmaps.sdk.Framework.nativeStopLocationFollow()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val isNavigating = navState.isNavigating
    val isMapEnabled = navState.mapEnabled
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    
    val pagerState = rememberPagerState(
        pageCount = {
            if (isNavigating) {
                if (isMapEnabled) 3 else 2
            } else {
                if (isMapEnabled) 6 else 5
            }
        }
    )

    androidx.compose.runtime.LaunchedEffect(Unit) {
        NavigationStateHolder.events.collect { event ->
            when (event) {
                is UiEvent.OpenMap -> {
                    // Read nav state live: this collector lives in LaunchedEffect(Unit), so the
                    // captured `isNavigating` is frozen at first composition. A stale `true` would
                    // silently swallow every OpenMap (e.g. "Show on Watch" never leaving the
                    // bookmarks page). Page 1 is the map in both nav and non-nav layouts.
                    if (!NavigationStateHolder.state.value.isNavigating) {
                        pagerState.animateScrollToPage(1)
                    }
                }
                is UiEvent.OpenMapManager -> {
                    if (!isNavigating) {
                        pagerState.animateScrollToPage(if (isMapEnabled) 4 else 3)
                    }
                }
                is UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, event.duration).show()
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(isNavigating) {
        if (isNavigating) {
            // Power saving: In companion mode, default to Turn-by-Turn screen
            if (!navState.standaloneMode && !navState.watchLocalMode) {
                pagerState.scrollToPage(if (isMapEnabled) 1 else 0)
            } else if (isMapEnabled) {
                pagerState.scrollToPage(1)
            }
        } else {
            pagerState.scrollToPage(1)
        }
    }

    androidx.compose.runtime.LaunchedEffect(isNavigating, navState.showOnLockScreen) {
        if (context is android.app.Activity) {
            val activity = context
            if (isNavigating && navState.showOnLockScreen) {
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
            } else {
                activity.setShowWhenLocked(false);
                activity.setTurnScreenOn(false);
            }
        }
    }

    // Intercept back gesture on the Map page to navigate to the previous tab
    // instead of exiting the app or panning the map at the edge.
    BackHandler(enabled = pagerState.currentPage == 1) {
        scope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    androidx.compose.runtime.LaunchedEffect(navState.isPhoneConnected) {
        if (!navState.isPhoneConnected && navState.locationSource == "PHONE_ONLY") {
            // Fallback to internal GPS to keep map moving
            NavigationStateHolder.update { it.copy(locationSource = "AUTO") }
            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Phone lost, using watch GPS"))
        }
    }

    OrganicMapsTheme {
        CompositionLocalProvider(LocalAmbientMode provides navState.isAmbient) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (!isNavigating) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true
                ) { page ->
                    val isVisible = pagerState.currentPage == page
                    if (isMapEnabled) {
                        when (page) {
                            0 -> SearchScreen(isVisible = isVisible)
                            1 -> MapPanel(
                                isVisible = isVisible,
                                onSearchClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                                onSettingsClick = { scope.launch { pagerState.animateScrollToPage(if (isMapEnabled) 5 else 4) } }
                            )
                            2 -> app.organicmaps.wear.presentation.bookmarks.BookmarksScreen(isVisible = isVisible)
                            3 -> app.organicmaps.wear.presentation.track.TrackScreen(isVisible = isVisible)
                            4 -> app.organicmaps.wear.presentation.downloads.MapManagerScreen(isVisible = isVisible)
                            5 -> app.organicmaps.wear.presentation.settings.SettingsScreen()
                        }
                    } else {
                        when (page) {
                            0 -> SearchScreen(isVisible = isVisible)
                            1 -> app.organicmaps.wear.presentation.bookmarks.BookmarksScreen(isVisible = isVisible)
                            2 -> app.organicmaps.wear.presentation.track.TrackScreen(isVisible = isVisible)
                            3 -> app.organicmaps.wear.presentation.downloads.MapManagerScreen(isVisible = isVisible)
                            4 -> app.organicmaps.wear.presentation.settings.SettingsScreen()
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true
                ) { page ->
                    val isVisible = pagerState.currentPage == page
                    if (isMapEnabled) {
                        when (page) {
                            0 -> NavigationPanel(navState, isVisible = isVisible)
                            1 -> MapPanel(
                                isVisible = isVisible,
                                onSearchClick = { /* No search during navigation? Or go to nav screen */ },
                                onSettingsClick = { /* Settings during nav */ }
                            )
                            2 -> StatsScreen(navState)
                        }
                    } else {
                        when (page) {
                            0 -> NavigationPanel(navState, isVisible = isVisible)
                            1 -> StatsScreen(navState)
                        }
                    }
                }
            }
            
            if (navState.isActive && !navState.isNavigating) {
                val routeReady = navState.isRouteReady || (!navState.isRouteBuilding && navState.routeBuildProgress >= 100)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))), 
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (navState.destinationName.isNotEmpty()) navState.destinationName else "Route Preview",
                            style = MaterialTheme.typography.caption2,
                            color = Color(0xFF00E5FF),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )

                        // Compact Routing Options Row
                        RoutingOptionsRow(
                            avoidTolls = navState.avoidTolls,
                            avoidMotorways = navState.avoidMotorways,
                            avoidFerries = navState.avoidFerries,
                            avoidUnpaved = navState.avoidUnpaved,
                            onOptionToggled = { type, newVal ->
                                if (newVal) RoutingOptions.addOption(type) else RoutingOptions.removeOption(type)
                                NavigationStateHolder.update { current ->
                                    val next = when(type) {
                                        RoadType.Toll -> current.copy(avoidTolls = newVal)
                                        RoadType.Motorway -> current.copy(avoidMotorways = newVal)
                                        RoadType.Ferry -> current.copy(avoidFerries = newVal)
                                        RoadType.Dirty -> current.copy(avoidUnpaved = newVal)
                                        else -> current
                                    }
                                    // Re-trigger calculation if in standalone mode
                                    if (navState.standaloneMode || !navState.isPhoneConnected) {
                                        app.organicmaps.sdk.routing.RoutingController.get().rebuildLastRoute()
                                    } else {
                                        WearCommandService.syncPreferences(context)
                                    }
                                    next.copy(lastSettingsInteractionTime = System.currentTimeMillis())
                                }
                            }
                        )
                        
                        androidx.wear.compose.material.Button(
                            onClick = {
                                if (navState.standaloneMode || navState.watchLocalMode) {
                                    app.organicmaps.sdk.routing.RoutingController.get().start()
                                    NavigationStateHolder.update(navState.copy(
                                        isNavigating = true,
                                        isRouteBuilding = false,
                                        isRouteReady = false,
                                        routeBuildProgress = 100
                                    ))
                                } else {
                                    WearCommandService.startNavigation(context)
                                }
                            },
                            enabled = routeReady && !navState.isRouteBuilding,
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            val statusText = when {
                                navState.isRouteBuilding -> "Calculating ${navState.routeBuildProgress}%"
                                !routeReady -> when(navState.lastRouteError) {
                                    2 -> "Waiting for Fix"
                                    5 -> "Start pos missing"
                                    6 -> "End pos missing"
                                    7 -> "Cross-map fails"
                                    8 -> "No route found"
                                    9 -> "Download Maps"
                                    else -> "No route found"
                                }
                                else -> "Start Navigation"
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.button.copy(fontSize = 13.sp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        
                        CompactChip(
                            onClick = { WearCommandService.cancelNavigation(context) },
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Cancel", style = MaterialTheme.typography.caption2) },
                            modifier = Modifier.width(100.dp).height(32.dp)
                        )
                    }
                }
            }

            MapDownloadOverlay()
            
            // Status Indicators - Always on top
            StatusIndicators(navState)
            }
        }
    }
}

@Composable
fun StatusIndicators(navState: app.organicmaps.wear.NavigationState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 28.dp), // Safe margin for round screens
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            if (navState.watchLocalMode) {
                Icon(
                    imageVector = Icons.Default.SdCard,
                    contentDescription = "Offline Mode",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            
            val (connIcon, connColor) = when {
                navState.isPhoneConnected -> (if (navState.backend == "BLUETOOTH") Icons.Default.Bluetooth else Icons.Default.Cloud) to Color(0xFF4CAF50)
                navState.isConnecting -> (if (navState.backend == "BLUETOOTH") Icons.Default.Bluetooth else Icons.Default.Cloud) to Color.Yellow
                else -> (if (navState.backend == "BLUETOOTH") Icons.Default.BluetoothDisabled else Icons.Default.CloudOff) to Color.Red
            }
            
            Icon(
                imageVector = connIcon,
                contentDescription = "Connection Status",
                tint = connColor,
                modifier = Modifier.size(14.dp)
            )

            if (navState.isTrackRecording) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = "Recording",
                    tint = Color.Red,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
fun NavigationPanel(navState: app.organicmaps.wear.NavigationState, isVisible: Boolean = true) {
    val context = LocalContext.current
    val hApp = context.applicationContext as WearApplication

    // Live device heading (radians, magnetic north corrected for screen rotation) from the shared
    // compass. Reuses SensorHelper (SENSOR_DELAY_NORMAL, ~5 Hz — plenty for a turn pointer, low
    // drain) and is held ONLY while this panel is the visible page and the watch is interactive, so
    // the gyro spins down in ambient and when the user swipes to the map or stats page or leaves
    // navigation. SensorHelper is reference-counted, so this never disturbs the map panel's compass.
    var northRad by remember { mutableStateOf(Double.NaN) }
    DisposableEffect(isVisible, navState.isAmbient, hApp.isFullyInitialized) {
        if (!isVisible || navState.isAmbient || !hApp.isFullyInitialized)
            return@DisposableEffect onDispose {}
        val sensors = hApp.organicMaps.sensorHelper
        val listener = SensorListener { north -> northRad = north }
        sensors.addListener(listener); sensors.start()
        onDispose { sensors.removeListener(listener); sensors.stop() }
    }

    // Absolute bearing from the live position to the next turn point minus the device heading =
    // where the arrow must point on the round screen. Null (→ fall back to the fixed maneuver glyph)
    // when any input is missing: no compass yet, no turn point, or no GPS fix. Live position comes
    // from locationHelper, not navState.lat/lon, because the latter is only filled in standalone
    // mode (here we are typically phone-connected and streaming the map).
    val loc = hApp.organicMaps.locationHelper.savedLocation
    val haveTurn = navState.turnLat != 0.0 || navState.turnLon != 0.0
    val targetAngle: Float? = if (!northRad.isNaN() && haveTurn && loc != null) {
        val tb = bearingDeg(loc.latitude, loc.longitude, navState.turnLat, navState.turnLon)
        val headingDeg = (Math.toDegrees(northRad) + 360.0) % 360.0
        (((tb - headingDeg) + 360.0) % 360.0).toFloat()
    } else null

    // Smooth along the shortest angular path so ~5 Hz compass + GPS jitter doesn't snap the arrow
    // and the 359°→0° wrap doesn't spin it the long way round.
    val anim = remember { Animatable(0f) }
    LaunchedEffect(targetAngle) {
        val t = targetAngle ?: return@LaunchedEffect
        var delta = (t - anim.value) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        anim.animateTo(anim.value + delta, animationSpec = tween(300))
    }

    NavigationScreen(
        distanceToNextTurn = navState.distToTurn,
        turnIcon = getTurnIcon(navState.carDirection, navState.pedestrianDirection, navState.exitNum),
        remainingTime = navState.nextStreet,
        onCancelClick = {
            WearCommandService.cancelNavigation(context)
        },
        exitNum = navState.exitNum,
        pointerAngleDeg = if (targetAngle != null) anim.value else null,
    )
}

// Initial-bearing (great-circle) from A to B in degrees clockwise from true north, [0,360).
private fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLon = Math.toRadians(bLon - aLon)
    val la = Math.toRadians(aLat)
    val lb = Math.toRadians(bLat)
    val y = Math.sin(dLon) * Math.cos(lb)
    val x = Math.cos(la) * Math.sin(lb) - Math.sin(la) * Math.cos(lb) * Math.cos(dLon)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}

@Composable
fun getTurnIcon(carDirection: Int, pedestrianDirection: Int, exitNum: Int = 0): ImageVector {
    // If pedestrian direction is not NoTurn/GoStraight, use it
    if (pedestrianDirection != 0 && pedestrianDirection != 1) {
        return when (pedestrianDirection) {
            2 -> Icons.AutoMirrored.Filled.ArrowForward
            3 -> Icons.AutoMirrored.Filled.ArrowBack
            4 -> Icons.Default.Place
            else -> Icons.Default.ArrowUpward
        }
    }

    // Mapping based on app.organicmaps.sdk.routing.CarDirection enum
    return when (carDirection) {
        0, 1, 13 -> Icons.Default.ArrowUpward // NoTurn, GoStraight, StartAtEndOfStreet
        2, 3, 4 -> Icons.AutoMirrored.Filled.ArrowForward // TurnRight variants
        5, 6, 7 -> Icons.AutoMirrored.Filled.ArrowBack // TurnLeft variants
        8, 9 -> Icons.Default.Refresh // UTurn variants (Refresh as fallback)
        10, 11, 12 -> if (exitNum > 0) Icons.Default.Refresh else Icons.Default.Refresh // Roundabout
        14 -> Icons.Default.Place // ReachedYourDestination
        else -> Icons.Default.ArrowUpward
    }
}
