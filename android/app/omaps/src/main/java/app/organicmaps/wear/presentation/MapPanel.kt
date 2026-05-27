package app.organicmaps.wear.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import app.organicmaps.sdk.R as SdkR
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.LocalAmbientMode
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.NavigationIcons
import app.organicmaps.wear.WearMapDownloader
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import app.organicmaps.wear.presentation.search.PlacePage
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.navigation.SensorViewModel

import androidx.compose.foundation.isSystemInDarkTheme
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.bookmarks.data.Metadata
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.routing.RoutingController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

import androidx.wear.compose.material.dialog.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import app.organicmaps.sdk.MapView
import app.organicmaps.sdk.Map
import app.organicmaps.sdk.PlacePageActivationListener
import app.organicmaps.sdk.widget.placepage.PlacePageData

@Composable
fun MapPanel(modifier: Modifier = Modifier, mainViewModel: MainViewModel = viewModel(), isVisible: Boolean = true) {
    val context = LocalContext.current
    val isAmbient = LocalAmbientMode.current
    val app = context.applicationContext as WearApplication
    val navState by NavigationStateHolder.state.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = remember(navState.mapStyle, isSystemDark) {
        when (navState.mapStyle) {
            "night" -> true
            "default" -> false
            else -> isSystemDark
        }
    }
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        if (isVisible) focusRequester.requestFocus()
    }
    
    var showQuickMenu by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<SearchResultItem?>(null) }

    val effectiveLat = if (navState.isMapUnlocked) navState.manualCenterLat else navState.lat
    val effectiveLon = if (navState.isMapUnlocked) navState.manualCenterLon else navState.lon
    
    val initialLat = remember {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        prefs.getFloat("last_known_lat", 48.2082f).toDouble()
    }
    val initialLon = remember {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        prefs.getFloat("last_known_lon", 16.3738f).toDouble()
    }

    var lastValidLat by remember { mutableStateOf(if (navState.lat != 0.0) navState.lat else initialLat) }
    var lastValidLon by remember { mutableStateOf(if (navState.lon != 0.0) navState.lon else initialLon) }
    
    LaunchedEffect(navState.lat, navState.lon) {
        if (navState.lat != 0.0) {
            lastValidLat = navState.lat
            lastValidLon = navState.lon
        }
    }
    
    val currentLat = if (navState.isMapUnlocked) effectiveLat else lastValidLat
    val currentLon = if (navState.isMapUnlocked) effectiveLon else lastValidLon

    // OPTIMIZATION: Check if map data is present for current viewport
    var isMapDownloaded by remember { mutableStateOf(true) }
    var isWorldMapPresent by remember { mutableStateOf(true) }
    LaunchedEffect(currentLat, currentLon, navState.watchLocalMode, app.isFullyInitialized) {
        if (app.isFullyInitialized) {
            delay(1000) // Throttle checks for power efficiency
            isMapDownloaded = withContext(Dispatchers.Default) {
                Framework.nativeIsDownloadedMapAtLocation(currentLat, currentLon)
            }
            isWorldMapPresent = withContext(Dispatchers.Default) {
                MapManager.nativeGetStatus("World") == CountryItem.STATUS_DONE
            }
        }
    }

    val targetViewSpan = remember(navState.speedMps, navState.routerType, navState.isActive, navState.isMapUnlocked, navState.manualViewSpan) {
        if (navState.isMapUnlocked) return@remember navState.manualViewSpan.toDouble()
        val vs = 1.0 
        val speedKmpH = if (navState.speedMps >= 0) navState.speedMps * 3.6 else 0.0
        val scales2d = listOf(20.0 to 0.70, 40.0 to 1.25, 60.0 to 2.25, 75.0 to 3.00, 85.0 to 3.75, 95.0 to 6.00)
        val baseScale = when {
            navState.routerType == 1 -> 0.70 
            speedKmpH <= scales2d.first().first -> scales2d.first().second
            speedKmpH >= scales2d.last().first -> scales2d.last().second
            else -> {
                var idx = 1
                while (idx < scales2d.size && scales2d[idx].first < speedKmpH) idx++
                val s1 = scales2d[idx-1]
                val s2 = scales2d[idx]
                val k = (speedKmpH - s1.first) / (s2.first - s1.first)
                s1.second + k * (s2.second - s1.second)
            }
        } / vs
        (1000.0 * baseScale) / 111000.0
    }
    
    val viewSpan by animateFloatAsState(
        targetValue = targetViewSpan.toFloat(),
        animationSpec = if (navState.isMapUnlocked) tween(80) else tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "zoom"
    )
    val clampedViewSpan = viewSpan.coerceAtLeast(0.0001f)

    val scope = rememberCoroutineScope()

    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive, navState.isMapUnlocked, app.isFullyInitialized) {
        if (!app.isFullyInitialized) return@LaunchedEffect
        
        val targetDeg = when {
            navState.isMapUnlocked -> 0f
            navState.isActive && navState.speedMps > 2.0f && navState.bearing >= 0f -> -navState.bearing
            else -> -compassHeading
        }
        var diff = targetDeg - sensorViewModel.mapRotationAnimatable.value
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        sensorViewModel.mapRotationAnimatable.animateTo(
            targetValue = sensorViewModel.mapRotationAnimatable.value + diff,
            animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)
        )
        Map.onCompassUpdated(sensorViewModel.mapRotationAnimatable.value.toDouble(), true)
    }

    DisposableEffect(app.isFullyInitialized) {
        if (!app.isFullyInitialized) return@DisposableEffect onDispose {}
        
        val listener = object : PlacePageActivationListener {
            override fun onPlacePageActivated(data: PlacePageData) {
                if (data is MapObject) {
                    tappedDestination = SearchResultItem(
                        name = data.title,
                        description = if (data.subtitle.isNotEmpty()) data.subtitle else "Dropped Pin",
                        lat = data.lat,
                        lon = data.lon,
                        type = 2,
                        openingHours = data.getMetadata(Metadata.MetadataType.FMD_OPEN_HOURS),
                        website = data.getMetadata(Metadata.MetadataType.FMD_WEBSITE),
                        phone = data.getMetadata(Metadata.MetadataType.FMD_PHONE_NUMBER),
                        address = data.address,
                        cuisine = data.getMetadata(Metadata.MetadataType.FMD_CUISINE),
                        operator = data.getMetadata(Metadata.MetadataType.FMD_OPERATOR),
                        brand = data.getMetadata(Metadata.MetadataType.FMD_BRAND),
                        stars = data.getMetadata(Metadata.MetadataType.FMD_STARS)
                    )
                }
            }
            override fun onPlacePageDeactivated() {
                tappedDestination = null
            }
        }
        Framework.nativePlacePageActivationListener(listener)
        onDispose {
            Framework.nativeRemovePlacePageActivationListener(listener)
        }
    }

    // OPTIMIZATION: Handle location follow mode natively instead of setting viewport manually
    LaunchedEffect(navState.isMapUnlocked, navState.isActive, app.isFullyInitialized) {
        if (!app.isFullyInitialized) return@LaunchedEffect
        
        if (!navState.isMapUnlocked) {
            // When locked, native following is handled via nativeSetViewportCenter or internal auto-follow
        } else {
            // When unlocked, stop native following
            Framework.nativeStopLocationFollow()
        }
    }

    Box(
        modifier = Modifier.then(modifier).fillMaxSize().clipToBounds().background(if (isAmbient) Color.Black else if (isDark) Color(0xFF1B1B1B) else Color(0xFFF1EEE8)).focusRequester(focusRequester)
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_1) { showQuickMenu = true; true }
                else if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_2) {
                    val current = NavigationStateHolder.state.value
                    NavigationStateHolder.update(current.copy(isMapUnlocked = !current.isMapUnlocked, manualCenterLat = if (!current.isMapUnlocked) currentLat else current.manualCenterLat, manualCenterLon = if (!current.isMapUnlocked) currentLon else current.manualCenterLon, manualViewSpan = viewSpan, lastSettingsInteractionTime = System.currentTimeMillis()))
                    true
                } else false
            }
            .onRotaryScrollEvent {
                if (navState.isMapUnlocked) {
                    if (it.verticalScrollPixels > 0) Map.zoomOut() else Map.zoomIn()
                } else {
                    val currentState = NavigationStateHolder.state.value
                    val factor = if (it.verticalScrollPixels > 0) 1.25f else 0.75f
                    val currentSpan = viewSpan
                    val newSpan = (currentSpan * factor).coerceIn(0.0001f, 0.05f)
                    NavigationStateHolder.update(currentState.copy(isMapUnlocked = true, isMapUnlockedBeforeNav = currentState.isMapUnlocked, manualViewSpan = newSpan, manualCenterLat = if (currentState.manualCenterLat == 0.0) currentLat else currentState.manualCenterLat, manualCenterLon = if (currentState.manualCenterLon == 0.0) currentLon else currentState.manualCenterLon, lastSettingsInteractionTime = System.currentTimeMillis()))
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        if (!app.isFullyInitialized) {
            CircularProgressIndicator()
        } else {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        getMap().setLocationHelper(app.organicMaps.locationHelper)
                    }
                },
                update = { mapView ->
                    // Allow interactions only when map is unlocked
                    mapView.isEnabled = navState.isMapUnlocked
                    mapView.isClickable = navState.isMapUnlocked
                    
                    val map = mapView.getMap()
                    // OPTIMIZATION: Pause rendering when not visible to save power
                    if (!isVisible || isAmbient) map.onPause() else map.onResume()
                    
                    if (navState.isMapUnlocked) {
                        // Manual control - sync only if changed significantly or initializing
                        // This is handled by native gestures when unlocked
                    } else {
                        // Locked to position - native engine handles this, 
                        // but we can force a sync on mode switch or large jumps
                        val zoom = (log2(360.0 / (clampedViewSpan * 2.0)).toInt()).coerceIn(1, 19)
                        Framework.nativeSetViewportCenter(currentLat, currentLon, zoom)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        if (navState.isActive && navState.distToTurn.isNotEmpty() && !navState.isMapUnlocked) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 35.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) { Icon(painter = painterResource(id = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection, navState.exitNum)), contentDescription = null, modifier = Modifier.fillMaxSize(), tint = Color.White) }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = navState.distToTurn, style = MaterialTheme.typography.title3.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                        if (navState.nextStreet.isNotEmpty()) Text(text = navState.nextStreet, style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                    }
                }
            }
        }

        if (navState.isMapUnlocked) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (navState.isRouteBuilt && !navState.isNavigating) 60.dp else 24.dp)) {
                androidx.wear.compose.material.CompactChip(onClick = { NavigationStateHolder.update { it.copy(isMapUnlocked = false, lastSettingsInteractionTime = System.currentTimeMillis()) } }, label = { Text("Recenter") }, icon = { Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp)) }, colors = ChipDefaults.secondaryChipColors())
            }
        }

        if (navState.isEffectivelyStandalone && navState.lat == 0.0 && !isAmbient) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(6.dp)); Text("Searching for GPS...", style = MaterialTheme.typography.caption3, color = Color.White) }
            }
        }
        
        if (navState.isRouteBuilt && !navState.isNavigating) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (navState.isMapUnlocked) 60.dp else 24.dp)) {
                androidx.wear.compose.material.Button(onClick = { RoutingController.get().start() }, modifier = Modifier.height(40.dp).fillMaxWidth(0.5f), colors = ButtonDefaults.primaryButtonColors()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Start") }
                }
            }
        }

        // MAP MISSING NOTIFICATION
        if ((!isMapDownloaded || !isWorldMapPresent) && app.isFullyInitialized && !isAmbient) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (!isWorldMapPresent) Icons.Default.Warning else Icons.Default.Map,
                        contentDescription = null,
                        tint = if (!isWorldMapPresent) Color.Yellow else Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (!isWorldMapPresent) "Missing World Map" else "No Local Map Data",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (!isWorldMapPresent) "(Required for rendering)" else "(Pan to center or sync)",
                        style = MaterialTheme.typography.caption3,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        androidx.wear.compose.material.CompactChip(
                            onClick = {
                                NavigationStateHolder.update { it.copy(openMapManager = true) }
                            },
                            label = {
                                Text("Manage", style = MaterialTheme.typography.caption3)
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.height(28.dp).weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        androidx.wear.compose.material.CompactChip(
                            onClick = {
                                scope.launch {
                                    if (!isWorldMapPresent) {
                                        WearMapDownloader.downloadOrStreamMap(context, "World", "")
                                    } else {
                                        val countryId = withContext(Dispatchers.Default) {
                                            MapManager.nativeFindCountry(currentLat, currentLon)
                                        }
                                        if (countryId != null && countryId.isNotEmpty()) {
                                            WearMapDownloader.downloadOrStreamMap(context, countryId, "")
                                        } else {
                                            NavigationStateHolder.update { it.copy(openMapManager = true) }
                                        }
                                    }
                                }
                            },
                            label = {
                                Text(if (!isWorldMapPresent) "Get World" else "Sync Local", style = MaterialTheme.typography.caption3)
                            },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.height(28.dp).weight(1f)
                        )
                    }
                }
            }
        }
        
        if (showQuickMenu) QuickMenu(currentLat = currentLat, currentLon = currentLon, viewSpan = viewSpan, onDismiss = { showQuickMenu = false })
        if (tappedDestination != null) {
            Dialog(showDialog = true, onDismissRequest = { tappedDestination = null }) {
                PlacePage(result = tappedDestination!!, onNavigate = { routerType, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved ->
                    scope.launch {
                        if (navState.standaloneMode || (!navState.isPhoneConnected && navState.watchLocalMode)) {
                            if (app.isFullyInitialized && !Framework.nativeIsDownloadedMapAtLocation(tappedDestination!!.lat, tappedDestination!!.lon)) { android.widget.Toast.makeText(context, "Map not downloaded for destination", android.widget.Toast.LENGTH_LONG).show(); return@launch }
                            try {
                                app.waitForInitializationSuspend()
                                NavigationStateHolder.update { it.copy(isActive = true, isNavigating = false, routeBuildProgress = 0, isRouteBuilding = true, isRouteReady = false, routePoints = emptyList(), lastRouteError = 0, isMapUnlockedBeforeNav = it.isMapUnlocked, isMapUnlocked = true) }
                                val startPoint = app.organicMaps.locationHelper.myPosition ?: app.organicMaps.locationHelper.savedLocation?.let { MapObject.createMapObject(MapObject.MY_POSITION, "My Location", "", it.latitude, it.longitude) } ?: let { val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE); val lastLat = prefs.getFloat("last_known_lat", 0f).toDouble(); val lastLon = prefs.getFloat("last_known_lon", 0f).toDouble(); if (lastLat != 0.0) MapObject.createMapObject(MapObject.MY_POSITION, "Previous Fix", "", lastLat, lastLon) else null }
                                if (startPoint == null) { android.util.Log.e("MapPanel", "No GPS position for routing"); android.widget.Toast.makeText(context, "No GPS position for routing", android.widget.Toast.LENGTH_LONG).show(); NavigationStateHolder.update { it.copy(isRouteBuilding = false) }; return@launch }
                                val destination = MapObject.createMapObject(MapObject.POI, tappedDestination!!.name, tappedDestination!!.description, tappedDestination!!.lat, tappedDestination!!.lon)
                                val router = when (routerType) { 0 -> Router.Vehicle; 1 -> Router.Pedestrian; 2 -> Router.Bicycle; else -> Router.Transit }; val controller = RoutingController.get(); controller.prepare(startPoint, destination, router); controller.checkAndBuildRoute()
                                NavigationStateHolder.update { it.copy(distToTurn = "", nextStreet = "", distToTarget = "", eta = 0, completionPercent = 0.0, turnLat = 0.0, turnLon = 0.0, isMapUnlocked = false, avoidTolls = avoidTolls, avoidMotorways = avoidMotorways, avoidFerries = avoidFerries, avoidUnpaved = avoidUnpaved) }
                            } catch (e: Exception) { android.util.Log.e("MapPanel", "Route planning failed: ${e.message}"); android.widget.Toast.makeText(context, "Routing failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show(); NavigationStateHolder.update { it.copy(isRouteBuilding = false) } }
                        } else { WearCommandService.selectSearchResult(context, tappedDestination!!, routerType); NavigationStateHolder.update { it.copy(isActive = true, isNavigating = false, destinationName = tappedDestination!!.name, isMapUnlockedBeforeNav = it.isMapUnlocked, isMapUnlocked = true, isRouteBuilding = true) } }
                        tappedDestination = null
                    }
                }, onDismiss = { val state = NavigationStateHolder.state.value; if (state.isRouteBuilding || (state.isActive && !state.isNavigating)) { RoutingController.get().cancel(); NavigationStateHolder.update(state.copy(isRouteBuilding = false, isActive = false, isMapUnlocked = state.isMapUnlockedBeforeNav), force = true) } else { NavigationStateHolder.update(state.copy(isRouteBuilding = false), force = true) }; tappedDestination = null })
            }
        }
    }
}

