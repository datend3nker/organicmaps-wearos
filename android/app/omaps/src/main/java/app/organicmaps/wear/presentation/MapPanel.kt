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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Icon
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.MapTileStateHolder
import app.organicmaps.wear.Mercator
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.ParsedMapTile
import app.organicmaps.wear.LocalAmbientMode
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.NavigationIcons
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import android.content.Context
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.navigation.SensorViewModel

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

import androidx.wear.compose.material.ButtonDefaults
import app.organicmaps.wear.MapTileKey

@Composable
fun MapPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isAmbient = LocalAmbientMode.current
    val app = context.applicationContext as WearApplication
    val navState by NavigationStateHolder.state.collectAsState()
    val streamedTile by MapTileStateHolder.mapTile.collectAsState()
    val allTiles by MapTileStateHolder.cachedTilesFlow.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()
    val focusRequester = remember { FocusRequester() }

    val effectiveLat = if (navState.isExploreMode) navState.manualCenterLat else navState.lat
    val effectiveLon = if (navState.isExploreMode) navState.manualCenterLon else navState.lon
    
    // PERSISTENT VALID LOCATION to prevent flickering on 0.0 jumps
    var lastValidLat by remember { mutableStateOf(if (navState.lat != 0.0) navState.lat else 48.2082) }
    var lastValidLon by remember { mutableStateOf(if (navState.lon != 0.0) navState.lon else 16.3738) }
    if (navState.lat != 0.0) lastValidLat = navState.lat
    if (navState.lon != 0.0) lastValidLon = navState.lon
    
    val currentLat = if (navState.isExploreMode) effectiveLat else lastValidLat
    val currentLon = if (navState.isExploreMode) effectiveLon else lastValidLon

    val zoom = 16
    val currentTileKey = MapTileKey(Mercator.lonToTileX(currentLon, 16), Mercator.latToTileY(currentLat, 16))

    var pendingRequestId by remember { mutableLongStateOf(0L) }
    val requestKeys = remember { mutableMapOf<Long, MapTileKey>() }

    // Logic: In companion mode and navigating, interaction is LOCKED
    val canInteract = navState.standaloneMode || !navState.isActive
    
    // Auto-disable explore mode if navigation starts in companion mode
    LaunchedEffect(navState.isActive, navState.standaloneMode) {
        if (navState.isActive && !navState.standaloneMode && navState.isExploreMode) {
            NavigationStateHolder.update(navState.copy(isExploreMode = false))
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

    // Quantized viewSpan for requests to improve zoom responsiveness without flood
    var lastRequestedSpan by remember { mutableStateOf(0f) }
    
    val requestViewSpan = remember(viewSpan) {
        val current = viewSpan
        // INCREASE THRESHOLD to 50% to prevent flickering during zoom animations and movement
        if (navState.isExploreMode || abs(current - lastRequestedSpan) / (lastRequestedSpan.coerceAtLeast(0.0001f)) > 0.50f) {
            lastRequestedSpan = current
            current
        } else {
            lastRequestedSpan.coerceAtLeast(0.0001f)
        }
    }

    var isUsingGpsBearing by remember { mutableStateOf(false) }

    // SAFE STABILIZED ROTATION with Speed-based Hysteresis
    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive, navState.isExploreMode) {
        if (navState.speedMps > 2.0f) { // Increased threshold for GPS bearing
            isUsingGpsBearing = true
        } else if (navState.speedMps < 0.8f || navState.bearing < 0f) {
            isUsingGpsBearing = false
        }
        val targetDeg = when {
            navState.isExploreMode -> 0f
            // Only use GPS bearing if we have a valid speed and valid bearing
            navState.isActive && isUsingGpsBearing && navState.bearing >= 0f -> -navState.bearing
            // Fallback to compass if slow or stationary
            else -> -compassHeading
        }
        
        var diff = targetDeg - sensorViewModel.mapRotationAnimatable.value
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        
        sensorViewModel.mapRotationAnimatable.animateTo(
            targetValue = sensorViewModel.mapRotationAnimatable.value + diff,
            animationSpec = spring(stiffness = Spring.StiffnessVeryLow, dampingRatio = Spring.DampingRatioNoBouncy) // Smoother rotation
        )
    }

    val verticalOffsetFractionTarget = if (navState.routerType == 0 && navState.isActive && !navState.isExploreMode) 0.35f else 0.0f
    val verticalOffsetFraction by animateFloatAsState(
        targetValue = verticalOffsetFractionTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offset"
    )

    val useOfflineMaps = navState.watchLocalMode || !navState.isPhoneConnected || navState.standaloneMode

    // Hide POIs while navigating/planning as target is already set
    val effectivePoiMask = if (navState.isActive) 0 else navState.poiCategoriesMask

    val scope = rememberCoroutineScope()

    // SMART PRE-FETCHING LOGIC
    LaunchedEffect(currentLat, currentLon, navState.routePoints, useOfflineMaps, effectivePoiMask) {
        while (true) {
            val keysToRequest = mutableListOf<MapTileKey>()
            
            // 1. Current Tile
            val currentKey = MapTileKey(Mercator.lonToTileX(currentLon, 16), Mercator.latToTileY(currentLat, 16))
            if (MapTileStateHolder.getCachedTileByKey(currentKey) == null) {
                keysToRequest.add(currentKey)
            }
            
            // 2. Surrounding 3x3 Grid (Explore/Stationary)
            if (!navState.isNavigating || navState.isExploreMode) {
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val key = MapTileKey(currentKey.x + dx, currentKey.y + dy)
                        if (MapTileStateHolder.getCachedTileByKey(key) == null && !keysToRequest.contains(key)) {
                            keysToRequest.add(key)
                        }
                    }
                }
            }
            
            // 3. Route Pre-fetching (Next 1km)
            if (navState.isNavigating && navState.routePoints.isNotEmpty()) {
                var distAcc = 0.0
                for (i in 0 until navState.routePoints.size - 1) {
                    val p1 = navState.routePoints[i]
                    val p2 = navState.routePoints[i+1]
                    
                    // Simple lat/lon dist approximation for pre-fetching
                    distAcc += hypot(p2.first - p1.first, p2.second - p1.second) * 111000.0
                    if (distAcc > 1500.0) break // Fetch up to 1.5km ahead
                    
                    val key = MapTileKey(Mercator.lonToTileX(p2.second, 16), Mercator.latToTileY(p2.first, 16))
                    if (MapTileStateHolder.getCachedTileByKey(key) == null && !keysToRequest.contains(key)) {
                        keysToRequest.add(key)
                    }
                }
            }

            // Execute requests (debounced)
            for (key in keysToRequest.take(5)) {
                if (app.isFullyInitialized) {
                    val tLat = Mercator.tileYToLat(key.y, 16)
                    val tLon = Mercator.tileXToLon(key.x, 16)
                    val span = 1.0 / (1 shl 16) // Tile span in degrees approx
                    // Get slightly larger area from native to avoid clipping
                    val nativeSpan = 0.005 
                    
                    val localFeatures = Framework.nativeGetWearMapFeatures(
                        tLat - nativeSpan, tLon - nativeSpan,
                        tLat + nativeSpan, tLon + nativeSpan,
                        16, navState.routerType, effectivePoiMask
                    )
                    if (localFeatures.isNotEmpty()) {
                        scope.launch(Dispatchers.Default) {
                            val parsed = MapTileStateHolder.parseTile(localFeatures, key, 1000f, 1000f)
                            withContext(Dispatchers.Main) {
                                MapTileStateHolder.updateCache(key, parsed)
                            }
                        }
                        continue
                    }
                }
                
                if (!useOfflineMaps || (useOfflineMaps && navState.isPhoneConnected)) {
                    val requestId = System.nanoTime()
                    pendingRequestId = requestId
                    requestKeys[requestId] = key
                    
                    val tLat = Mercator.tileYToLat(key.y, 16)
                    val tLon = Mercator.tileXToLon(key.x, 16)
                    val reqSpan = 0.003 // Standard request size
                    
                    WearCommandService.requestMapTile(
                        context, requestId,
                        tLat - reqSpan, tLon - reqSpan,
                        tLat + reqSpan, tLon + reqSpan,
                        navState.routerType, effectivePoiMask
                    )
                    delay(300) // Small delay between remote requests
                }
            }
            
            delay(5000) // Much longer delay for the re-check loop
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
            .background(if (isAmbient) Color.Black else Color(0xFFF1EEE8)) // Pure black in ambient mode
            .focusRequester(focusRequester)
            .onRotaryScrollEvent {
                if (canInteract) {
                    val factor = if (it.verticalScrollPixels > 0) 1.25f else 0.75f
                    val currentSpan = if (navState.isExploreMode) navState.manualViewSpan else viewSpan
                    val newSpan = (currentSpan * factor).coerceIn(0.0005f, 0.05f)
                    NavigationStateHolder.update(navState.copy(
                        isExploreMode = true,
                        manualViewSpan = newSpan,
                        manualCenterLat = if (!navState.isExploreMode) (if (navState.lat != 0.0) navState.lat else 48.2082) else navState.manualCenterLat,
                        manualCenterLon = if (!navState.isExploreMode) (if (navState.lon != 0.0) navState.lon else 16.3738) else navState.manualCenterLon
                    ))
                    true
                } else false
            }
            .pointerInput(navState.isExploreMode, canInteract) {
                if (navState.isExploreMode && canInteract) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val latStep = (dragAmount.y / size.height) * (viewSpan * 2)
                        val lonStep = -(dragAmount.x / size.width) * (viewSpan * 2)
                        NavigationStateHolder.update(navState.copy(
                            manualCenterLat = navState.manualCenterLat + latStep.toDouble(),
                            manualCenterLon = navState.manualCenterLon + lonStep.toDouble()
                        ))
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        val currentTiles = allTiles
        
        // PERSISTENT ROUTE state to prevent flickering when map reloads
        var routeToDraw by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
        if (navState.routePoints.isNotEmpty()) {
            routeToDraw = navState.routePoints
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            val userScreenX = size.width / 2
            val userScreenY = (size.height / 2) + offsetValPx
            
            // UNIFIED MERCATOR COORDINATE SYSTEM
            withTransform({
                rotate(sensorViewModel.mapRotationAnimatable.value, pivot = Offset(userScreenX, userScreenY))
                translate(top = offsetValPx)
            }) {
                val curX = Mercator.lonToX(currentLon)
                val curY = Mercator.latToY(currentLat)
                val topY = Mercator.latToY(currentLat + viewSpan)
                val curSpan = abs(curY - topY) * 2.0
                
                // MULTI-TILE RENDERING
                currentTiles.forEach { tile ->
                    // Only draw if within reasonable distance
                    if (abs(tile.mercatorX - curX) < curSpan * 2.0 && abs(tile.mercatorY - curY) < curSpan * 2.0) {
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
                            drawTile(tile, isAmbient)
                        }
                    }
                }

                // DRAW ROUTE (Overlay)
                if (routeToDraw.isNotEmpty()) {
                    withTransform({
                        translate(size.width / 2, size.height / 2)
                        // Scale 1:1 with screen pixels for route drawing
                    }) {
                        val routePath = Path()
                        routeToDraw.forEachIndexed { i, (lat, lon) ->
                            val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.width).toFloat()
                            val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.width).toFloat()
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

                                // Extract segment around the turn for morphing (e.g., +/- 3 points)
                                val startIdx = (bestTurnIdx - 3).coerceAtLeast(0)
                                val endIdx = (bestTurnIdx + 3).coerceAtMost(routeToDraw.size - 1)
                                val segment = routeToDraw.subList(startIdx, endIdx + 1)
                                
                                if (segment.size >= 2) {
                                    val turnPath = Path()
                                    segment.forEachIndexed { i, (lat, lon) ->
                                        val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.width).toFloat()
                                        val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.width).toFloat()
                                        if (i == 0) turnPath.moveTo(rx, ry)
                                        else turnPath.lineTo(rx, ry)
                                    }
                                    
                                    val turnColor = Color(0xFFFFC30A) // Organic Maps Yellow
                                    
                                    // Draw the thick arrow body
                                    drawPath(
                                        path = turnPath,
                                        color = Color.White,
                                        style = Stroke(width = 13.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    drawPath(
                                        path = turnPath,
                                        color = turnColor, 
                                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    
                                    // Draw the LARGE arrowhead at the end of the segment
                                    val lastP = segment.last()
                                    val prevP = segment[segment.size - 2]
                                    val lx = ((Mercator.lonToX(lastP.second) - curX) / curSpan * size.width).toFloat()
                                    val ly = ((Mercator.latToY(lastP.first) - curY) / curSpan * size.width).toFloat()
                                    val px = ((Mercator.lonToX(prevP.second) - curX) / curSpan * size.width).toFloat()
                                    val py = ((Mercator.latToY(prevP.first) - curY) / curSpan * size.width).toFloat()
                                    
                                    val angle = atan2((ly - py).toDouble(), (lx - px).toDouble()).toFloat()
                                    withTransform({
                                        translate(lx, ly)
                                        rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
                                    }) {
                                        val tipPath = Path().apply {
                                            moveTo(0f, -12.dp.toPx())
                                            lineTo(-11.dp.toPx(), 8.dp.toPx())
                                            lineTo(11.dp.toPx(), 8.dp.toPx())
                                            close()
                                        }
                                        drawPath(tipPath, Color.White, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
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
                    .padding(top = 26.dp)
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

        // Interaction Toggle and Re-center (Only if allowed)
        if (canInteract) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.wear.compose.material.Button(
                    onClick = {
                        NavigationStateHolder.update(navState.copy(
                            isExploreMode = !navState.isExploreMode,
                            manualCenterLat = if (!navState.isExploreMode) (if (navState.lat != 0.0) navState.lat else 48.2082) else navState.manualCenterLat,
                            manualCenterLon = if (!navState.isExploreMode) (if (navState.lon != 0.0) navState.lon else 16.3738) else navState.manualCenterLon,
                            manualViewSpan = 0.003f
                        ))
                    },
                    modifier = Modifier.size(36.dp),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(
                        imageVector = if (navState.isExploreMode) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = "Toggle Interaction",
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (navState.isExploreMode) {
                    androidx.wear.compose.material.Button(
                        onClick = {
                            val newSpan = (navState.manualViewSpan * 0.75f).coerceAtLeast(0.0005f)
                            NavigationStateHolder.update(navState.copy(manualViewSpan = newSpan))
                        },
                        modifier = Modifier.size(36.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
                    }
                    
                    androidx.wear.compose.material.Button(
                        onClick = {
                            val newSpan = (navState.manualViewSpan * 1.33f).coerceAtMost(0.05f)
                            NavigationStateHolder.update(navState.copy(manualViewSpan = newSpan))
                        },
                        modifier = Modifier.size(36.dp),
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
                    }

                    androidx.wear.compose.material.Button(
                        onClick = { NavigationStateHolder.update(navState.copy(isExploreMode = false)) },
                        modifier = Modifier.size(36.dp),
                        colors = ButtonDefaults.primaryButtonColors()
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Re-center", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Connection/Mode Indicators
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)) {
            Row {
                if (!navState.isPhoneConnected && !navState.standaloneMode) {
                    Box(modifier = Modifier.size(22.dp).background(Color.Red.copy(alpha = 0.8f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = "Not Connected", modifier = Modifier.size(14.dp), tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (navState.watchLocalMode || navState.standaloneMode) {
                    Box(modifier = Modifier.size(22.dp).background(Color(0xFF4CAF50).copy(alpha = 0.8f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (navState.standaloneMode) Icons.Default.AirplanemodeActive else Icons.Default.SdStorage, contentDescription = "Local Mode", modifier = Modifier.size(14.dp), tint = Color.White)
                    }
                }
            }
        }

        // USER MARKER (Unified 3D-style Arrow)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val userScreenX = size.width / 2
            val userScreenY = (size.height / 2) + (verticalOffsetFraction * size.height)

            if (userScreenX in 0f..size.width && userScreenY in 0f..size.height) {
                val arrowBlue = Color(0xFF249CF2) // Organic Maps Blue
                
                withTransform({
                    translate(userScreenX, userScreenY)
                    // If exploring, arrow shows actual compass. If navigating, it shows relative rotation.
                    rotate(if (navState.isExploreMode) compassHeading else 0f)
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
    }
}

private fun DrawScope.drawTile(tile: ParsedMapTile, isAmbient: Boolean = false) {
    val drawOrder = listOf(9, 3, 2, 8, 1, 7, 6, 5, 4)
    val sortedTypes = tile.pathsByType.keys.sortedBy { type -> drawOrder.indexOf(type).let { if (it == -1) 99 else it } }

    for (type in sortedTypes) {
        val mapPath = tile.pathsByType[type] ?: continue
        if (isAmbient) {
            // In ambient mode, only draw water and roads in grayscale
            when (type) {
                3 -> drawPath(mapPath, Color.Gray) // Water
                4, 5, 6, 7, 8, 1 -> drawPath(mapPath, Color.White, style = Stroke(width = 2.dp.toPx())) // Roads
            }
            continue
        }
        when (type) {
            1 -> { // Residential
                drawPath(mapPath, Color(0xFFCAC8C0), style = Stroke(width = 6.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color.White, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            2 -> drawPath(mapPath, Color(0xFFDEDBD0)) // Buildings
            3 -> drawPath(mapPath, Color(0xFFB2E2F2)) // Water
            4 -> { // Motorway/Trunk
                drawPath(mapPath, Color(0xFFE2A67C), style = Stroke(width = 8.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFFB74D), style = Stroke(width = 5.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            5 -> { // Primary
                drawPath(mapPath, Color(0xFFE9C689), style = Stroke(width = 7.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFFD54F), style = Stroke(width = 4.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            6 -> { // Secondary
                drawPath(mapPath, Color(0xFFDEDBD0), style = Stroke(width = 6.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color.White, style = Stroke(width = 3.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            7 -> { // Tertiary
                drawPath(mapPath, Color(0xFFE0E0E0), style = Stroke(width = 5.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color.White, style = Stroke(width = 3.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            8 -> { // Service/Minor
                drawPath(mapPath, Color(0xFFE0E0E0), style = Stroke(width = 5.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFF5F5F5), style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            9 -> drawPath(mapPath, Color(0xFFD4E3A9)) // Greenery
            else -> {
                drawPath(mapPath, Color(0xFFDEDBD0), style = Stroke(width = 4.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFAFAFA), style = Stroke(width = 3.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    
    // Draw POIs
    if (!isAmbient) {
        for ((type, points) in tile.pointsByType) {
            val color = when (type) {
                100 -> Color(0xFFFF9100)
                101 -> Color(0xFF2979FF)
                102 -> Color(0xFFFF6D00)
                103 -> Color(0xFF00C853)
                105 -> Color(0xFF546E7A)
                else -> Color(0xFF7B1FA2)
            }
            for (p in points) {
                drawCircle(Color.Black.copy(alpha = 0.15f), radius = 6.5.dp.toPx(), center = p.copy(y = p.y + 0.5.dp.toPx()))
                drawCircle(Color.White, radius = 5.5.dp.toPx(), center = p)
                drawCircle(color, radius = 5.5.dp.toPx(), center = p, style = Stroke(width = 1.2.dp.toPx()))
                drawCircle(color, radius = 2.2.dp.toPx(), center = p)
            }
        }
    }
}

private fun lonToTileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
private fun latToTileY(lat: Double, zoom: Int): Int = floor((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)).toInt()
