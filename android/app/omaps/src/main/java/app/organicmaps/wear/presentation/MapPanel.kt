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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Icon
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.wear.MapTileStateHolder
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.NavigationIcons
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import android.content.Context

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.navigation.SensorViewModel

@Composable
fun MapPanel() {
    val context = LocalContext.current
    val app = context.applicationContext as WearApplication
    val navState by NavigationStateHolder.state.collectAsState()
    val streamedTile by MapTileStateHolder.mapTile.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()

    val centerLat = if (navState.lat != 0.0) navState.lat else 48.2082
    val centerLon = if (navState.lon != 0.0) navState.lon else 16.3738
    val zoom = 16

    val currentTileX = lonToTileX(centerLon, zoom)
    val currentTileY = latToTileY(centerLat, zoom)
    var mapFeatures by remember { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var pendingRequestId by remember { mutableStateOf(0L) }

    // IMPROVED AUTO-ZOOM (Phone App Logic)
    val targetViewSpan = remember(navState.speedMps, navState.distToTurnMeters, navState.routerType, navState.isActive) {
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
        animationSpec = tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "zoom"
    )

    // SNAPPY STABILIZED ROTATION
    val mapRotationAnimatable = remember { Animatable(0f) }
    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive) {
        val targetDeg = if (navState.isActive && navState.speedMps > 1.5 && navState.bearing >= 0) {
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

    val verticalOffsetFractionTarget = if (navState.routerType == 0 && navState.isActive) 0.35f else 0.0f
    val verticalOffsetFraction by animateFloatAsState(
        targetValue = verticalOffsetFractionTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offset"
    )

    val useOfflineMaps = navState.offlineMapsEnabled || !navState.isPhoneConnected

    LaunchedEffect(currentTileX, currentTileY, useOfflineMaps, viewSpan) {
        val cachedFeatures = MapTileStateHolder.getCachedFeatures(centerLat, centerLon)
        if (cachedFeatures != null) {
            MapTileStateHolder.updateCache(centerLat, centerLon, cachedFeatures)
            return@LaunchedEffect
        }

        if (useOfflineMaps && app.isFullyInitialized) {
            val features = Framework.nativeGetWearMapFeatures(
                centerLat - viewSpan,
                centerLon - viewSpan,
                centerLat + viewSpan,
                centerLon + viewSpan,
                16
            )
            if (features.isNotEmpty()) {
                mapFeatures = features
                MapTileStateHolder.updateCache(centerLat, centerLon, features)
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
                centerLat - viewSpan,
                centerLon - viewSpan,
                centerLat + viewSpan,
                centerLon + viewSpan
            )
        }
    }

    LaunchedEffect(streamedTile, pendingRequestId) {
        val tile = streamedTile ?: return@LaunchedEffect
        if (tile.requestId != pendingRequestId) {
            return@LaunchedEffect
        }

        mapFeatures = tile.features
        loading = false
        MapTileStateHolder.updateCache(centerLat, centerLon, tile.features)
    }

    Box(modifier = Modifier.fillMaxSize().clipToBounds().background(Color(0xFFF1EEE8)), contentAlignment = Alignment.Center) {
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
                    drawTile(features, currentTileX, currentTileY, zoom, centerLat, centerLon, viewSpan.toDouble())
                }

                if (navState.routePoints.isNotEmpty()) {
                    val routePath = Path()
                    var turnPointIdx = -1
                    
                    val screenPoints = navState.routePoints.mapIndexed { i: Int, point: Pair<Double, Double> ->
                        val (lat, lon) = point
                        val x = ((lon - (centerLon - viewSpan)) / (2 * viewSpan)) * size.width
                        val y = size.height - (((lat - (centerLat - viewSpan)) / (2 * viewSpan)) * size.height)
                        val point = Offset(x.toFloat(), y.toFloat())
                        
                        if (i == 0) routePath.moveTo(point.x, point.y)
                        else routePath.lineTo(point.x, point.y)

                        if (turnPointIdx == -1 && navState.turnLat != 0.0 && 
                            abs(lat - navState.turnLat) < 0.00001 && abs(lon - navState.turnLon) < 0.00001) {
                            turnPointIdx = i
                        }
                        point
                    }
                    
                    drawPath(
                        path = routePath, 
                        color = Color(0xFF3D5AFE),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    
                    if (navState.isActive) {
                        for (i in 1 until screenPoints.size - 1) {
                            val p0 = screenPoints[i - 1]
                            val p1 = screenPoints[i]
                            val p2 = screenPoints[i + 1]
                            
                            val angle1 = atan2(p1.y - p0.y, p1.x - p0.x)
                            val angle2 = atan2(p2.y - p1.y, p2.x - p1.x)
                            var diff = Math.toDegrees((angle2 - angle1).toDouble())
                            while (diff < -180) diff += 360
                            while (diff > 180) diff -= 360
                            
                            if (abs(diff) > 25.0) {
                                val dist01 = hypot(p1.x - p0.x, p1.y - p0.y)
                                val dist12 = hypot(p2.x - p1.x, p2.y - p1.y)
                                
                                val backDist = minOf(40f, dist01 * 0.8f)
                                val fwdDist = minOf(40f, dist12 * 0.8f)
                                
                                val startX = p1.x - backDist * cos(angle1)
                                val startY = p1.y - backDist * sin(angle1)
                                
                                val endX = p1.x + fwdDist * cos(angle2)
                                val endY = p1.y + fwdDist * sin(angle2)
                                
                                val turnSegmentPath = Path().apply {
                                    moveTo(startX, startY)
                                    lineTo(p1.x, p1.y)
                                    lineTo(endX, endY)
                                }
                                
                                drawPath(
                                    path = turnSegmentPath, 
                                    color = Color(0xFF000000),
                                    style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                drawPath(
                                    path = turnSegmentPath, 
                                    color = Color(0xFFFFFFFF),
                                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                
                                withTransform({
                                    rotate(Math.toDegrees(angle2.toDouble()).toFloat() + 90f, pivot = Offset(endX, endY))
                                }) {
                                    val arrowPath = Path().apply {
                                        moveTo(endX, endY - 6.dp.toPx())
                                        lineTo(endX - 7.dp.toPx(), endY + 7.dp.toPx())
                                        lineTo(endX + 7.dp.toPx(), endY + 7.dp.toPx())
                                        close()
                                    }
                                    drawPath(path = arrowPath, color = Color(0xFFFFFFFF))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (navState.isActive && navState.distToTurn.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 26.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection),
                        contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White
                    )
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

        // STATIC USER MARKER
        Canvas(modifier = Modifier.fillMaxSize()) {
            val userMarkerY = (size.height / 2) + (verticalOffsetFraction * size.height)
            drawCircle(color = Color(0xFF3D5AFE), radius = 7.dp.toPx(), center = Offset(size.width / 2, userMarkerY))
            val arrowPath = Path().apply {
                moveTo(size.width / 2, userMarkerY - 11.dp.toPx())
                lineTo(size.width / 2 - 5.dp.toPx(), userMarkerY + 3.dp.toPx())
                lineTo(size.width / 2 + 5.dp.toPx(), userMarkerY + 3.dp.toPx())
                close()
            }
            drawPath(path = arrowPath, color = Color(0xFF3D5AFE))
        }

        if (currentTile == null && navState.offlineMapsEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Map missing offline.",
                        color = Color.White,
                        style = MaterialTheme.typography.caption2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        androidx.wear.compose.material.Button(
                            onClick = {
                                val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putBoolean("forceWatchOfflineMaps", false).apply()
                                NavigationStateHolder.update(navState.copy(offlineMapsEnabled = false))
                                WearCommandService.syncPreferences(context)
                            },
                            modifier = Modifier.height(32.dp).weight(1f),
                            colors = androidx.wear.compose.material.ButtonDefaults.primaryButtonColors()
                        ) {
                            Text("Stream", style = MaterialTheme.typography.caption3)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        androidx.wear.compose.material.Button(
                            onClick = {
                                NavigationStateHolder.update(navState.copy(openMapManager = true))
                            },
                            modifier = Modifier.height(32.dp).weight(1f),
                            colors = androidx.wear.compose.material.ButtonDefaults.secondaryButtonColors()
                        ) {
                            Text("Manage", style = MaterialTheme.typography.caption3)
                        }
                    }
                }
            }
        }
else if (currentTile == null && !navState.isPhoneConnected) {
            Text(
                text = "Waiting for connection...",
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).padding(top = 40.dp),
                style = MaterialTheme.typography.caption3
            )
        }
    }
}

private fun DrawScope.drawTile(features: ByteArray, tx: Int, ty: Int, zoom: Int, centerLat: Double, centerLon: Double, viewSpan: Double) {
    val buffer = ByteBuffer.wrap(features).order(ByteOrder.LITTLE_ENDIAN)
    val pathsByType = mutableMapOf<Int, MutableList<Path>>()

    while (buffer.hasRemaining()) {
        if (buffer.remaining() < 5) break
        val type = buffer.get().toInt()
        val count = buffer.getInt()
        if (buffer.remaining() < count * 16) break
        val mapPath = Path()
        for (i in 0 until count) {
            val lon = buffer.getDouble()
            val lat = buffer.getDouble()
            val x = ((lon - (centerLon - viewSpan)) / (2 * viewSpan)) * size.width
            val y = size.height - (((lat - (centerLat - viewSpan)) / (2 * viewSpan)) * size.height)
            if (i == 0) mapPath.moveTo(x.toFloat(), y.toFloat())
            else mapPath.lineTo(x.toFloat(), y.toFloat())
        }
        pathsByType.getOrPut(type) { mutableListOf() }.add(mapPath)
    }

    val drawOrder = listOf(3, 2, 1, 7, 6, 5, 4)
    val currentTypes = pathsByType.keys.toList()
    val sortedTypes = currentTypes.sortedBy { drawOrder.indexOf(it).let { idx -> if (idx == -1) 99 else idx } }

    for (type in sortedTypes) {
        val paths = pathsByType[type] ?: continue
        for (mapPath in paths) {
            when (type) {
                1 -> { // Residential
                    drawPath(path = mapPath, color = Color(0xFFC8C8C8), style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = mapPath, color = Color(0xFFFFFFFF), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                2 -> drawPath(path = mapPath, color = Color(0xFFDCD7CE)) // Buildings
                3 -> drawPath(path = mapPath, color = Color(0xFFADE1FF)) // Water
                4 -> { // Motorway/Trunk
                    drawPath(path = mapPath, color = Color(0xFFE0812F), style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = mapPath, color = Color(0xFFFFB366), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                5 -> { // Primary
                    drawPath(path = mapPath, color = Color(0xFFE0BB68), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = mapPath, color = Color(0xFFFFD580), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                6 -> { // Secondary
                    drawPath(path = mapPath, color = Color(0xFFC8C8C8), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = mapPath, color = Color(0xFFFFFFFF), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                7 -> { // Tertiary
                    drawPath(path = mapPath, color = Color(0xFFC8C8C8), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = mapPath, color = Color(0xFFFFFFFF), style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                else -> {
                    drawPath(path = mapPath, color = Color(0xFFC8C8C8), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path = mapPath, color = Color(0xFFFFFFFF), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

private fun lonToTileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
private fun latToTileY(lat: Double, zoom: Int): Int = floor((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)).toInt()
private fun tileXToLon(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom).toDouble() * 360.0 - 180.0
private fun tileYToLat(y: Int, zoom: Int): Double {
    val n = PI - 2.0 * PI * y.toDouble() / (1 shl zoom).toDouble()
    return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
}
private fun msDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 12742000.0 * atan2(sqrt(a), sqrt(1 - a))
}
