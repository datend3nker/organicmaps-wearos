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
import androidx.compose.foundation.background
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.ui.graphics.Color
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.presentation.navigation.NavigationScreen
import app.organicmaps.wear.presentation.navigation.SensorViewModel
import app.organicmaps.wear.presentation.navigation.StatsScreen
import app.organicmaps.wear.presentation.search.SearchScreen
import app.organicmaps.wear.presentation.theme.OrganicMapsTheme
import app.organicmaps.wear.presentation.MapPanel
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
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
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
    
    NavigationScreen(
        distanceToNextTurn = navState.distToTurn,
        turnIcon = app.organicmaps.wear.NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection), 
        remainingTime = navState.nextStreet,
        onCancelClick = { WearCommandService.stopNavigation(context) }
    )
}


