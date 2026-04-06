package app.organicmaps.wear.presentation

import android.os.Bundle
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.Text
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

class Omaps : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        
        // Initialize state from prefs
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        val isMapEnabled = prefs.getBoolean("mapEnabled", false)
        if (NavigationStateHolder.state.value.mapEnabled != isMapEnabled) {
            NavigationStateHolder.update(NavigationStateHolder.state.value.copy(mapEnabled = isMapEnabled))
        }
        
        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    val navState by NavigationStateHolder.state.collectAsState()
    val isNavigating = navState.isActive
    val isMapEnabled = navState.mapEnabled
    
    val pagerState = rememberPagerState(pageCount = { 
        if (isNavigating) {
            if (isMapEnabled) 3 else 2
        } else 3 
    })

    androidx.compose.runtime.LaunchedEffect(navState.openMapManager) {
        if (navState.openMapManager && !isNavigating) {
            pagerState.animateScrollToPage(1)
            NavigationStateHolder.update(navState.copy(openMapManager = false))
        }
    }

    OrganicMapsTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isNavigating) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> SearchScreen(onSearchClick = {})
                        1 -> app.organicmaps.wear.presentation.downloads.MapManagerScreen()
                        2 -> app.organicmaps.wear.presentation.settings.SettingsScreen()
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
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
        onCancelClick = { WearCommandService.stopNavigation(context) },
        deviceRotation = deviceRotation
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

@Composable
fun MapPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    
    val centerLat = if (navState.lat != 0.0) navState.lat else 48.2082
    val centerLon = if (navState.lon != 0.0) navState.lon else 16.3738
    val span = 0.01 
    
    var mapFeatures by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    var loaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var downloadStatus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(centerLat, centerLon) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                wearApp.waitForInitializationSuspend()
                
                val countryId = app.organicmaps.sdk.downloader.MapManager.nativeFindCountry(centerLat, centerLon)
                if (countryId != null) {
                    val initStatus = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(countryId)
                    if (initStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
                        app.organicmaps.sdk.downloader.MapManager.startDownload(countryId)
                        app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                        
                        while(true) {
                            val item = app.organicmaps.sdk.downloader.CountryItem.fill(countryId)
                            if (item.status == app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
                                downloadStatus = ""
                                break
                            } else if (item.status == app.organicmaps.sdk.downloader.CountryItem.STATUS_FAILED) {
                                downloadStatus = "Map Download Failed"
                                break
                            } else {
                                downloadStatus = "Downloading Map: ${item.progress.toInt()}%"
                            }
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                }
                
                mapFeatures = app.organicmaps.sdk.Framework.nativeGetWearMapFeatures(
                    centerLat - span, centerLon - span, 
                    centerLat + span, centerLon + span, 
                    17
                )
                loaded = true
            } catch (e: Throwable) { 
                e.printStackTrace()
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = androidx.compose.ui.graphics.Color(0xFF1E1E1E))
            
            val features = mapFeatures
            if (features != null && features.isNotEmpty()) {
                val buffer = java.nio.ByteBuffer.wrap(features).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (buffer.hasRemaining()) {
                    val type = buffer.get()
                    val count = buffer.getInt()
                    
                    val mapPath = androidx.compose.ui.graphics.Path()
                    for (i in 0 until count) {
                        val lon = buffer.getDouble()
                        val lat = buffer.getDouble()
                        
                        val x = ((lon - (centerLon - span)) / (2 * span)) * size.width
                        val y = size.height - (((lat - (centerLat - span)) / (2 * span)) * size.height)
                        
                        if (i == 0) mapPath.moveTo(x.toFloat(), y.toFloat())
                        else mapPath.lineTo(x.toFloat(), y.toFloat())
                    }
                    
                    if (type.toInt() == 1) {
                        drawPath(path = mapPath, color = androidx.compose.ui.graphics.Color.Gray, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                    } else if (type.toInt() == 2) {
                        drawPath(path = mapPath, color = androidx.compose.ui.graphics.Color(0xFF2A2A2A)) 
                    }
                }
            } else if (!loaded) {
                // loading
            }
            
            drawCircle(
                color = androidx.compose.ui.graphics.Color.Cyan,
                radius = 12f,
                center = center
            )
        }
        
        if (downloadStatus.isNotEmpty()) {
            androidx.wear.compose.material.Chip(
                onClick = {},
                colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(),
                label = { Text(downloadStatus, color = androidx.compose.ui.graphics.Color.White) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
        } else {
            Text(
                text = "Standalone Vector Engine",
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                style = androidx.wear.compose.material.MaterialTheme.typography.caption3
            )
        }
    }
}
