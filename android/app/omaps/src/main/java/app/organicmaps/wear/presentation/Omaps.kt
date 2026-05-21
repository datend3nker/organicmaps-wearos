package app.organicmaps.wear.presentation

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.presentation.navigation.NavigationScreen
import app.organicmaps.wear.presentation.navigation.SensorViewModel
import app.organicmaps.wear.presentation.navigation.StatsScreen
import app.organicmaps.wear.presentation.search.SearchScreen
import app.organicmaps.wear.presentation.theme.OrganicMapsTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.view.KeyEvent

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import app.organicmaps.sdk.settings.RoadType
import app.organicmaps.sdk.routing.RoutingOptions

import androidx.wear.input.WearableButtons

class Omaps : ComponentActivity() {
    private var availableButtons = emptySet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        // Check for available hardware buttons
        val buttonCount = WearableButtons.getButtonCount(this)
        val buttons = mutableSetOf<Int>()
        if (buttonCount > 0) {
            val keyCodes = listOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3)
            keyCodes.forEach { code ->
                if (WearableButtons.getButtonInfo(this, code) != null) {
                    buttons.add(code)
                }
            }
        }
        availableButtons = buttons
        android.util.Log.d("Omaps", "Available hardware buttons: $availableButtons")

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        val missingPermissions = permissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }
        
        // Initialize state from prefs
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        val isMapEnabled = prefs.getBoolean("mapEnabled", false)
        val isOfflineMapsEnabled = prefs.getBoolean("watchLocalMode", false)
        val routerType = prefs.getInt("routerType", 0)
        val poiMask = prefs.getInt("poiCategoriesMask", 0x3F)
        
        val is3dEnabled = prefs.getBoolean("pref_3d", true)
        val is3dBldEnabled = prefs.getBoolean("pref_3d_buildings", true)
        val isAutoZoomEnabled = prefs.getBoolean("pref_auto_zoom", true)
        val units = prefs.getInt("pref_munits", 0)
        val style = prefs.getString("pref_map_style", "default") ?: "default"
        
        val avoidTolls = prefs.getBoolean("avoid_tolls", false)
        val avoidMotorways = prefs.getBoolean("avoid_motorways", false)
        val avoidFerries = prefs.getBoolean("avoid_ferries", false)
        val avoidUnpaved = prefs.getBoolean("avoid_dirty_roads", false)

        val transitEnabled = prefs.getBoolean("transit_enabled", false)
        val bikingEnabled = prefs.getBoolean("biking_enabled", false)
        val hikingEnabled = prefs.getBoolean("hiking_enabled", false)
        val isolinesEnabled = prefs.getBoolean("isolines_enabled", false)

        val lastLat = prefs.getFloat("last_known_lat", 0.0f).toDouble()
        val lastLon = prefs.getFloat("last_known_lon", 0.0f).toDouble()
        
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            mapEnabled = isMapEnabled,
            watchLocalMode = isOfflineMapsEnabled,
            routerType = routerType,
            poiCategoriesMask = poiMask,
            is3dEnabled = is3dEnabled,
            is3dBuildingsEnabled = is3dBldEnabled,
            isAutoZoomEnabled = isAutoZoomEnabled,
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

        // Apply native settings
        try {
            app.organicmaps.sdk.Framework.nativeSetTransitSchemeEnabled(transitEnabled)
            app.organicmaps.sdk.Framework.nativeSetCyclingLayerEnabled(bikingEnabled)
            app.organicmaps.sdk.Framework.nativeSetHikingLayerEnabled(hikingEnabled)
            app.organicmaps.sdk.Framework.nativeSetIsolinesLayerEnabled(isolinesEnabled)
        } catch (_: Throwable) {}
        
        setContent {
            WearApp()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val navState = NavigationStateHolder.state.value
        if (navState.mapEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_NAVIGATE_IN -> {
                    val currentSpan = if (navState.isExploreMode) navState.manualViewSpan else 0.003f
                    val newSpan = (currentSpan * 0.8f).coerceIn(0.0005f, 0.05f)
                    NavigationStateHolder.update(navState.copy(isExploreMode = true, manualViewSpan = newSpan))
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_NAVIGATE_OUT -> {
                    val currentSpan = if (navState.isExploreMode) navState.manualViewSpan else 0.003f
                    val newSpan = (currentSpan * 1.2f).coerceIn(0.0005f, 0.05f)
                    NavigationStateHolder.update(navState.copy(isExploreMode = true, manualViewSpan = newSpan))
                    return true
                }
                KeyEvent.KEYCODE_STEM_1 -> {
                    // STEM_1 usually opens menu. Handled in MapPanel, but we capture it here too.
                    return true
                }
                KeyEvent.KEYCODE_STEM_2 -> {
                    NavigationStateHolder.update(navState.copy(
                        isExploreMode = !navState.isExploreMode,
                        manualCenterLat = if (!navState.isExploreMode) navState.lat else navState.manualCenterLat,
                        manualCenterLon = if (!navState.isExploreMode) navState.lon else navState.manualCenterLon,
                        manualViewSpan = 0.003f
                    ))
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (navState.isExploreMode) {
                        NavigationStateHolder.update(navState.copy(isExploreMode = false))
                        return true
                    }
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
    
    val pagerState = remember(isNavigating, isMapEnabled) {
        PagerState(
            pageCount = { 
                if (isNavigating) {
                    if (isMapEnabled) 3 else 2
                } else {
                    if (isMapEnabled) 4 else 3
                }
            }
        )
    }

    androidx.compose.runtime.LaunchedEffect(navState.openMapManager) {
        if (navState.openMapManager && !isNavigating) {
            pagerState.animateScrollToPage(if (isMapEnabled) 2 else 1)
            NavigationStateHolder.update(navState.copy(openMapManager = false))
        }
    }

    androidx.compose.runtime.LaunchedEffect(isNavigating) {
        if (isNavigating && isMapEnabled) {
            pagerState.scrollToPage(0)
        }
    }

    OrganicMapsTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isNavigating) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !navState.isExploreMode
                ) { page ->
                    if (isMapEnabled) {
                        when (page) {
                            0 -> MapPanel()
                            1 -> SearchScreen()
                            2 -> app.organicmaps.wear.presentation.downloads.MapManagerScreen()
                            3 -> app.organicmaps.wear.presentation.settings.SettingsScreen()
                        }
                    } else {
                        when (page) {
                            0 -> SearchScreen()
                            1 -> app.organicmaps.wear.presentation.downloads.MapManagerScreen()
                            2 -> app.organicmaps.wear.presentation.settings.SettingsScreen()
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !navState.isExploreMode
                ) { page ->
                    if (isMapEnabled) {
                        when (page) {
                            0 -> MapPanel()
                            1 -> NavigationPanel(navState)
                            2 -> StatsScreen(navState)
                        }
                    } else {
                        when (page) {
                            0 -> NavigationPanel(navState)
                            1 -> StatsScreen(navState)
                        }
                    }
                }
            }
            
            // Status Indicators (Anchored for circular safety)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 45.dp, start = 45.dp) // Pushed inward for round watches
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (navState.watchLocalMode) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Offline Mode",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (navState.watchLocalMode && !navState.isPhoneConnected) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (!navState.isPhoneConnected) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Disconnected",
                            tint = Color.Red,
                            modifier = Modifier.size(14.dp)
                        )
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
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val options = listOf(
                                Icons.Default.Paid to RoadType.Toll,
                                Icons.Default.DirectionsCar to RoadType.Motorway,
                                Icons.Default.DirectionsBoat to RoadType.Ferry,
                                Icons.Default.Terrain to RoadType.Dirty
                            )
                            options.forEach { (icon, type) ->
                                val isChecked = when(type) {
                                    RoadType.Toll -> navState.avoidTolls
                                    RoadType.Motorway -> navState.avoidMotorways
                                    RoadType.Ferry -> navState.avoidFerries
                                    RoadType.Dirty -> navState.avoidUnpaved
                                    else -> false
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isChecked) Color.Red.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.15f))
                                        .clickable {
                                            val newVal = !isChecked
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
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (isChecked) Color.White else Color.LightGray)
                                }
                            }
                        }
                        
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
                            onClick = {
                                if (navState.standaloneMode || navState.watchLocalMode) {
                                    app.organicmaps.sdk.routing.RoutingController.get().cancel()
                                } else {
                                    WearCommandService.stopNavigation(context)
                                }
                                NavigationStateHolder.update(navState.copy(isActive = false, lastRouteError = 0), force = true)
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Cancel", style = MaterialTheme.typography.caption2) },
                            modifier = Modifier.width(100.dp).height(32.dp)
                        )
                    }
                }
            }

            MapDownloadOverlay()
        }
    }
}

@Composable
fun NavigationPanel(navState: app.organicmaps.wear.NavigationState) {
    val context = LocalContext.current
    val sensorViewModel: SensorViewModel = viewModel()
    val deviceRotation by sensorViewModel.heading.collectAsState()
    
    NavigationScreen(
        distanceToNextTurn = navState.distToTurn,
        turnIcon = getTurnIcon(navState.carDirection, navState.pedestrianDirection), 
        remainingTime = navState.nextStreet,
        onCancelClick = { 
            app.organicmaps.sdk.routing.RoutingController.get().cancel()
            if (!navState.standaloneMode) {
                WearCommandService.stopNavigation(context)
            }
            NavigationStateHolder.update(navState.copy(isActive = false, isNavigating = false), force = true)
        },
        deviceRotation = deviceRotation,
        exitNum = navState.exitNum
    )
}

@Composable
fun getTurnIcon(carDirection: Int, pedestrianDirection: Int): ImageVector {
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
        10, 11, 12 -> Icons.Default.Refresh // Roundabout
        14 -> Icons.Default.Place // ReachedYourDestination
        else -> Icons.Default.ArrowUpward
    }
}
