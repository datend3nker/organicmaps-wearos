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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Icon
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.MapTileStateHolder
import app.organicmaps.wear.NavigationStateHolder
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

import androidx.wear.compose.material.ButtonDefaults

@Composable
fun MapPanel() {
    val context = LocalContext.current
    val app = context.applicationContext as WearApplication
    val navState by NavigationStateHolder.state.collectAsState()
    val streamedTile by MapTileStateHolder.mapTile.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()
    val focusRequester = remember { FocusRequester() }

    val effectiveLat = if (navState.isExploreMode) navState.manualCenterLat else (if (navState.lat != 0.0) navState.lat else 48.2082)
    val effectiveLon = if (navState.isExploreMode) navState.manualCenterLon else (if (navState.lon != 0.0) navState.lon else 16.3738)
    val zoom = 16

    val currentTileX = lonToTileX(effectiveLon, zoom)
    val currentTileY = latToTileY(effectiveLat, zoom)
    var mapFeatures by remember { mutableStateOf<ByteArray?>(null) }
    var mapFeaturesAnchor by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    var pendingRequestId by remember { mutableStateOf(0L) }
    val requestAnchors = remember { mutableMapOf<Long, Triple<Double, Double, Double>>() }

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
        if (navState.isExploreMode || abs(current - lastRequestedSpan) / (lastRequestedSpan.coerceAtLeast(0.0001f)) > 0.15f) {
            lastRequestedSpan = current
            current
        } else {
            lastRequestedSpan.coerceAtLeast(0.0001f)
        }
    }

    // SNAPPY STABILIZED ROTATION
    val mapRotationAnimatable = remember { Animatable(0f) }
    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive, navState.isExploreMode) {
        val targetDeg = if (navState.isExploreMode) {
            0f
        } else if (navState.isActive && navState.speedMps > 1.5 && navState.bearing >= 0) {
            -navState.bearing
        } else {
            -compassHeading
        }
        
        var diff = targetDeg - mapRotationAnimatable.value
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        
        mapRotationAnimatable.animateTo(
            targetValue = mapRotationAnimatable.value + diff,
            animationSpec = spring(stiffness = 300f, dampingRatio = Spring.DampingRatioNoBouncy)
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

    // Quantized coordinates for requests to improve responsiveness without flood
    val requestLat = (effectiveLat * 500).roundToInt() / 500.0
    val requestLon = (effectiveLon * 500).roundToInt() / 500.0

    LaunchedEffect(requestLat, requestLon, useOfflineMaps, requestViewSpan, effectivePoiMask) {
        val cached = MapTileStateHolder.getCachedTile(effectiveLat, effectiveLon)
        if (cached != null && !navState.isExploreMode) {
            mapFeatures = cached.features
            mapFeaturesAnchor = cached.anchor
            return@LaunchedEffect
        }

        if (app.isFullyInitialized) {
            val localFeatures = Framework.nativeGetWearMapFeatures(
                effectiveLat - requestViewSpan,
                effectiveLon - requestViewSpan,
                effectiveLat + requestViewSpan,
                effectiveLon + requestViewSpan,
                16, // Use 16 for local features too
                navState.routerType,
                effectivePoiMask
            )
            if (localFeatures.isNotEmpty()) {
                mapFeatures = localFeatures
                val anchor = Triple(effectiveLat, effectiveLon, requestViewSpan.toDouble())
                mapFeaturesAnchor = anchor
                if (!navState.isExploreMode) MapTileStateHolder.updateCache(effectiveLat, effectiveLon, localFeatures, anchor)
                return@LaunchedEffect
            }

            if (useOfflineMaps && !navState.isPhoneConnected) {
                mapFeatures = null
                mapFeaturesAnchor = null
            }
        }
        
        if (!useOfflineMaps || (useOfflineMaps && mapFeatures == null && navState.isPhoneConnected)) {
            val requestId = System.nanoTime()
            pendingRequestId = requestId
            val anchor = Triple(effectiveLat, effectiveLon, requestViewSpan.toDouble())
            requestAnchors[requestId] = anchor
            WearCommandService.requestMapTile(
                context,
                requestId,
                effectiveLat - requestViewSpan,
                effectiveLon - requestViewSpan,
                effectiveLat + requestViewSpan,
                effectiveLon + requestViewSpan,
                navState.routerType,
                effectivePoiMask
            )
        }
    }

    LaunchedEffect(streamedTile, pendingRequestId) {
        val tile = streamedTile ?: return@LaunchedEffect
        if (tile.requestId != pendingRequestId) return@LaunchedEffect
        val anchor = requestAnchors[tile.requestId]
        mapFeatures = tile.features
        if (anchor != null) {
            mapFeaturesAnchor = anchor
            MapTileStateHolder.updateCache(effectiveLat, effectiveLon, tile.features, anchor)
            requestAnchors.remove(tile.requestId)
        } else {
            val defaultAnchor = Triple(effectiveLat, effectiveLon, requestViewSpan.toDouble())
            mapFeaturesAnchor = defaultAnchor
            MapTileStateHolder.updateCache(effectiveLat, effectiveLon, tile.features, defaultAnchor)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFFF1EEE8)) // Light beige background
            .focusRequester(focusRequester)
            .onRotaryScrollEvent {
                if (navState.isExploreMode && canInteract) {
                    val factor = if (it.verticalScrollPixels > 0) 1.25f else 0.75f
                    val newSpan = (navState.manualViewSpan * factor).coerceIn(0.0005f, 0.05f)
                    NavigationStateHolder.update(navState.copy(manualViewSpan = newSpan))
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

        val currentTile = mapFeatures
        val currentAnchor = mapFeaturesAnchor

        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            val userScreenX = size.width / 2
            val userScreenY = (size.height / 2) + offsetValPx
            
            withTransform({
                rotate(mapRotationAnimatable.value, pivot = Offset(userScreenX, userScreenY))
                translate(top = offsetValPx)
            }) {
                if (currentTile != null && currentAnchor != null) {
                    drawTile(currentTile, currentAnchor.first, currentAnchor.second, currentAnchor.third)
                }

                if (navState.routePoints.isNotEmpty()) {
                    val routePath = Path()
                    val screenPoints = navState.routePoints.map { (lat, lon) ->
                        val x = (((lon - (effectiveLon - viewSpan)) / (2 * viewSpan)) * size.width).toFloat()
                        val y = (size.height - (((lat - (effectiveLat - viewSpan)) / (2 * viewSpan)) * size.height)).toFloat()
                        Offset(x, y)
                    }
                    
                    screenPoints.forEachIndexed { i, pt ->
                        if (i == 0) routePath.moveTo(pt.x, pt.y)
                        else routePath.lineTo(pt.x, pt.y)
                    }
                    
                    // Route shadow/casing
                    drawPath(
                        path = routePath, 
                        color = Color(0x33000000),
                        style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    
                    // Main route line
                    drawPath(
                        path = routePath, 
                        color = Color(0xFF3D5AFE),
                        style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    
                    // Highlight the current turn area on the route line
                    if (navState.isActive && navState.turnLat != 0.0) {
                        var bestTurnIdx = -1
                        var minDist = 0.001 // ~100m tolerance for path matching
                        
                        for (i in 0 until navState.routePoints.size) {
                            val d = hypot(navState.routePoints[i].first - navState.turnLat, 
                                          navState.routePoints[i].second - navState.turnLon)
                            if (d < minDist) {
                                minDist = d
                                bestTurnIdx = i
                            }
                        }

                        if (bestTurnIdx != -1) {
                            // Find range of points to highlight around the turn
                            val startIdx = (bestTurnIdx - 3).coerceAtLeast(0)
                            val endIdx = (bestTurnIdx + 3).coerceAtMost(screenPoints.size - 1)
                            
                            if (endIdx > startIdx) {
                                val highlightPath = Path()
                                highlightPath.moveTo(screenPoints[startIdx].x, screenPoints[startIdx].y)
                                for (i in startIdx + 1..endIdx) {
                                    highlightPath.lineTo(screenPoints[i].x, screenPoints[i].y)
                                }
                                
                                val turnPoint = Offset(
                                    (((navState.turnLon - (effectiveLon - viewSpan.toDouble())) / (2 * viewSpan.toDouble())) * size.width).toFloat(),
                                    (size.height - (((navState.turnLat - (effectiveLat - viewSpan.toDouble())) / (2 * viewSpan.toDouble())) * size.height)).toFloat()
                                )
                                val p1 = screenPoints[(bestTurnIdx - 1).coerceAtLeast(0)]
                                val p2 = screenPoints[(bestTurnIdx + 1).coerceAtMost(screenPoints.size - 1)]
                                val angle = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble()).toFloat()
                                drawRouteTurnMarker(
                                    center = turnPoint,
                                    angle = angle,
                                    carDirection = navState.carDirection,
                                    pedestrianDirection = navState.pedestrianDirection
                                )
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
                            imageVector = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), tint = Color.White
                        )
                        // Roundabout exit number (Phone uses specific bits/enums, we check exitNum)
                        if (navState.exitNum > 0 && (navState.carDirection in 10..12 || navState.carDirection == 9)) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.White, RoundedCornerShape(10.dp))
                                    .align(Alignment.Center),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = navState.exitNum.toString(),
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
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

        // USER MARKER
        Canvas(modifier = Modifier.fillMaxSize()) {
            val userScreenX = if (navState.isExploreMode) {
                (((navState.lon - (effectiveLon - viewSpan.toDouble())) / (2 * viewSpan.toDouble())) * size.width).toFloat()
            } else size.width / 2

            val userScreenY = if (navState.isExploreMode) {
                (size.height - (((navState.lat - (effectiveLat - viewSpan.toDouble())) / (2 * viewSpan.toDouble())) * size.height)).toFloat()
            } else (size.height / 2) + (verticalOffsetFraction * size.height)

            if (userScreenX in 0f..size.width && userScreenY in 0f..size.height) {
                val markerColor = Color(0xFF4CAF50)
                
                // Outer glow/shadow
                drawCircle(Color.Black.copy(alpha = 0.2f), radius = 9.dp.toPx(), center = Offset(userScreenX, userScreenY))
                // White border
                drawCircle(Color.White, radius = 7.5.dp.toPx(), center = Offset(userScreenX, userScreenY))
                // Main circle
                drawCircle(markerColor, radius = 6.dp.toPx(), center = Offset(userScreenX, userScreenY))
                
                withTransform({
                    translate(userScreenX, userScreenY)
                    rotate(if (navState.isExploreMode) 0f else 360f - mapRotationAnimatable.value)
                }) {
                    val arrowPath = Path().apply {
                        moveTo(0f, -13.dp.toPx()) // Tip points UP
                        lineTo(-6.5.dp.toPx(), 2.5.dp.toPx()) // Left base
                        lineTo(6.5.dp.toPx(), 2.5.dp.toPx()) // Right base
                        close()
                    }
                    // Arrow border
                    drawPath(arrowPath, Color.White, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
                    // Arrow fill
                    drawPath(arrowPath, markerColor)
                }
            }
        }
    }
}

private fun DrawScope.drawTile(features: ByteArray, centerLat: Double, centerLon: Double, viewSpan: Double) {
    val buffer = ByteBuffer.wrap(features).order(ByteOrder.LITTLE_ENDIAN)
    val pathsByType = mutableMapOf<Int, Path>()
    val pointsByType = mutableMapOf<Int, MutableList<Offset>>()

    while (buffer.hasRemaining()) {
        if (buffer.remaining() < 5) break
        val type = buffer.get().toInt()
        val count = buffer.getInt()
        if (buffer.remaining() < count * 16) break
        
        if (type >= 100) {
            val list = pointsByType.getOrPut(type) { mutableListOf() }
            for (i in 0 until count) {
                val lon = buffer.getDouble()
                val lat = buffer.getDouble()
                val x = (((lon - (centerLon - viewSpan)) / (2 * viewSpan)) * size.width).toFloat()
                val y = (size.height - (((lat - (centerLat - viewSpan)) / (2 * viewSpan)) * size.height)).toFloat()
                list.add(Offset(x, y))
            }
        } else {
            val mapPath = pathsByType.getOrPut(type) { Path() }
            for (i in 0 until count) {
                val lon = buffer.getDouble()
                val lat = buffer.getDouble()
                val x = (((lon - (centerLon - viewSpan)) / (2 * viewSpan)) * size.width).toFloat()
                val y = (size.height - (((lat - (centerLat - viewSpan)) / (2 * viewSpan)) * size.height)).toFloat()
                if (i == 0) mapPath.moveTo(x, y)
                else mapPath.lineTo(x, y)
                if (i == count - 1 && type in listOf(2, 3, 9)) mapPath.close()
            }
        }
    }

    val drawOrder = listOf(9, 3, 2, 8, 1, 7, 6, 5, 4)
    val sortedTypes = pathsByType.keys.sortedBy { type -> drawOrder.indexOf(type).let { if (it == -1) 99 else it } }

    for (type in sortedTypes) {
        val mapPath = pathsByType[type] ?: continue
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
            9 -> drawPath(mapPath, Color(0xFFD4E3A9)) // Greenery (More vibrant/natural green)
            else -> {
                drawPath(mapPath, Color(0xFFDEDBD0), style = Stroke(width = 4.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFAFAFA), style = Stroke(width = 3.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    
    // Draw POIs as stylized markers
    for ((type, points) in pointsByType) {
        val color = when (type) {
            100 -> Color(0xFFFF9100) // Eat (Orange/Peach)
            101 -> Color(0xFF2979FF) // Transportation (Bright Blue)
            102 -> Color(0xFFFF6D00) // Hotel (Deep Orange)
            103 -> Color(0xFF00C853) // ATM (Green)
            105 -> Color(0xFF546E7A) // Main POIs (Blue-grey)
            else -> Color(0xFF7B1FA2) // All Details (Purple)
        }
        for (p in points) {
            // Marker shadow
            drawCircle(Color.Black.copy(alpha = 0.15f), radius = 6.5.dp.toPx(), center = p.copy(y = p.y + 0.5.dp.toPx()))
            // Marker white ring
            drawCircle(Color.White, radius = 5.5.dp.toPx(), center = p)
            // Marker colored border
            drawCircle(color, radius = 5.5.dp.toPx(), center = p, style = Stroke(width = 1.2.dp.toPx()))
            // Inner colored dot
            drawCircle(color, radius = 2.2.dp.toPx(), center = p)
        }
    }
}

private fun DrawScope.drawRouteTurnMarker(
    center: Offset,
    angle: Float,
    carDirection: Int,
    pedestrianDirection: Int
) {
    val direction = if (pedestrianDirection != 0 && pedestrianDirection != 1) pedestrianDirection else carDirection
    val shaftLength = 19.dp.toPx()
    val shaftWidth = 4.dp.toPx()
    val arrowHeadWidth = 9.dp.toPx()
    val arrowHeadLength = 11.dp.toPx()

    val markerPath = when (direction) {
        2, 3, 4, 15, 16 -> Path().apply {
            moveTo(-shaftWidth, shaftLength)
            lineTo(shaftWidth, shaftLength)
            lineTo(shaftWidth, 3.dp.toPx())
            lineTo(shaftWidth + arrowHeadWidth * 0.5f, 3.dp.toPx())
            lineTo(shaftWidth + arrowHeadWidth * 0.5f, -arrowHeadLength)
            lineTo(shaftWidth, -arrowHeadLength)
            lineTo(0f, -shaftLength - 3.dp.toPx())
            lineTo(-shaftWidth, -arrowHeadLength)
            lineTo(-shaftWidth - arrowHeadWidth * 0.5f, -arrowHeadLength)
            lineTo(-shaftWidth - arrowHeadWidth * 0.5f, 3.dp.toPx())
            lineTo(-shaftWidth, 3.dp.toPx())
            close()
        }
        5, 6, 7 -> Path().apply {
            moveTo(-shaftWidth, shaftLength)
            lineTo(shaftWidth, shaftLength)
            lineTo(shaftWidth, 3.dp.toPx())
            lineTo(-shaftWidth - arrowHeadWidth * 0.5f, -arrowHeadLength)
            lineTo(-shaftWidth, -arrowHeadLength)
            lineTo(0f, -shaftLength - 3.dp.toPx())
            lineTo(shaftWidth, -arrowHeadLength)
            lineTo(shaftWidth + arrowHeadWidth * 0.5f, -arrowHeadLength)
            lineTo(shaftWidth + arrowHeadWidth * 0.5f, 3.dp.toPx())
            lineTo(-shaftWidth, 3.dp.toPx())
            close()
        }
        8, 9, 10, 11, 12 -> Path().apply {
            addArc(
                androidx.compose.ui.geometry.Rect(
                    left = -shaftLength,
                    top = -shaftLength,
                    right = shaftLength,
                    bottom = shaftLength
                ),
                210f,
                300f
            )
            moveTo(shaftLength * 0.55f, -shaftLength * 0.25f)
            lineTo(shaftLength * 0.95f, -shaftLength * 0.55f)
            lineTo(shaftLength * 0.72f, -shaftLength * 0.02f)
            close()
        }
        14 -> Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    left = -7.dp.toPx(),
                    top = -7.dp.toPx(),
                    right = 7.dp.toPx(),
                    bottom = 7.dp.toPx()
                )
            )
        }
        else -> Path().apply {
            moveTo(0f, -shaftLength)
            lineTo(shaftWidth, shaftLength - 3.dp.toPx())
            lineTo(0f, shaftLength * 0.45f)
            lineTo(-shaftWidth, shaftLength - 3.dp.toPx())
            close()
        }
    }

    withTransform({
        translate(center.x, center.y)
        rotate(Math.toDegrees(angle.toDouble()).toFloat(), pivot = Offset.Zero)
    }) {
        if (direction == 14) {
            drawCircle(Color.White.copy(alpha = 0.96f), radius = 8.dp.toPx(), center = Offset.Zero)
            drawCircle(Color(0xFF3D5AFE), radius = 4.dp.toPx(), center = Offset.Zero)
        } else {
            drawPath(markerPath, Color.White.copy(alpha = 0.95f))
            drawPath(markerPath, Color(0xFF3D5AFE).copy(alpha = 0.12f))
        }
    }
}

private fun lonToTileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
private fun latToTileY(lat: Double, zoom: Int): Int = floor((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)).toInt()
