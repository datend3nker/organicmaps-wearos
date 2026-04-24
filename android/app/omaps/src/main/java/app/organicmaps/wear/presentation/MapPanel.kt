package app.organicmaps.wear.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.navigation.SensorViewModel

@Composable
fun MapPanel() {
    val context = LocalContext.current
    val app = context.applicationContext as WearApplication
    val navState by NavigationStateHolder.state.collectAsState()
    val tilesSignal by MapTileStateHolder.tilesUpdateSignal.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()

    val centerLat = if (navState.lat != 0.0) navState.lat else 48.2082
    val centerLon = if (navState.lon != 0.0) navState.lon else 16.3738
    val zoom = 16

    val currentTileX = lonToTileX(centerLon, zoom)
    val currentTileY = latToTileY(centerLat, zoom)

    // PROACTIVE ADAPTIVE ZOOM
    // Start zooming in earlier (400m instead of 300m) and finish earlier (60m)
    val targetViewSpan = when (navState.routerType) {
        0 -> { // Car
            if (navState.isActive && navState.distToTurnMeters in 0.0..400.0) {
                val t = (navState.distToTurnMeters.coerceIn(60.0, 400.0) - 60.0) / 340.0
                0.0015 + (0.008 - 0.0015) * t
            } else 0.008
        }
        2 -> 0.005 // Bike
        else -> 0.003 // Walk
    }
    
    val viewSpan by animateFloatAsState(
        targetValue = targetViewSpan.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessMedium), // FASTER ZOOM
        label = "zoom"
    )

    // SNAPPY & STABLE ROTATION
    val mapRotationAnimatable = remember { Animatable(0f) }
    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive) {
        val targetDeg = if (navState.isActive && navState.speedMps > 1.4 && navState.bearing >= 0) {
            -navState.bearing
        } else {
            -compassHeading
        }
        
        var diff = targetDeg - mapRotationAnimatable.value
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        
        mapRotationAnimatable.animateTo(
            targetValue = mapRotationAnimatable.value + diff,
            animationSpec = spring(
                stiffness = 350f, // SNAPPY
                dampingRatio = Spring.DampingRatioNoBouncy
            )
        )
    }

    // Perspective Shift
    val verticalOffsetFractionTarget = if (navState.routerType == 0 && navState.isActive) 0.35f else 0.0f
    val verticalOffsetFraction by animateFloatAsState(
        targetValue = verticalOffsetFractionTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offset"
    )

    LaunchedEffect(currentTileX, currentTileY, navState.offlineMapsEnabled, viewSpan) {
        val range = if (navState.routerType == 0) 2 else 1
        for (dx in -range..range) {
            for (dy in -range..range) {
                val tx = currentTileX + dx
                val ty = currentTileY + dy
                if (MapTileStateHolder.getTile(tx, ty, zoom) == null) {
                    val minLon = tileXToLon(tx, zoom)
                    val maxLon = tileXToLon(tx + 1, zoom)
                    val maxLat = tileYToLat(ty, zoom)
                    val minLat = tileYToLat(ty + 1, zoom)
                    if (navState.offlineMapsEnabled && app.isFullyInitialized) {
                        val features = Framework.nativeGetWearMapFeatures(minLat, minLon, maxLat, maxLon, zoom)
                        if (features.isNotEmpty()) MapTileStateHolder.update(tx, ty, zoom, features)
                    } else if (!navState.offlineMapsEnabled) {
                        WearCommandService.requestMapTile(context, tx, ty, zoom, minLat, minLon, maxLat, maxLon)
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().clipToBounds().background(Color(0xFF242323)), contentAlignment = Alignment.Center) {
        val currentTile = MapTileStateHolder.getTile(currentTileX, currentTileY, zoom)
        @Suppress("UNUSED_VARIABLE")
        val signal = tilesSignal

        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            val userScreenX = size.width / 2
            val userScreenY = (size.height / 2) + offsetValPx
            
            withTransform({
                rotate(mapRotationAnimatable.value, pivot = Offset(userScreenX, userScreenY))
                translate(top = offsetValPx)
            }) {
                // 1. Draw Map Features
                val range = if (navState.routerType == 0) 2 else 1
                for (dx in -range..range) {
                    for (dy in -range..range) {
                        val tx = currentTileX + dx
                        val ty = currentTileY + dy
                        MapTileStateHolder.getTile(tx, ty, zoom)?.let { features ->
                            drawTile(features, tx, ty, zoom, centerLat, centerLon, viewSpan.toDouble())
                        }
                    }
                }

                // 2. Draw Route & Turns
                if (navState.routePoints.isNotEmpty()) {
                    val routePath = Path()
                    val turnPath = Path()
                    
                    var turnPointIdx = -1
                    
                    val screenPoints = navState.routePoints.mapIndexed { i, (lat, lon) ->
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
                    
                    // Highlight Turn Segment
                    if (navState.isActive && turnPointIdx != -1) {
                        val startH = (turnPointIdx - 2).coerceAtLeast(0)
                        val endH = (turnPointIdx + 1).coerceAtMost(screenPoints.size - 1)
                        for (i in startH..endH) {
                            val p = screenPoints[i]
                            if (i == startH) turnPath.moveTo(p.x, p.y)
                            else turnPath.lineTo(p.x, p.y)
                        }
                    }
                    
                    // Main Route line
                    drawPath(
                        path = routePath, 
                        color = Color(0xFF3D5AFE),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    
                    // Glow for next turn
                    if (!turnPath.isEmpty) {
                        drawPath(path = turnPath, color = Color.White, style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(path = turnPath, color = Color(0xFF3D5AFE), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        
                        // FILLED TURN INDICATOR
                        val tp = screenPoints[turnPointIdx]
                        drawCircle(color = Color.White, radius = 6.dp.toPx(), center = tp)
                        drawCircle(color = Color(0xFF3D5AFE), radius = 4.dp.toPx(), center = tp)
                        drawCircle(color = Color.White, radius = 2.dp.toPx(), center = tp)
                    }
                }
            }
        }
        
        // Navigation Overlay
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
            drawCircle(color = Color(0xFF00E5FF), radius = 7.dp.toPx(), center = Offset(size.width / 2, userMarkerY))
            val arrowPath = Path().apply {
                moveTo(size.width / 2, userMarkerY - 11.dp.toPx())
                lineTo(size.width / 2 - 5.dp.toPx(), userMarkerY + 3.dp.toPx())
                lineTo(size.width / 2 + 5.dp.toPx(), userMarkerY + 3.dp.toPx())
                close()
            }
            drawPath(path = arrowPath, color = Color(0xFF00E5FF))
        }

        if (currentTile == null && !navState.offlineMapsEnabled && navState.routerType != 0) {
            Text(text = "Loading...", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center).padding(top = 40.dp), style = MaterialTheme.typography.caption3)
        }
    }
}

private fun DrawScope.drawTile(features: ByteArray, tx: Int, ty: Int, zoom: Int, centerLat: Double, centerLon: Double, viewSpan: Double) {
    val buffer = ByteBuffer.wrap(features).order(ByteOrder.LITTLE_ENDIAN)
    while (buffer.hasRemaining()) {
        if (buffer.remaining() < 5) break
        val type = buffer.get()
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
        when (type.toInt()) {
            1 -> drawPath(path = mapPath, color = Color(0xFF383838), style = Stroke(width = 2.dp.toPx())) // Roads
            2 -> drawPath(path = mapPath, color = Color(0xFF2B2B2B)) // Buildings
            else -> drawPath(path = mapPath, color = Color(0xFF1C2A33)) // Water
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
