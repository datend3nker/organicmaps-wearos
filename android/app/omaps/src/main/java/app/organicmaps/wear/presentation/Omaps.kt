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
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.view.KeyEvent

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

class Omaps : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

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
        
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            mapEnabled = isMapEnabled,
            watchLocalMode = isOfflineMapsEnabled,
            routerType = routerType,
            poiCategoriesMask = poiMask
        ))
        
        setContent {
            WearApp()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val navState = NavigationStateHolder.state.value
        if (navState.isActive && navState.mapEnabled && navState.isExploreMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_NAVIGATE_IN -> {
                    val newSpan = (navState.manualViewSpan * 0.8f).coerceIn(0.0005f, 0.05f)
                    NavigationStateHolder.update(navState.copy(manualViewSpan = newSpan))
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_NAVIGATE_OUT -> {
                    val newSpan = (navState.manualViewSpan * 1.2f).coerceIn(0.0005f, 0.05f)
                    NavigationStateHolder.update(navState.copy(manualViewSpan = newSpan))
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
    
    val pagerState = remember(isNavigating, isMapEnabled) {
        PagerState(
            pageCount = { 
                if (isNavigating) {
                    if (isMapEnabled) 3 else 2
                } else 3 
            }
        )
    }

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
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !navState.isExploreMode
                ) { page ->
                    when (page) {
                        0 -> SearchScreen()
                        1 -> app.organicmaps.wear.presentation.downloads.MapManagerScreen()
                        2 -> app.organicmaps.wear.presentation.settings.SettingsScreen()
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
            
            // Status Indicators
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
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
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Text(
                            if (navState.isRouteBuilding) {
                                "Calculating ${navState.routeBuildProgress}%"
                            } else {
                                "Start Navigation"
                            },
                            style = MaterialTheme.typography.button
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
            WearCommandService.stopNavigation(context)
            NavigationStateHolder.update(navState.copy(isActive = false))
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