@Composable
fun QuickMenu(currentLat: Double, currentLon: Double, viewSpan: Float, onDismiss: () -> Unit) {
    androidx.wear.compose.material.dialog.Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val navState by NavigationStateHolder.state.collectAsState(); val context = LocalContext.current
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)) {
            item { Text("Quick Menu", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF)) }
            item { Chip(onClick = { Map.zoomIn(); onDismiss() }, label = { Text("Zoom In") }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { Chip(onClick = { Map.zoomOut(); onDismiss() }, label = { Text("Zoom Out") }, icon = { Icon(Icons.Default.Remove, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { Chip(onClick = { NavigationStateHolder.update { it.copy(isMapUnlocked = false, lastSettingsInteractionTime = System.currentTimeMillis()) }; onDismiss() }, label = { Text("Follow Position") }, icon = { Icon(Icons.Default.MyLocation, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { ToggleChip(checked = !navState.isMapUnlocked, onCheckedChange = { newVal -> val current = NavigationStateHolder.state.value; NavigationStateHolder.update(current.copy(isMapUnlocked = !newVal, manualCenterLat = if (current.lat != 0.0) current.lat else currentLat, manualCenterLon = if (current.lon != 0.0) current.lon else currentLon, manualViewSpan = 0.003f, lastSettingsInteractionTime = System.currentTimeMillis())); onDismiss() }, label = { Text("Follow Position") }, toggleControl = { Switch(checked = !navState.isMapUnlocked) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
            item { Chip(onClick = onDismiss, label = { Text("Close") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        }
    }
}
