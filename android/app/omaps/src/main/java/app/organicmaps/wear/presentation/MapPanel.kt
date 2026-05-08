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
    var pendingRequestId by remember { mutableStateOf(0L) }

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

    LaunchedEffect(currentTileX, currentTileY, useOfflineMaps, requestViewSpan, navState.poiCategoriesMask) {
        val cachedFeatures = MapTileStateHolder.getCachedFeatures(effectiveLat, effectiveLon)
        if (cachedFeatures != null && !navState.isExploreMode) {
            mapFeatures = cachedFeatures
            return@LaunchedEffect
        }

        if (useOfflineMaps && app.isFullyInitialized) {
            val features = Framework.nativeGetWearMapFeatures(
                effectiveLat - requestViewSpan,
                effectiveLon - requestViewSpan,
                effectiveLat + requestViewSpan,
                effectiveLon + requestViewSpan,
                19, // Higher detail for local extraction
                navState.routerType,
                navState.poiCategoriesMask
            )
            if (features.isNotEmpty()) {
                mapFeatures = features
                if (!navState.isExploreMode) MapTileStateHolder.updateCache(effectiveLat, effectiveLon, features)
            } else if (!navState.isPhoneConnected) {
                mapFeatures = null
            }
        }
        
        if (!useOfflineMaps || (useOfflineMaps && mapFeatures == null && navState.isPhoneConnected)) {
            val requestId = System.nanoTime()
            pendingRequestId = requestId
            WearCommandService.requestMapTile(
                context,
                requestId,
                effectiveLat - requestViewSpan,
                effectiveLon - requestViewSpan,
                effectiveLat + requestViewSpan,
                effectiveLon + requestViewSpan,
                navState.routerType
            )
        }
    }

    LaunchedEffect(streamedTile, pendingRequestId) {
        val tile = streamedTile ?: return@LaunchedEffect
        if (tile.requestId != pendingRequestId) return@LaunchedEffect
        mapFeatures = tile.features
        MapTileStateHolder.updateCache(effectiveLat, effectiveLon, tile.features)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFFF1EEE8))
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            val userScreenX = size.width / 2
            val userScreenY = (size.height / 2) + offsetValPx
            
            withTransform({
                rotate(mapRotationAnimatable.value, pivot = Offset(userScreenX, userScreenY))
                translate(top = offsetValPx)
            }) {
                currentTile?.let { features ->
                    drawTile(features, effectiveLat, effectiveLon, viewSpan.toDouble())
                }

                if (navState.routePoints.isNotEmpty()) {
                    val routePath = Path()
                    val screenPoints = navState.routePoints.mapIndexed { i, point ->
                        val (lat, lon) = point
                        val x = (((lon - (effectiveLon - viewSpan)) / (2 * viewSpan)) * size.width).toFloat()
                        val y = (size.height - (((lat - (effectiveLat - viewSpan)) / (2 * viewSpan)) * size.height)).toFloat()
                        val pointOffset = Offset(x, y)
                        if (i == 0) routePath.moveTo(x, y)
                        else routePath.lineTo(x, y)
                        pointOffset
                    }
                    
                    drawPath(
                        path = routePath, 
                        color = Color(0xFF3D5AFE),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                                
                                // Glowing highlight for the turn area
                                drawPath(
                                    path = highlightPath,
                                    color = Color.White,
                                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                drawPath(
                                    path = highlightPath,
                                    color = Color(0xFF3D5AFE),
                                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                
                                // Draw a direction arrow at the exact junction
                                val p1 = screenPoints[bestTurnIdx]
                                if (bestTurnIdx < screenPoints.size - 1) {
                                    val p2 = screenPoints[bestTurnIdx + 1]
                                    val angle = atan2(p2.y - p1.y, p2.x - p1.x)
                                    val arrowHead = Path().apply {
                                        moveTo(0f, 0f) // Tip at (0,0)
                                        lineTo(-12.dp.toPx(), -7.dp.toPx())
                                        lineTo(-9.dp.toPx(), 0f)
                                        lineTo(-12.dp.toPx(), 7.dp.toPx())
                                        close()
                                    }
                                    withTransform({
                                        translate(p1.x, p1.y)
                                        rotate(Math.toDegrees(angle.toDouble()).toFloat(), pivot = Offset(0f, 0f))
                                    }) {
                                        drawPath(arrowHead, Color.White)
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
                drawCircle(markerColor, radius = 7.dp.toPx(), center = Offset(userScreenX, userScreenY))
                val arrowPath = Path().apply {
                    moveTo(userScreenX, userScreenY - 11.dp.toPx())
                    lineTo(userScreenX - 5.dp.toPx(), userScreenY + 3.dp.toPx())
                    lineTo(userScreenX + 5.dp.toPx(), userScreenY + 3.dp.toPx())
                    close()
                }
                drawPath(arrowPath, markerColor)
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
            }
        }
    }

    val drawOrder = listOf(3, 2, 1, 7, 6, 5, 4)
    val sortedTypes = pathsByType.keys.sortedBy { type -> drawOrder.indexOf(type).let { if (it == -1) 99 else it } }

    for (type in sortedTypes) {
        val mapPath = pathsByType[type] ?: continue
        when (type) {
            1 -> { // Residential
                drawPath(mapPath, Color(0xFFBDBDBD), style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFFFFFF), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            2 -> drawPath(mapPath, Color(0xFFE0E0E0)) // Buildings
            3 -> drawPath(mapPath, Color(0xFFA2D9FF)) // Water
            4 -> { // Motorway/Trunk
                drawPath(mapPath, Color(0xFFD84315), style = Stroke(width = 8.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFF8A65), style = Stroke(width = 6.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            5 -> { // Primary
                drawPath(mapPath, Color(0xFFF9A825), style = Stroke(width = 7.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFFF176), style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            6 -> { // Secondary
                drawPath(mapPath, Color(0xFF9E9E9E), style = Stroke(width = 6.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFF5F5F5), style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            7 -> { // Tertiary
                drawPath(mapPath, Color(0xFFAAAAAA), style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFFFFFF), style = Stroke(width = 4.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            else -> {
                drawPath(mapPath, Color(0xFFBDBDBD), style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(mapPath, Color(0xFFFFFFFF), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    
    // Draw POIs
    for ((type, points) in pointsByType) {
        val color = when (type) {
            100 -> Color(0xFFE91E63) // Eat
            101 -> Color(0xFF2196F3) // Transportation
            102 -> Color(0xFFFF9800) // Hotel
            103 -> Color(0xFF4CAF50) // ATM
            105 -> Color(0xFFF44336) // Main POIs
            else -> Color(0xFF9C27B0) // All Details
        }
        for (p in points) {
            drawCircle(Color.Black, radius = 5.dp.toPx(), center = p)
            drawCircle(color, radius = 4.dp.toPx(), center = p)
        }
    }
}

private fun lonToTileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
private fun latToTileY(lat: Double, zoom: Int): Int = floor((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)).toInt()
