package app.organicmaps.wear.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
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
import app.organicmaps.sdk.R
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.MapTileKey
import app.organicmaps.wear.MapTileStateHolder
import app.organicmaps.wear.Mercator
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.ParsedMapTile
import app.organicmaps.wear.MapFeaturePath
import app.organicmaps.wear.MapFeaturePoint
import app.organicmaps.wear.LocalAmbientMode
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.NavigationIcons
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import app.organicmaps.wear.presentation.search.PlacePage
import android.view.KeyCharacterMap
import android.content.pm.PackageManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.navigation.SensorViewModel

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.presentation.search.ModeSelectionScreen
import app.organicmaps.sdk.bookmarks.data.MapObject
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

@Composable
fun MapPanel(modifier: Modifier = Modifier, mainViewModel: MainViewModel = viewModel()) {
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
    val streamedTile by MapTileStateHolder.mapTile.collectAsState()
    val allTiles by MapTileStateHolder.cachedTilesFlow.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()
    val focusRequester = remember { FocusRequester() }
    
    var showQuickMenu by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<SearchResultItem?>(null) }

    val effectiveLat = if (navState.isExploreMode) navState.manualCenterLat else navState.lat
    val effectiveLon = if (navState.isExploreMode) navState.manualCenterLon else navState.lon
    
    // PERSISTENT VALID LOCATION to prevent flickering on 0.0 jumps
    var lastValidLat by remember { mutableStateOf(if (navState.lat != 0.0) navState.lat else 48.2082) }
    var lastValidLon by remember { mutableStateOf(if (navState.lon != 0.0) navState.lon else 16.3738) }
    
    LaunchedEffect(navState.lat, navState.lon) {
        if (navState.lat != 0.0) {
            lastValidLat = navState.lat
            lastValidLon = navState.lon
        }
    }
    
    val currentLat = if (navState.isExploreMode) effectiveLat else lastValidLat
    val currentLon = if (navState.isExploreMode) effectiveLon else lastValidLon

    val requestKeys = remember { mutableMapOf<Long, MapTileKey>() }

    // Interaction is now allowed whenever NOT actively building a route
    val canInteract = !navState.isRouteBuilding
    
    // Auto-disable explore mode if navigation starts in companion mode and we want to follow
    // But allow user to re-enable it manually.
    LaunchedEffect(navState.isActive, navState.standaloneMode) {
        if (navState.isActive && !navState.standaloneMode && navState.isExploreMode && navState.lastSettingsInteractionTime < System.currentTimeMillis() - 5000) {
            // Only auto-disable if no recent user interaction
            // NavigationStateHolder.update(navState.copy(isExploreMode = false))
        }
    }

    // IMPROVED AUTO-ZOOM (Phone App Logic)
    val targetViewSpan = remember(navState.speedMps, navState.distToTurnMeters, navState.routerType, navState.isActive, navState.isExploreMode, navState.manualViewSpan) {
        if (navState.isExploreMode) return@remember navState.manualViewSpan.toDouble()
        if (navState.routerType != 0 || !navState.isActive) return@remember 0.003
        
        val speedKmH = navState.speedMps * 3.6
        val speedSpan = when {
            speedKmH < 30.0 -> 0.002
            speedKmH < 70.0 -> 0.004
            speedKmH < 100.0 -> 0.006
            else -> 0.010
        }
        
        val turnDist = navState.distToTurnMeters
        val targetSpan = if (turnDist > 0.0 && turnDist < 250.0) {
            0.0015
        } else if (turnDist > 0.0 && turnDist < 500.0) {
            0.002
        } else if (turnDist > 3000.0) {
            maxOf(speedSpan, 0.008)
        } else {
            speedSpan
        }
        targetSpan
    }
    
    val viewSpan by animateFloatAsState(
        targetValue = targetViewSpan.toFloat(),
        animationSpec = if (navState.isExploreMode) tween(80) else tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "zoom"
    )

    // Fix: Clamp viewSpan to avoid 0.0 or negative values in calculations
    val clampedViewSpan = viewSpan.coerceAtLeast(0.0001f)

    // Corrected scale calculation: normalize viewSpan (degrees) to world range (~360)
    val currentScale = remember(clampedViewSpan) {
        (log2(360.0 / (clampedViewSpan * 2.0)).toInt() + 1).coerceIn(1, 18)
    }

    var isUsingGpsBearing by remember { mutableStateOf(false) }

    // SAFE STABILIZED ROTATION with Speed-based Hysteresis
    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive, navState.isExploreMode) {
        if (navState.speedMps > 2.0f) {
            isUsingGpsBearing = true
        } else if (navState.speedMps < 0.8f || navState.bearing < 0f) {
            isUsingGpsBearing = false
        }
        val targetDeg = when {
            navState.isExploreMode -> 0f
            navState.isActive && isUsingGpsBearing && navState.bearing >= 0f -> -navState.bearing
            else -> -compassHeading
        }
        
        var diff = targetDeg - sensorViewModel.mapRotationAnimatable.value
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        
        sensorViewModel.mapRotationAnimatable.animateTo(
            targetValue = sensorViewModel.mapRotationAnimatable.value + diff,
            animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)
        )
    }

    val verticalOffsetFractionTarget = if (navState.routerType == 0 && navState.isActive && !navState.isExploreMode) 0.25f else 0.0f
    val verticalOffsetFraction by animateFloatAsState(
        targetValue = verticalOffsetFractionTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offset"
    )

    val useOfflineMaps = navState.watchLocalMode || !navState.isPhoneConnected || navState.standaloneMode

    var isMapDownloaded by remember { mutableStateOf(true) }
    LaunchedEffect(currentLat, currentLon, useOfflineMaps) {
        if (useOfflineMaps && app.isFullyInitialized) {
            isMapDownloaded = Framework.nativeIsDownloadedMapAtLocation(currentLat, currentLon)
        } else {
            isMapDownloaded = true
        }
    }

    // Hide POIs while navigating/planning as target is already set
    val effectivePoiMask = if (navState.isActive) 0 else navState.poiCategoriesMask

    val scope = rememberCoroutineScope()

    // Pre-load POI icons
    val poiIcons = mapOf(
        100 to painterResource(id = R.drawable.ic_bookmark_food),
        101 to painterResource(id = R.drawable.ic_bookmark_cafe),
        102 to painterResource(id = R.drawable.ic_bookmark_hotel),
        103 to painterResource(id = R.drawable.ic_bookmark_money),
        107 to painterResource(id = R.drawable.ic_bookmark_parking),
        108 to painterResource(id = R.drawable.ic_bookmark_mountain),
        109 to painterResource(id = R.drawable.ic_bookmark_park),
        111 to painterResource(id = R.drawable.ic_bookmark_transport),
        112 to painterResource(id = R.drawable.ic_bookmark_transport),
        113 to painterResource(id = R.drawable.ic_bookmark_airport)
    )

    // Hardware button detection
    val isEmulator = remember {
        android.os.Build.FINGERPRINT.contains("generic") ||
        android.os.Build.MODEL.contains("google_sdk") ||
        android.os.Build.MODEL.contains("Emulator") ||
        android.os.Build.DEVICE.contains("generic") ||
        android.os.Build.HARDWARE.contains("goldfish") ||
        android.os.Build.HARDWARE.contains("ranchu") ||
        android.os.Build.PRODUCT.contains("sdk_gwear")
    }
    // Sync manual center when map is locked to avoid "jumps"
    LaunchedEffect(navState.isExploreMode) {
        if (!navState.isExploreMode) {
            NavigationStateHolder.update { it.copy(
                manualCenterLat = navState.lat,
                manualCenterLon = navState.lon
            ) }
        }
    }

    // Performance: Quantized viewSpan for requests to improve responsiveness
    var localRequestLat by remember { mutableStateOf(currentLat) }
    var localRequestLon by remember { mutableStateOf(currentLon) }
    var localRequestSpan by remember { mutableStateOf(clampedViewSpan) }

    LaunchedEffect(currentLat, currentLon, clampedViewSpan, verticalOffsetFraction) {
        delay(100)
        
        // Calculate the visual center of the screen in lat/lon
        // currentLat/Lon is where the user marker is (if VerticalOffset is active) or the map center
        // VerticalOffset shifts the map DOWN, so the "visual center" is slightly ABOVE the marker
        // We need to account for rotation as well for the loading center
        val screenCenterLat = if (!navState.isExploreMode && navState.isActive) {
            val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
            currentLat + (verticalOffsetFraction * clampedViewSpan * 2.0 * cos(rotationRad))
        } else currentLat
        
        val screenCenterLon = if (!navState.isExploreMode && navState.isActive) {
            val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
            currentLon - (verticalOffsetFraction * clampedViewSpan * 2.0 * sin(rotationRad))
        } else currentLon
        
        if (abs(screenCenterLat - localRequestLat) > clampedViewSpan * 0.3 || 
            abs(screenCenterLon - localRequestLon) > clampedViewSpan * 0.3 ||
            abs(clampedViewSpan - localRequestSpan) / localRequestSpan > 0.2) {
            localRequestLat = screenCenterLat
            localRequestLon = screenCenterLon
            localRequestSpan = clampedViewSpan
        }
    }

    // PERFORMANCE: Limited dispatcher for JNI calls to avoid lagginess during roaming
    val jniDispatcher = remember { Dispatchers.Default.limitedParallelism(4) }

    // SMART PRE-FETCHING LOGIC
    LaunchedEffect(localRequestLat, localRequestLon, localRequestSpan, useOfflineMaps, effectivePoiMask) {
        val currentKey = MapTileKey(Mercator.lonToTileX(localRequestLon, 16), Mercator.latToTileY(localRequestLat, 16))
        
        // Always load around center to prevent gaps
        val grid = mutableListOf<MapTileKey>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                grid.add(MapTileKey(currentKey.x + dx, currentKey.y + dy))
            }
        }

        grid.forEach { key ->
            if (MapTileStateHolder.getCachedTileByKey(key) == null) {
                launch(jniDispatcher) {
                    if (app.isFullyInitialized) {
                        val tileLeftLon = Mercator.tileXToLon(key.x, 16)
                        val tileTopLat = Mercator.tileYToLat(key.y, 16)
                        val tileRightLon = Mercator.tileXToLon(key.x + 1, 16)
                        val tileBottomLat = Mercator.tileYToLat(key.y + 1, 16)
                        
                        val localFeatures = Framework.nativeGetWearMapFeatures(
                            minOf(tileTopLat, tileBottomLat) - 0.001, tileLeftLon - 0.001,
                            maxOf(tileTopLat, tileBottomLat) + 0.001, tileRightLon + 0.001,
                            currentScale, navState.routerType, effectivePoiMask
                        )
                        if (localFeatures.isNotEmpty()) {
                            val parsed = MapTileStateHolder.parseTile(localFeatures, key, 1000f, 1000f)
                            withContext(Dispatchers.Main) {
                                MapTileStateHolder.updateCache(key, parsed)
                            }
                            return@launch
                        }
                    }
                    
                    if (!useOfflineMaps || navState.isPhoneConnected) {
                        val requestId = System.nanoTime()
                        requestKeys[requestId] = key
                        WearCommandService.requestMapTile(context, requestId, 0.0, 0.0, 0.0, 0.0, navState.routerType, effectivePoiMask)
                    }
                }
            }
        }
    }

    LaunchedEffect(streamedTile) {
        val tile = streamedTile ?: return@LaunchedEffect
        val key = requestKeys[tile.requestId] ?: MapTileKey(Mercator.lonToTileX(currentLon, 16), Mercator.latToTileY(currentLat, 16))
        
        scope.launch(Dispatchers.Default) {
            val parsed = MapTileStateHolder.parseTile(tile.features, key, 1000f, 1000f)
            withContext(Dispatchers.Main) {
                MapTileStateHolder.updateCache(key, parsed)
                requestKeys.remove(tile.requestId)
            }
        }
    }

    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .clipToBounds()
            .background(if (isAmbient) Color.Black else if (isDark) Color(0xFF1B1B1B) else Color(0xFFF1EEE8)) // Dynamic background
            .focusRequester(focusRequester)
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_1) {
                    showQuickMenu = true
                    true
                } else if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_2) {
                    NavigationStateHolder.update(navState.copy(
                        isExploreMode = !navState.isExploreMode,
                        manualCenterLat = if (!navState.isExploreMode) currentLat else navState.manualCenterLat,
                        manualCenterLon = if (!navState.isExploreMode) currentLon else navState.manualCenterLon,
                        manualViewSpan = viewSpan,
                        lastSettingsInteractionTime = System.currentTimeMillis()
                    ))
                    true
                } else false
            }
            .onRotaryScrollEvent {
                if (canInteract) {
                    val factor = if (it.verticalScrollPixels > 0) 1.25f else 0.75f
                    val currentSpan = if (navState.isExploreMode) navState.manualViewSpan else viewSpan
                    val newSpan = (currentSpan * factor).coerceIn(0.0001f, 0.05f)
                    NavigationStateHolder.update(navState.copy(
                        isExploreMode = true,
                        manualViewSpan = newSpan,
                        manualCenterLat = if (!navState.isExploreMode) currentLat else navState.manualCenterLat,
                        manualCenterLon = if (!navState.isExploreMode) currentLon else navState.manualCenterLon,
                        lastSettingsInteractionTime = System.currentTimeMillis()
                    ))
                    true
                } else false
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (canInteract) {
                        if (zoom != 1f) {
                            val currentSpan = if (navState.isExploreMode) navState.manualViewSpan else viewSpan
                            val newSpan = (currentSpan / zoom).coerceIn(0.0001f, 0.05f)
                            NavigationStateHolder.update(navState.copy(
                                isExploreMode = true,
                                manualViewSpan = newSpan,
                                manualCenterLat = if (!navState.isExploreMode) currentLat else navState.manualCenterLat,
                                manualCenterLon = if (!navState.isExploreMode) currentLon else navState.manualCenterLon,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            ))
                        }
                        if (navState.isExploreMode && (pan.x != 0f || pan.y != 0f)) {
                            val latStep = (pan.y / size.height) * (viewSpan * 2)
                            val lonStep = -(pan.x / size.width) * (viewSpan * 2)
                            NavigationStateHolder.update(navState.copy(
                                manualCenterLat = navState.manualCenterLat + latStep.toDouble(),
                                manualCenterLon = navState.manualCenterLon + lonStep.toDouble(),
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            ))
                        }
                    }
                }
            }
            .pointerInput(viewSpan, currentLat, currentLon, navState.standaloneMode, navState.isPhoneConnected, allTiles) {
                detectTapGestures(
                    onDoubleTap = {
                        // Double-tap to re-center (Force Lock)
                        NavigationStateHolder.update(navState.copy(
                            isExploreMode = false,
                            manualCenterLat = navState.lat,
                            manualCenterLon = navState.lon,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                    },
                    onLongPress = {
                        showQuickMenu = true
                    },
                    onTap = { offset ->
                        // Convert screen offset to lat/lon
                        val dx = (offset.x - size.width / 2) / size.width * (viewSpan * 2)
                        val dy = (offset.y - size.height / 2) / size.height * (viewSpan * 2)
                        
                        val tappedLat = currentLat - dy
                        val tappedLon = currentLon + dx
                        
                        // Precise POI hit-testing
                        val density = context.resources.displayMetrics.density
                        val tapRadiusPx = 20f * density
                        var nearestPoi: MapFeaturePoint? = null
                        var minDistPx = tapRadiusPx
                        
                        // We need a way to estimate current scale/span for hit-testing
                        val curSpanVal = (abs(Mercator.latToY(currentLat) - Mercator.latToY(currentLat + clampedViewSpan)) * 2.0).coerceAtLeast(1e-9)

                        allTiles.forEach { tile ->
                            // Transform tappedLat/Lon to tile coordinates (0..1000)
                            val tx = ((Mercator.lonToX(tappedLon) - (tile.mercatorX - tile.mercatorSpan / 2.0)) / tile.mercatorSpan * 1000.0).toFloat()
                            val ty = ((Mercator.latToY(tappedLat) - (tile.mercatorY - tile.mercatorSpan / 2.0)) / tile.mercatorSpan * 1000.0).toFloat()
                            
                            tile.pointsByType.values.flatten().forEach { poi ->
                                val dist = hypot(poi.point.x - tx, poi.point.y - ty)
                                // Convert tile units to screen pixels
                                val screenDist = dist * (tile.mercatorSpan / curSpanVal * size.height / 1000f)
                                if (screenDist < minDistPx) {
                                    minDistPx = screenDist.toFloat()
                                    nearestPoi = poi
                                }
                            }
                        }

                        val resultItem = if (nearestPoi != null) {
                            val mapObject = if (app.isFullyInitialized) {
                                Framework.nativeGetMapObjectForLocation(tappedLat, tappedLon)
                            } else null
                            SearchResultItem(
                                name = mapObject?.title ?: nearestPoi!!.name,
                                description = mapObject?.subtitle ?: "POI",
                                lat = tappedLat, // Use tapped location for better precision on watch
                                lon = tappedLon,
                                type = 2, // TYPE_RESULT
                                openingHours = mapObject?.getMetadata(app.organicmaps.sdk.bookmarks.data.Metadata.MetadataType.FMD_OPEN_HOURS) ?: "",
                                website = mapObject?.getMetadata(app.organicmaps.sdk.bookmarks.data.Metadata.MetadataType.FMD_WEBSITE) ?: "",
                                phone = mapObject?.getMetadata(app.organicmaps.sdk.bookmarks.data.Metadata.MetadataType.FMD_PHONE_NUMBER) ?: "",
                                address = mapObject?.address ?: ""
                            )
                        } else {
                            SearchResultItem(
                                name = "Tapped Location",
                                description = String.format(java.util.Locale.US, "%.5f, %.5f", tappedLat, tappedLon),
                                lat = tappedLat,
                                lon = tappedLon,
                                type = 2
                            )
                        }

                        if (!navState.standaloneMode && navState.isPhoneConnected) {
                            WearCommandService.showOnPhone(context, resultItem)
                        } else {
                            tappedDestination = resultItem
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        val currentTiles = allTiles
        
        // PERSISTENT ROUTE state to prevent flickering when map reloads
        var routeToDraw by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
        if (navState.isActive && navState.routePoints.isNotEmpty()) {
            routeToDraw = navState.routePoints
        } else if (!navState.isActive || navState.isRecalculating) {
            routeToDraw = emptyList()
        }

        val curX = Mercator.lonToX(currentLon)
        val curY = Mercator.latToY(currentLat)
        val topY = Mercator.latToY(currentLat + clampedViewSpan)
        val curSpan = (abs(curY - topY) * 2.0).coerceAtLeast(1e-9)

        // PERFORMANCE: Pre-filter and group visible tiles once per frame
        val visibleTiles = remember(allTiles, curX, curY, curSpan) {
            val threshold = (curSpan + (1.0 / (1 shl 16))) * 1.5
            allTiles.filter { 
                abs(it.mercatorX - curX) < threshold && abs(it.mercatorY - curY) < threshold 
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            
            // UNIFIED MERCATOR COORDINATE SYSTEM
            withTransform({
                translate(top = offsetValPx)
                rotate(sensorViewModel.mapRotationAnimatable.value, pivot = Offset(size.width / 2, size.height / 2))
            }) {
                // GLOBAL TYPE-CENTRIC RENDERING (The Professional Way)
                // Pass 1: Background features
                val bgOrder = listOf(9, 3, 2) // Green, Water, Buildings
                bgOrder.forEach { type ->
                    visibleTiles.forEach { tile ->
                        DrawPassInternal(tile, curX, curY, curSpan, 1, isAmbient, clampedViewSpan, isDark, this, emptyMap(), type)
                    }
                }
                
                // Pass 2: Road Outlines (Ordered lowest to highest)
                val roadOrder = listOf(1, 8, 7, 6, 5, 4)
                roadOrder.forEach { type ->
                    visibleTiles.forEach { tile ->
                        DrawPassInternal(tile, curX, curY, curSpan, 2, isAmbient, clampedViewSpan, isDark, this, emptyMap(), type)
                    }
                }
                
                // Pass 3: Road Fills (Ordered lowest to highest)
                roadOrder.forEach { type ->
                    visibleTiles.forEach { tile ->
                        DrawPassInternal(tile, curX, curY, curSpan, 3, isAmbient, clampedViewSpan, isDark, this, emptyMap(), type)
                    }
                }
                
                // Pass 4: Labels & POIs
                visibleTiles.forEach { tile ->
                    DrawPassInternal(tile, curX, curY, curSpan, 4, isAmbient, clampedViewSpan, isDark, this, poiIcons)
                }

                // DRAW ROUTE
                if (routeToDraw.isNotEmpty()) {
                    withTransform({
                        translate(size.width / 2, size.height / 2)
                        // Scale 1:1 with screen pixels for route drawing
                    }) {
                        val routePath = Path()
                        routeToDraw.forEachIndexed { i, (lat, lon) ->
                            val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.height).toFloat()
                            val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.height).toFloat()
                            if (i == 0) routePath.moveTo(rx, ry)
                            else routePath.lineTo(rx, ry)
                        }
                        
                        drawPath(
                            path = routePath, 
                            color = Color(0x33000000),
                            style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        drawPath(
                            path = routePath, 
                            color = if (isAmbient) Color.White else Color(0xFF249CF2),
                            style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        
                        // DRAW MORPHED TURN ARROW (Overlay on route)
                        if (!isAmbient && navState.isActive && navState.turnLat != 0.0) {
                            if (routeToDraw.size >= 2) {
                                var bestTurnIdx = 0
                                var minDist = Double.MAX_VALUE
                                for (i in routeToDraw.indices) {
                                    val d = hypot(
                                        Mercator.lonToX(routeToDraw[i].second) - Mercator.lonToX(navState.turnLon),
                                        Mercator.latToY(routeToDraw[i].first) - Mercator.latToY(navState.turnLat)
                                    )
                                    if (d < minDist) {
                                        minDist = d
                                        bestTurnIdx = i
                                    }
                                }

                                // Extract segment around the turn for morphing (e.g., +/- 5 points)
                                val startIdx = (bestTurnIdx - 5).coerceAtLeast(0)
                                val endIdx = (bestTurnIdx + 5).coerceAtMost(routeToDraw.size - 1)
                                val segment = routeToDraw.subList(startIdx, endIdx + 1)
                                
                                if (segment.size >= 2) {
                                    val turnPath = Path()
                                    segment.forEachIndexed { i, (lat, lon) ->
                                        val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.height).toFloat()
                                        val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.height).toFloat()
                                        if (i == 0) turnPath.moveTo(rx, ry)
                                        else turnPath.lineTo(rx, ry)
                                    }
                                    
                                    val turnColor = Color(0xFFFFC30A) // Organic Maps Yellow
                                    
                                    // Draw the thick arrow body
                                    drawPath(
                                        path = turnPath,
                                        color = Color.White,
                                        style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    drawPath(
                                        path = turnPath,
                                        color = turnColor, 
                                        style = Stroke(width = 10.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    
                                    // Draw the LARGE arrowhead at the end of the segment
                                    val lastP = segment.last()
                                    val prevP = segment[segment.size - 2]
                                    val lx = ((Mercator.lonToX(lastP.second) - curX) / curSpan * size.height).toFloat()
                                    val ly = ((Mercator.latToY(lastP.first) - curY) / curSpan * size.height).toFloat()
                                    val px = ((Mercator.lonToX(prevP.second) - curX) / curSpan * size.height).toFloat()
                                    val py = ((Mercator.latToY(prevP.first) - curY) / curSpan * size.height).toFloat()
                                    
                                    val angle = atan2((ly - py).toDouble(), (lx - px).toDouble()).toFloat()
                                    withTransform({
                                        translate(lx, ly)
                                        rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
                                    }) {
                                        val tipPath = Path().apply {
                                            moveTo(0f, -16.dp.toPx())
                                            lineTo(-13.dp.toPx(), 10.dp.toPx())
                                            lineTo(13.dp.toPx(), 10.dp.toPx())
                                            close()
                                        }
                                        drawPath(tipPath, Color.White, style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round))
                                        drawPath(tipPath, turnColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Navigation Instructions Overlay
        if (navState.isActive && navState.distToTurn.isNotEmpty() && !navState.isExploreMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 35.dp) // More inward for round watches
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
                        Icon(
                            painter = painterResource(id = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection, navState.exitNum)),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = navState.distToTurn, style = MaterialTheme.typography.title3.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                        if (navState.nextStreet.isNotEmpty()) {
                            Text(text = navState.nextStreet, style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                        }
                    }
                }
            }
        }

        // USER MARKER (Unified 3D-style Arrow)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            
            // Map parameters
            val curX = Mercator.lonToX(currentLon)
            val curY = Mercator.latToY(currentLat)
            val topY = Mercator.latToY(currentLat + clampedViewSpan)
            val curSpan = (abs(curY - topY) * 2.0).coerceAtLeast(1e-9)

            val userScreenX: Float
            val userScreenY: Float

            if (navState.isExploreMode) {
                // Project user location relative to the map's current center
                val rawDx = ((Mercator.lonToX(lastValidLon) - curX) / curSpan * size.height).toFloat()
                val rawDy = ((Mercator.latToY(lastValidLat) - curY) / curSpan * size.height).toFloat()

                // Apply map rotation and vertical offset to the relative coordinates
                val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
                val cosR = cos(rotationRad).toFloat()
                val sinR = sin(rotationRad).toFloat()

                // Rotate the (dx, dy) vector
                val rotatedDx = rawDx * cosR - rawDy * sinR
                val rotatedDy = rawDx * sinR + rawDy * cosR

                userScreenX = size.width / 2 + rotatedDx
                userScreenY = size.height / 2 + offsetValPx + rotatedDy
            } else {
                userScreenX = size.width / 2
                userScreenY = size.height / 2 + offsetValPx
            }

            if (userScreenX in -30f..(size.width + 30f) && userScreenY in -30f..(size.height + 30f)) {
                val arrowBlue = if (isDark) Color(0xFF1E88E5) else Color(0xFF249CF2) 
                
                withTransform({
                    translate(userScreenX, userScreenY)
                    // If exploring, arrow shows actual compass. If navigating, it shows relative rotation.
                    rotate(if (navState.isExploreMode) compassHeading + sensorViewModel.mapRotationAnimatable.value else 0f)
                }) {
                    val arrowPath = Path().apply {
                        moveTo(0f, -14.dp.toPx())
                        lineTo(-8.5.dp.toPx(), 4.dp.toPx())
                        lineTo(0f, 0.dp.toPx()) // Inner notch for 3D look
                        lineTo(8.5.dp.toPx(), 4.dp.toPx())
                        close()
                    }
                    drawPath(arrowPath, Color.White, style = Stroke(width = 2.8.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(arrowPath, arrowBlue)
                }
            }
        }

        if (!isMapDownloaded && useOfflineMaps) {
            // ... (no change to this box)
        }

        if (navState.isActive && navState.isExploreMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                androidx.wear.compose.material.CompactChip(
                    onClick = {
                        NavigationStateHolder.update { it.copy(isExploreMode = false, lastSettingsInteractionTime = System.currentTimeMillis()) }
                    },
                    label = { Text("Recenter") },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
        
        if (showQuickMenu) {
            QuickMenu(
                currentLat = currentLat,
                currentLon = currentLon,
                viewSpan = viewSpan,
                onDismiss = { showQuickMenu = false }
            )
        }

        if (tappedDestination != null) {
            val dest = tappedDestination!!
            Dialog(
                showDialog = true,
                onDismissRequest = { tappedDestination = null }
            ) {
                val scope = rememberCoroutineScope()
                PlacePage(
                    result = dest,
                    onNavigate = { routerType ->
                        scope.launch {
                            if (navState.watchLocalMode || navState.standaloneMode) {
                                try {
                                    app.waitForInitializationSuspend()
                                    val startPoint = app.organicMaps.locationHelper.myPosition
                                    val destination = MapObject.createMapObject(MapObject.POI, dest.name, dest.description, dest.lat, dest.lon)
                                    val router = when (routerType) {
                                        0 -> Router.Vehicle
                                        1 -> Router.Pedestrian
                                        2 -> Router.Bicycle
                                        else -> Router.Transit
                                    }
                                    val controller = RoutingController.get()
                                    controller.prepare(startPoint, destination, router)
                                    controller.checkAndBuildRoute()
                                    NavigationStateHolder.update { it.copy(
                                        isActive = true,
                                        isNavigating = false,
                                        routeBuildProgress = 0,
                                        isRouteBuilding = true,
                                        isRouteReady = false,
                                        routePoints = emptyList(),
                                        distToTurn = "",
                                        nextStreet = "",
                                        distToTarget = "",
                                        eta = 0,
                                        completionPercent = 0.0,
                                        turnLat = 0.0,
                                        turnLon = 0.0,
                                        isExploreMode = false // Force lock when navigation starts
                                    ) }
                                } catch (e: Exception) {
                                    android.util.Log.e("MapPanel", "Route planning failed: ${e.message}")
                                }
                            } else {
                                WearCommandService.selectSearchResult(context, dest, routerType)
                                // Transition to route preview state locally too
                                NavigationStateHolder.update { it.copy(
                                    isActive = true,
                                    isNavigating = false,
                                    destinationName = dest.name,
                                    isExploreMode = false
                                ) }
                            }
                            tappedDestination = null
                        }
                    },
                    onDismiss = { tappedDestination = null }
                )
            }
        }
    }
}

@Composable
fun QuickMenu(currentLat: Double, currentLon: Double, viewSpan: Float, onDismiss: () -> Unit) {
    androidx.wear.compose.material.dialog.Dialog(
        showDialog = true,
        onDismissRequest = onDismiss
    ) {
        val navState by NavigationStateHolder.state.collectAsState()
        val context = LocalContext.current
        
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)
        ) {
            item {
                Text("Quick Menu", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF))
            }
            
            item {
                Chip(
                    onClick = {
                        val newSpan = (viewSpan * 0.75f).coerceAtLeast(0.0001f)
                        NavigationStateHolder.update(navState.copy(
                            isExploreMode = true,
                            manualViewSpan = newSpan,
                            manualCenterLat = if (!navState.isExploreMode) currentLat else navState.manualCenterLat,
                            manualCenterLon = if (!navState.isExploreMode) currentLon else navState.manualCenterLon,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                        onDismiss()
                    },
                    label = { Text("Zoom In") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            
            item {
                Chip(
                    onClick = {
                        val newSpan = (viewSpan * 1.33f).coerceAtMost(0.05f)
                        NavigationStateHolder.update(navState.copy(
                            isExploreMode = true,
                            manualViewSpan = newSpan,
                            manualCenterLat = if (!navState.isExploreMode) currentLat else navState.manualCenterLat,
                            manualCenterLon = if (!navState.isExploreMode) currentLon else navState.manualCenterLon,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                        onDismiss()
                    },
                    label = { Text("Zoom Out") },
                    icon = { Icon(Icons.Default.Remove, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            
            item {
                Chip(
                    onClick = {
                        NavigationStateHolder.update { it.copy(isExploreMode = false, lastSettingsInteractionTime = System.currentTimeMillis()) }
                        onDismiss()
                    },
                    label = { Text("Re-center") },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            
            item {
                ToggleChip(
                    checked = navState.isExploreMode,
                    onCheckedChange = { newVal ->
                        NavigationStateHolder.update(navState.copy(
                            isExploreMode = newVal,
                            manualCenterLat = navState.lat, // Always sync on lock/unlock
                            manualCenterLon = navState.lon,
                            manualViewSpan = viewSpan,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                        onDismiss()
                    },
                    label = { Text(if (navState.isExploreMode) "Unlock Map" else "Lock Map") },
                    toggleControl = {
                        Switch(checked = navState.isExploreMode)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            
            item {
                Chip(
                    onClick = onDismiss,
                    label = { Text("Close") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

fun DrawPassInternal(
    tile: ParsedMapTile,
    curX: Double,
    curY: Double,
    curSpan: Double,
    pass: Int,
    isAmbient: Boolean,
    viewSpan: Float,
    isDark: Boolean,
    drawScope: DrawScope,
    poiIcons: Map<Int, Painter>,
    specificType: Int? = null
) {
    with(drawScope) {
        withTransform({
            translate(size.width / 2, size.height / 2)
            val scale = (tile.mercatorSpan / curSpan).toFloat()
            scale(scale, scale, Offset.Zero)
            
            val canonicalScale = size.width / 1000f
            val dx = ((tile.mercatorX - curX) / tile.mercatorSpan * 1000.0 * canonicalScale).toFloat()
            val dy = ((tile.mercatorY - curY) / tile.mercatorSpan * 1000.0 * canonicalScale).toFloat()
            translate(dx, dy)
            scale(canonicalScale, canonicalScale, Offset.Zero)
            translate(-500f, -500f)
        }) {
            // Pass 1-3 are Background and Roads - apply strict clipping to fix stitching
            if (pass in 1..3) {
                drawIntoCanvas { canvas ->
                    canvas.clipRect(0f, 0f, 1000f, 1000f)
                    DrawTilePassInternal(tile, pass, isAmbient, viewSpan, isDark, this, poiIcons, specificType)
                }
            } else {
                DrawTilePassInternal(tile, pass, isAmbient, viewSpan, isDark, this, poiIcons, specificType)
            }
        }
    }
}

fun DrawTilePassInternal(tile: ParsedMapTile, pass: Int, isAmbient: Boolean = false, viewSpan: Float = 0.003f, isDark: Boolean = false, drawScope: DrawScope, poiIcons: Map<Int, Painter>, specificType: Int? = null) {
    with(drawScope) {
        val typesToDraw = if (specificType != null) listOf(specificType) else tile.pathsByType.keys.toList()

        // Performance: Dynamic Detail Reduction (LOD)
        val hideMinorRoads = viewSpan > 0.035f
        val hideBuildings = viewSpan > 0.025f
        val hideLabels = viewSpan > 0.015f

        for (type in typesToDraw) {
            if (type == 2 && hideBuildings) continue
            if (type == 8 && hideMinorRoads) continue

            val features = tile.pathsByType[type] ?: continue
            for (f in features) {
                val mapPath = f.path
                if (isAmbient) {
                    if (pass != 3) continue // Only draw fills in ambient
                    // In ambient mode, only draw water and roads in grayscale
                    when (type) {
                        3 -> drawPath(mapPath, Color.Gray) // Water
                        4, 5, 6, 7, 8, 1 -> drawPath(mapPath, Color.White, style = Stroke(width = 8f)) // Roads
                    }
                    continue
                }
                
                // PRETTIER COLORS (Organic Maps Inspired)
                val roadBorderColor = if (isDark) Color(0xFF333333) else Color(0xFF808080) // Darker border for day mode
                when (type) {
                    1 -> { // Residential
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF444444) else Color.White, style = Stroke(width = 15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    2 -> if (pass == 1) drawPath(mapPath, if (isDark) Color(0xFF2A2A2A) else Color(0xFFDEDBD0)) // Buildings
                    3 -> if (pass == 1) drawPath(mapPath, if (isDark) Color(0xFF1F2D3D) else Color(0xFF90CAF9)) // Water
                    4 -> { // Motorway/Trunk (Orange)
                        if (pass == 2) drawPath(mapPath, if (isDark) Color(0xFFE67E22) else Color(0xFFE67E22), style = Stroke(width = 36f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFFD35400) else Color(0xFFFF9800), style = Stroke(width = 26f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    5 -> { // Primary (Yellow)
                        if (pass == 2) drawPath(mapPath, if (isDark) Color(0xFFF1C40F) else Color(0xFFF1C40F), style = Stroke(width = 32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFFF39C12) else Color(0xFFFBC02D), style = Stroke(width = 22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    6 -> { // Secondary
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 28f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF444444) else Color.White, style = Stroke(width = 19f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    7 -> { // Tertiary
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF3A3A3A) else Color.White, style = Stroke(width = 15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    8 -> { // Service/Minor
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 20f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF333333) else Color(0xFFF5F5F5), style = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    9 -> if (pass == 1) drawPath(mapPath, if (isDark) Color(0xFF212D21) else Color(0xFFD4E3A9)) // Greenery
                    else -> {
                        if (pass == 2) drawPath(mapPath, if (isDark) Color(0xFF222222) else Color(0xFF808080), style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF333333) else Color(0xFFFAFAFA), style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                
                // Pass 4: Labels
                if (pass == 4 && !hideLabels && f.name.isNotEmpty() && type in listOf(4, 5, 6, 7)) {
                    drawIntoCanvas { canvas ->
                        val textPaint = android.graphics.Paint().apply {
                            this.color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                            textSize = 8.dp.toPx()
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                            setShadowLayer(2.dp.toPx(), 0f, 0f, if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                        canvas.nativeCanvas.drawTextOnPath(f.name, f.path.asAndroidPath(), 0f, 3.dp.toPx(), textPaint)
                    }
                }
            }
        }
        
        // Pass 4: POIs
        if (pass == 4 && !isAmbient && !hideLabels) {
            for ((type, features) in tile.pointsByType) {
                val color = when (type) {
                    100, 101 -> Color(0xFFE67E22) // Eat / Cafe
                    102 -> Color(0xFF9B59B6) // Hotel
                    103 -> Color(0xFF2ECC71) // ATM
                    107 -> Color(0xFF3498DB) // Parking
                    108 -> Color(0xFF7F8C8D) // Peak
                    109 -> Color(0xFF27AE60) // Camping
                    111, 112 -> Color(0xFFF1C40F) // Transport
                    else -> Color(0xFF95A5A6)
                }

                val icon = poiIcons[type] ?: poiIcons[111] // Fallback

                for (f in features) {
                    val p = f.point
                    
                    // Draw POI Pin
                    drawCircle(Color.Black.copy(alpha = 0.2f), radius = 8.dp.toPx(), center = p.copy(y = p.y + 1.dp.toPx()))
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = p)
                    drawCircle(color, radius = 7.dp.toPx(), center = p, style = Stroke(width = 1.5.dp.toPx()))
                    
                    // Draw Icon
                    if (icon != null) {
                        val iconSizePx = 9.dp.toPx()
                        withTransform({
                            translate(p.x - iconSizePx / 2f, p.y - iconSizePx / 2f)
                        }) {
                            with(icon) {
                                draw(size = androidx.compose.ui.geometry.Size(iconSizePx, iconSizePx))
                            }
                        }
                    } else {
                        drawCircle(color, radius = 3.dp.toPx(), center = p)
                    }
                    
                    // Labels only when zoomed in very closely
                    if (f.name.isNotEmpty() && viewSpan < 0.004f) {
                        drawIntoCanvas { canvas ->
                            val textPaint = android.graphics.Paint().apply {
                                this.color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                                textSize = 9.dp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                setShadowLayer(2f, 0f, 0f, if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                this.color = if (isDark) android.graphics.Color.argb(180, 40, 40, 40) else android.graphics.Color.argb(180, 255, 255, 255)
                            }
                            val bounds = android.graphics.Rect()
                            textPaint.getTextBounds(f.name, 0, f.name.length, bounds)
                            val bgRect = android.graphics.RectF(
                                p.x - bounds.width()/2f - 3.dp.toPx(),
                                p.y + 11.dp.toPx() - bounds.height() - 1.dp.toPx(),
                                p.x + bounds.width()/2f + 3.dp.toPx(),
                                p.y + 11.dp.toPx() + 2.dp.toPx()
                            )
                            canvas.nativeCanvas.drawRoundRect(bgRect, 3.dp.toPx(), 3.dp.toPx(), bgPaint)
                            canvas.nativeCanvas.drawText(f.name, p.x, p.y + 11.dp.toPx(), textPaint)
                        }
                    }
                }
            }
        }
    }
}
