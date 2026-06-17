package app.organicmaps.wear.presentation

import android.util.Log
import android.view.KeyEvent
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyListState

import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.Map
import app.organicmaps.sdk.MapView
import app.organicmaps.sdk.PlacePageActivationListener
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.bookmarks.data.Metadata
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.sdk.location.LocationState
import app.organicmaps.sdk.widget.placepage.PlacePageData
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.MapStyle

import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.LocalAmbientMode
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.NavigationIcons
import app.organicmaps.wear.WearMapDownloader
import app.organicmaps.wear.VirtualMwmManager
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.UiEvent
import app.organicmaps.wear.presentation.search.PlacePage

// Granular state models
import app.organicmaps.wear.NavigationInstructions
import app.organicmaps.wear.RoutingStatus
import app.organicmaps.wear.MapStatus
import app.organicmaps.wear.ConnectionStatus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun MapPanel(
    isVisible: Boolean,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isAmbient = LocalAmbientMode.current
    val hApp = context.applicationContext as WearApplication
    
    val focusRequester = remember { FocusRequester() }
    
    // Collecting granular flows
    val routingStatus by NavigationStateHolder.routingStatusFlow.collectAsState(initial = RoutingStatus(false, false, false, false, false, emptyList()))
    val mapStatus by NavigationStateHolder.mapStatusFlow.collectAsState(initial = MapStatus(false, "default", false))
    val connectionStatus by NavigationStateHolder.connectionStatusFlow.collectAsState(initial = ConnectionStatus(false, false, false))
    val location by NavigationStateHolder.locationFlow.collectAsState(initial = Triple(0.0, 0.0, 0f))
    
    LaunchedEffect(Unit) {
        if (isVisible) focusRequester.requestFocus()
    }
    
    var showQuickMenu by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<SearchResultItem?>(null) }

    var isMapDownloaded by remember { mutableStateOf(false) }
    var isWorldMapPresent by remember { mutableStateOf(true) }
    
    LaunchedEffect(location, connectionStatus, hApp.isFullyInitialized) {
        if (hApp.isFullyInitialized) {
            delay(500.milliseconds)
            val (lat, lon, _) = location
            isMapDownloaded = withContext(Dispatchers.Default) {
                if (lat != 0.0) {
                    Framework.nativeIsDownloadedMapAtLocation(lat, lon)
                } else {
                    MapManager.nativeGetDownloadedCount() > 0
                }
            }
            isWorldMapPresent = withContext(Dispatchers.Default) {
                MapManager.nativeGetStatus("World") == CountryItem.STATUS_DONE
            }
        }
    }

    // Feed the device compass to the native engine so FOLLOW_AND_ROTATE actually rotates the map
    // (phone does this via SensorHelper; the watch never wired it). Active only while the map is
    // visible and not ambient, to spare the sensor/battery.
    DisposableEffect(isVisible, isAmbient, hApp.isFullyInitialized) {
        if (!isVisible || isAmbient || !hApp.isFullyInitialized) return@DisposableEffect onDispose {}
        val sensors = hApp.organicMaps.sensorHelper
        val listener = object : app.organicmaps.sdk.location.SensorListener {
            override fun onCompassUpdated(north: Double) {
                Map.onCompassUpdated(north, false)
            }
        }
        sensors.addListener(listener)
        sensors.start()
        onDispose {
            sensors.removeListener(listener)
            sensors.stop()
        }
    }

    // Side Effect: MWM Mounting — instant reaction while panning
    DisposableEffect(isVisible, hApp.isFullyInitialized, connectionStatus) {
        if (!isVisible || !hApp.isFullyInitialized || !connectionStatus.isPhoneConnected || connectionStatus.watchLocalMode) {
            return@DisposableEffect onDispose {}
        }

        val listener = MapManager.CurrentCountryChangedListener { countryId ->
            maybeRequestMount(context, countryId, "Reactive")
        }

        Log.d("MapPanel", "DEBUG_WEAR_PIPELINE: Subscribing to country change events")
        MapManager.nativeSubscribeOnCountryChanged(listener)

        onDispose {
            Log.d("MapPanel", "DEBUG_WEAR_PIPELINE: Unsubscribing from country change events")
            MapManager.nativeUnsubscribeOnCountryChanged()
        }
    }

    // Side Effect: MWM Mounting — periodic safety net. The country-changed listener
    // only fires on viewport changes, so a stationary watch (or a lost mount/metadata
    // message) would otherwise never mount the map under the viewport.
    LaunchedEffect(isVisible, hApp.isFullyInitialized, connectionStatus) {
        if (!isVisible || !hApp.isFullyInitialized || !connectionStatus.isPhoneConnected || connectionStatus.watchLocalMode) {
            return@LaunchedEffect
        }
        while (true) {
            val center = Framework.nativeGetScreenRectCenter()
            if (center != null && center.size == 2) {
                val countryId = withContext(Dispatchers.Default) { MapManager.nativeFindCountry(center[0], center[1]) }
                maybeRequestMount(context, countryId, "Periodic")
            }
            // Re-evaluate map availability: a virtual mount can register a map at any
            // time, and the one-shot check above would leave the overlay stale.
            val (lat, lon, _) = location
            isMapDownloaded = withContext(Dispatchers.Default) {
                if (lat != 0.0) {
                    Framework.nativeIsDownloadedMapAtLocation(lat, lon)
                } else {
                    MapManager.nativeGetDownloadedCount() > 0
                }
            }
            delay(5000)
        }
    }

    // Side Effect: Clear missingMapId on country change or mount
    val state by NavigationStateHolder.state.collectAsState()
    LaunchedEffect(location, state.missingMapId) {
        val (lat, lon, _) = location
        if (lat != 0.0 && state.missingMapId != null) {
            val currentCountry = withContext(Dispatchers.Default) { MapManager.nativeFindCountry(lat, lon) }
            if (currentCountry != null && (currentCountry != state.missingMapId || VirtualMwmManager.isMounted(currentCountry))) {
                NavigationStateHolder.update { it.copy(missingMapId = null) }
            }
        }
    }

    // Side Effect: Route Drawing
    LaunchedEffect(routingStatus, connectionStatus.watchLocalMode) {
        if (!connectionStatus.watchLocalMode && routingStatus.isActive && routingStatus.routePoints.isNotEmpty()) {
            val lats = routingStatus.routePoints.map { it.first }.toDoubleArray()
            val lons = routingStatus.routePoints.map { it.second }.toDoubleArray()
            Framework.nativeDrawRouteLine(lats, lons, 6.0f, 0xBB1E90FF.toInt())
        } else if (!routingStatus.isActive || connectionStatus.watchLocalMode) {
            Framework.nativeRemoveRouteLine()
        }
    }

    // Side Effect: Place Page Activation
    DisposableEffect(hApp.isFullyInitialized) {
        if (!hApp.isFullyInitialized) return@DisposableEffect onDispose {}
        
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

    val (clat, clon, _) = location
    val currentCountry = remember(clat, clon) {
        if (clat != 0.0) MapManager.nativeFindCountry(clat, clon) else null
    }
    val isMapNotOnPhone = currentCountry != null &&
            currentCountry == state.missingMapId &&
            !VirtualMwmManager.isMounted(currentCountry)

    val downloadState by WearMapDownloader.downloadState.collectAsState()
    val isOverlayActive = (!isMapDownloaded || !isWorldMapPresent || isMapNotOnPhone) || showQuickMenu || (tappedDestination != null) || (downloadState != WearMapDownloader.DownloadState.IDLE && downloadState != WearMapDownloader.DownloadState.COMPLETED && downloadState != WearMapDownloader.DownloadState.CANCELLED)

    Box(
        modifier = Modifier.then(modifier).fillMaxSize().clipToBounds()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent {
                if (!hApp.isFullyInitialized || isOverlayActive) return@onKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_STEM_1 -> { showQuickMenu = true; true }
                    KeyEvent.KEYCODE_STEM_2 -> {
                        if (mapStatus.isMapUnlocked) {
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                                LocationState.nativeSwitchToNextMode()
                            }
                        } else {
                            Framework.nativeStopLocationFollow()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (mapStatus.isMapUnlocked) {
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                                LocationState.nativeSwitchToNextMode()
                            }
                            true
                        } else false
                    }
                    else -> false
                }
            }
            .onRotaryScrollEvent {
                if (!hApp.isFullyInitialized || isOverlayActive) return@onRotaryScrollEvent false
                if (it.verticalScrollPixels > 0) Map.zoomOut() else Map.zoomIn()
                true
            },
        contentAlignment = Alignment.Center
    ) {
        if (!hApp.isFullyInitialized) {
            CircularProgressIndicator()
        } else {
            MapViewContainer(
                isVisible = isVisible,
                isAmbient = isAmbient,
                isOverlayActive = isOverlayActive,
                isMapUnlocked = mapStatus.isMapUnlocked,
                mapStyle = mapStatus.mapStyle,
                onLongClick = { showQuickMenu = true }
            )

            if (isOverlayActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                }
                            }
                        }
                        .clickable(enabled = true, onClick = { /* Consume touch */ })
                )
            }
            
            MapLockControl(
                isOverlayActive = isOverlayActive,
                isAmbient = isAmbient
            )
            
            NavigationInstructionsOverlay(
                isMapUnlocked = mapStatus.isMapUnlocked
            )
            
            RecenterControl()
            
            GpsStatusControl(
                isAmbient = isAmbient
            )
            
            RouteStartControl(
                isMapUnlocked = mapStatus.isMapUnlocked
            )
            
            MapMissingControl(
                isMapDownloaded = isMapDownloaded,
                isWorldMapPresent = isWorldMapPresent,
                isMapNotOnPhone = isMapNotOnPhone,
                currentCountry = currentCountry,
                isAmbient = isAmbient
            )
            
            if (showQuickMenu) QuickMenu(onDismiss = { showQuickMenu = false })
            
            PlacePageOverlay(
                tappedDestination = tappedDestination,
                onDismiss = { tappedDestination = null }
            )
        }
    }
}

@Composable
private fun MapViewContainer(
    isVisible: Boolean,
    isAmbient: Boolean,
    isOverlayActive: Boolean,
    isMapUnlocked: Boolean,
    mapStyle: String,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hApp = context.applicationContext as WearApplication
    val isSystemDark = isSystemInDarkTheme()
    val density = LocalDensity.current

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                getMap().setLocationHelper(hApp.organicMaps.locationHelper)
                isLongClickable = true
                setOnLongClickListener {
                    if (hApp.isFullyInitialized && !isOverlayActive) {
                        onLongClick()
                    }
                    true
                }
            }
        },
        update = { mapView ->
            mapView.setMapLocked(isOverlayActive || !isMapUnlocked)
            mapView.isClickable = !isOverlayActive
            mapView.setOnTouchListener { v, _ -> 
                if (isOverlayActive) {
                    v?.performClick()
                    true
                } else false
            }
            
            val map = mapView.getMap()
            val offsetY = with(density) { 60.dp.roundToPx() }
            map.updateCompassOffset(context, -1, offsetY, false)

            if (!isVisible || isAmbient) map.onPause() else map.onResume()

            val targetStyle = when (mapStyle) {
                "night" -> MapStyle.Dark
                "auto" -> if (isSystemDark) MapStyle.Dark else MapStyle.Clear
                else -> MapStyle.Clear
            }
            if (MapStyle.get() != targetStyle) {
                MapStyle.set(targetStyle)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun NavigationInstructionsOverlay(
    isMapUnlocked: Boolean,
    modifier: Modifier = Modifier
) {
    val navInstructions by NavigationStateHolder.navigationInstructionsFlow.collectAsState(initial = null)
    val routingStatus by NavigationStateHolder.routingStatusFlow.collectAsState(initial = null)

    if (routingStatus?.isActive == true && navInstructions?.distToTurn?.isNotEmpty() == true && !isMapUnlocked) {
        val instructions = navInstructions!!
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = 35.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
                        Icon(
                            painter = painterResource(id = NavigationIcons.getTurnIcon(instructions.carDirection, instructions.pedestrianDirection, instructions.exitNum)),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = instructions.distToTurn,
                            style = MaterialTheme.typography.title3.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color.White
                        )
                        if (instructions.nextStreet.isNotEmpty()) {
                            Text(
                                text = instructions.nextStreet,
                                style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLockControl(
    isOverlayActive: Boolean,
    isAmbient: Boolean,
    modifier: Modifier = Modifier
) {
    val mapStatus by NavigationStateHolder.mapStatusFlow.collectAsState(initial = null)
    val context = LocalContext.current
    val hApp = context.applicationContext as WearApplication
    
    if (isAmbient || mapStatus == null) return

    val isMapUnlocked = mapStatus!!.isMapUnlocked

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd
    ) {
        Box(
            modifier = Modifier
                .padding(top = 40.dp, end = 25.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .clickable(enabled = !isOverlayActive) {
                    if (hApp.isFullyInitialized) {
                        if (isMapUnlocked) {
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                                LocationState.nativeSwitchToNextMode()
                            }
                        } else {
                            Framework.nativeStopLocationFollow()
                        }
                    }
                }
                .padding(6.dp)
        ) {
            Icon(
                imageVector = if (isMapUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = if (isMapUnlocked) "Unlock Map" else "Lock Map",
                tint = if (isMapUnlocked) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun RecenterControl(
    modifier: Modifier = Modifier
) {
    val mapStatus by NavigationStateHolder.mapStatusFlow.collectAsState(initial = null)
    val hApp = LocalContext.current.applicationContext as WearApplication

    if (mapStatus?.isMapUnlocked == true) {
        Box(
            modifier = modifier.fillMaxSize().padding(bottom = 10.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            CompactChip(
                onClick = { 
                    if (hApp.isFullyInitialized) {
                        repeat(5) {
                            val mode = LocationState.getMode()
                            if (mode == LocationState.FOLLOW || mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                            LocationState.nativeSwitchToNextMode()
                        }
                    }
                }, 
                label = { Text("Recenter") }, 
                icon = { Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp)) }, 
                colors = ChipDefaults.secondaryChipColors()
            )
        }
    }
}

@Composable
private fun GpsStatusControl(
    isAmbient: Boolean,
    modifier: Modifier = Modifier
) {
    val connectionStatus by NavigationStateHolder.connectionStatusFlow.collectAsState(initial = null)
    val location by NavigationStateHolder.locationFlow.collectAsState(initial = null)

    if (isAmbient || connectionStatus == null || location == null) return

    val isEffectivelyStandalone = connectionStatus!!.standaloneMode || !connectionStatus!!.isPhoneConnected
    if (isEffectivelyStandalone && location!!.first == 0.0) {
        Box(
            modifier = modifier.fillMaxSize().padding(top = 40.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Searching for GPS...", style = MaterialTheme.typography.caption3, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RouteStartControl(
    isMapUnlocked: Boolean,
    modifier: Modifier = Modifier
) {
    val routingStatus by NavigationStateHolder.routingStatusFlow.collectAsState(initial = null)
    val hApp = LocalContext.current.applicationContext as WearApplication

    if (routingStatus?.isRouteBuilt == true && !routingStatus!!.isNavigating) {
        Box(
            modifier = modifier.fillMaxSize().padding(bottom = if (isMapUnlocked) 95.dp else 12.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Button(
                onClick = { if (hApp.isFullyInitialized) RoutingController.get().start() },
                modifier = Modifier.height(40.dp).fillMaxWidth(0.5f),
                colors = ButtonDefaults.primaryButtonColors()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start")
                }
            }
        }
    }
}

@Composable
private fun MapMissingControl(
    isMapDownloaded: Boolean,
    isWorldMapPresent: Boolean,
    isMapNotOnPhone: Boolean,
    currentCountry: String?,
    isAmbient: Boolean,
    modifier: Modifier = Modifier
) {
    if (isAmbient) return

    val context = LocalContext.current
    val hApp = context.applicationContext as WearApplication
    if (!hApp.isFullyInitialized) return

    val location by NavigationStateHolder.locationFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    if (!isMapDownloaded || !isWorldMapPresent || isMapNotOnPhone) {
        Box(
            modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .clickable(enabled = true, onClick = {}) // Consume clicks
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val icon = when {
                        !isWorldMapPresent -> Icons.Default.Warning
                        isMapNotOnPhone -> Icons.Default.PhoneAndroid
                        else -> Icons.Default.Map
                    }
                    val iconTint = if (!isWorldMapPresent) Color.Yellow else Color.White
                    
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val title = when {
                        !isWorldMapPresent -> "Missing World Map"
                        isMapNotOnPhone -> "Map not on phone"
                        else -> "No Local Map Data"
                    }
                    Text(title, style = MaterialTheme.typography.caption2, color = Color.White, textAlign = TextAlign.Center)
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val subtitle = when {
                        !isWorldMapPresent -> "(Required for rendering)"
                        isMapNotOnPhone -> currentCountry!!.replace("_", " ")
                        else -> "(Pan to center or sync)"
                    }
                    Text(subtitle, style = MaterialTheme.typography.caption3, color = Color.LightGray, textAlign = TextAlign.Center)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        CompactChip(
                            onClick = { NavigationStateHolder.emitEvent(UiEvent.OpenMapManager) },
                            label = { Text("Manage", style = MaterialTheme.typography.caption3) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.height(28.dp).weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        CompactChip(
                            onClick = {
                                scope.launch {
                                    val (clat, clon, _) = location ?: Triple(0.0, 0.0, 0f)
                                    if (!isWorldMapPresent) {
                                        Log.d("MapPanel", "DEBUG_WEAR: Sync Local - World map missing, downloading World")
                                        WearMapDownloader.downloadOrStreamMap(context, "World", "")
                                    } else {
                                        Log.d("MapPanel", "DEBUG_WEAR: Sync Local - Finding country for $clat, $clon")
                                        val countryId = withContext(Dispatchers.Default) { MapManager.nativeFindCountry(clat, clon) }
                                        if (!countryId.isNullOrEmpty()) {
                                            Log.d("MapPanel", "DEBUG_WEAR: Sync Local - Country found: $countryId, requesting download")
                                            // Storage guard: only copy the whole region if it fits comfortably;
                                            // otherwise leave it to bounded on-demand streaming.
                                            val regionBytes = withContext(Dispatchers.Default) {
                                                runCatching { app.organicmaps.sdk.downloader.CountryItem.fill(countryId).totalSize }.getOrDefault(0L)
                                            }
                                            if (regionBytes > 0 && !app.organicmaps.wear.WatchStorage.fitsComfortably(context, regionBytes)) {
                                                val free = app.organicmaps.wear.WatchStorage.formatBytes(app.organicmaps.wear.WatchStorage.freeBytes(context))
                                                val size = app.organicmaps.wear.WatchStorage.formatBytes(regionBytes)
                                                NavigationStateHolder.emitEvent(UiEvent.ShowToast(
                                                    "$size won't fit ($free free). Streaming on demand instead.",
                                                    android.widget.Toast.LENGTH_LONG))
                                            } else {
                                                WearMapDownloader.downloadOrStreamMap(context, countryId!!, "")
                                            }
                                        } else {
                                            Log.w("MapPanel", "DEBUG_WEAR: Sync Local - Could not find country for $clat, $clon. Opening Map Manager.")
                                            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Cannot find country for current location"))
                                            NavigationStateHolder.emitEvent(UiEvent.OpenMapManager)
                                        }
                                    }
                                }
                            },
                            label = { Text(if (!isWorldMapPresent) "Get World" else "Copy to Watch", style = MaterialTheme.typography.caption3) },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.height(28.dp).weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacePageOverlay(
    tappedDestination: SearchResultItem?,
    onDismiss: () -> Unit
) {
    if (tappedDestination == null) return
    
    val context = LocalContext.current
    val hApp = context.applicationContext as WearApplication
    val scope = rememberCoroutineScope()

    androidx.wear.compose.material.dialog.Dialog(showDialog = true, onDismissRequest = onDismiss) {
        PlacePage(
            result = tappedDestination,
            onNavigate = { routerType, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved ->
                scope.launch {
                    val state = NavigationStateHolder.state.value
                    if (state.standaloneMode || (!state.isPhoneConnected && state.watchLocalMode)) {
                        if (hApp.isFullyInitialized && !Framework.nativeIsDownloadedMapAtLocation(tappedDestination.lat, tappedDestination.lon)) {
                            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Map not downloaded for destination", android.widget.Toast.LENGTH_LONG))
                            return@launch
                        }
                        try {
                            hApp.waitForInitializationSuspend()
                            NavigationStateHolder.update { it.copy(
                                isActive = true, 
                                isNavigating = false, 
                                routeBuildProgress = 0, 
                                isRouteBuilding = true, 
                                isRouteReady = false, 
                                isRouteBuilt = false,
                                routePoints = emptyList(), 
                                lastRouteError = 0
                            ) }
                            val locationHelper = hApp.organicMaps.locationHelper
                            val myPos = locationHelper.myPosition
                            val savedPos = locationHelper.savedLocation
                            
                            val startPoint: MapObject? = if (myPos != null) {
                                myPos
                            } else if (savedPos != null) {
                                MapObject.createMapObject(MapObject.MY_POSITION, "My Location", "", savedPos.getLatitude(), savedPos.getLongitude())
                            } else {
                                val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
                                val lastLat = prefs.getFloat("last_known_lat", 0f).toDouble()
                                val lastLon = prefs.getFloat("last_known_lon", 0f).toDouble()
                                if (lastLat != 0.0) {
                                    MapObject.createMapObject(MapObject.MY_POSITION, "Previous Fix", "", lastLat, lastLon)
                                } else {
                                    null
                                }
                            }

                            if (startPoint == null) { 
                                Log.e("MapPanel", "No GPS position for routing")
                                NavigationStateHolder.emitEvent(UiEvent.ShowToast("No GPS position for routing", android.widget.Toast.LENGTH_LONG))
                                NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                                return@launch 
                            }
                            val destination = MapObject.createMapObject(MapObject.POI, tappedDestination.name, tappedDestination.description, tappedDestination.lat, tappedDestination.lon)
                            val router = when (routerType) { 0 -> Router.Vehicle; 1 -> Router.Pedestrian; 2 -> Router.Bicycle; else -> Router.Transit }
                            val controller = RoutingController.get()
                            controller.prepare(startPoint!!, destination, router)
                            controller.checkAndBuildRoute()
                            NavigationStateHolder.update { it.copy(distToTurn = "", nextStreet = "", distToTarget = "", eta = 0, completionPercent = 0.0, turnLat = 0.0, turnLon = 0.0, avoidTolls = avoidTolls, avoidMotorways = avoidMotorways, avoidFerries = avoidFerries, avoidUnpaved = avoidUnpaved) }
                        } catch (e: Exception) {
                            Log.e("MapPanel", "Route planning failed: ${e.message}")
                            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Routing failed: ${e.message}", android.widget.Toast.LENGTH_LONG))
                            NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                        }
                    } else {
                        WearCommandService.selectSearchResult(context, tappedDestination, routerType)
                        NavigationStateHolder.update { it.copy(isActive = true, isNavigating = false, destinationName = tappedDestination.name, isRouteBuilding = true) }
                    }
                    onDismiss()
                }
            },
            onDismiss = { 
                val state = NavigationStateHolder.state.value
                if (state.isRouteBuilding || (state.isActive && !state.isNavigating)) {
                    RoutingController.get().cancel()
                    NavigationStateHolder.update(state.copy(
                        isRouteBuilding = false, 
                        isRouteBuilt = false,
                        isRouteReady = false,
                        isActive = false, 
                        isMapUnlocked = false
                    ), force = true)
                } else {
                    NavigationStateHolder.update(state.copy(isRouteBuilding = false), force = true)
                }
                Framework.nativeDeactivatePopup()
                onDismiss()
            }
        )
    }
}

@Composable
fun QuickMenu(onDismiss: () -> Unit) {
    androidx.wear.compose.material.dialog.Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val routingStatus by NavigationStateHolder.routingStatusFlow.collectAsState(initial = null)
        val connectionStatus by NavigationStateHolder.connectionStatusFlow.collectAsState(initial = null)
        val trackRecording by NavigationStateHolder.trackRecordingFlow.collectAsState(initial = null)
        val context = LocalContext.current
        
        var elapsedTime by remember { mutableStateOf("") }
        LaunchedEffect(trackRecording?.isRecording, trackRecording?.startTime) {
            val recording = trackRecording
            if (recording != null && recording.isRecording && recording.startTime > 0) {
                while (true) {
                    val ms = System.currentTimeMillis() - recording.startTime
                    val sec = (ms / 1000) % 60
                    val min = (ms / 60000) % 60
                    val hr = ms / 3600000
                    elapsedTime = if (hr > 0) String.format(Locale.US, "%d:%02d:%02d", hr, min, sec) else String.format(Locale.US, "%02d:%02d", min, sec)
                    delay(1.seconds)
                }
            } else {
                elapsedTime = ""
            }
        }

        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(), 
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally, 
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)
        ) {
            item { Text("Quick Menu", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF)) }
            
            if (routingStatus?.isActive == true) {
                item {
                    Chip(
                        onClick = {
                            if (connectionStatus?.standaloneMode == true || connectionStatus?.watchLocalMode == true) {
                                RoutingController.get().cancel()
                            } else {
                                WearCommandService.stopNavigation(context)
                            }
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                                LocationState.nativeSwitchToNextMode()
                            }
                            NavigationStateHolder.update { it.copy(isActive = false, isNavigating = false, isRouteBuilt = false, isRouteBuilding = false, isMapUnlocked = false) }
                            onDismiss()
                        },
                        label = { Text("Stop Navigation") },
                        icon = { Icon(Icons.Default.Close, contentDescription = null) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
            }

            item { Chip(onClick = { if (Map.isEngineCreated()) Map.zoomIn(); onDismiss() }, label = { Text("Zoom In") }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { Chip(onClick = { if (Map.isEngineCreated()) Map.zoomOut(); onDismiss() }, label = { Text("Zoom Out") }, icon = { Icon(Icons.Default.Remove, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }

            item {
                Chip(
                    onClick = {
                        // Create a bookmark at the current map centre and push it to the phone via
                        // the normal metadata→file sync (de-dup + tombstone aware).
                        val center = Framework.nativeGetScreenRectCenter()
                        if (center != null && center.size == 2) {
                            try {
                                app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addNewBookmark(center[0], center[1])
                                app.organicmaps.wear.WatchBookmarkSyncManager.onLocalBookmarksChanged(context, isUserAction = true)
                                app.organicmaps.wear.WatchBookmarkSyncManager.requestSync(context)
                                NavigationStateHolder.emitEvent(UiEvent.ShowToast("Bookmark added"))
                            } catch (e: Exception) {
                                NavigationStateHolder.emitEvent(UiEvent.ShowToast("Couldn't add bookmark"))
                            }
                        } else {
                            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Map not ready"))
                        }
                        onDismiss()
                    },
                    label = { Text("Add Bookmark") },
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }

            item {
                val isRecording = trackRecording?.isRecording == true
                Chip(
                    onClick = { 
                        WearCommandService.toggleTrackRecording(context)
                        onDismiss()
                    },
                    label = { Text(if (isRecording) "Stop Recording" else "Start Recording") },
                    secondaryLabel = { if (elapsedTime.isNotEmpty()) Text(elapsedTime) },
                    icon = { 
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = if (isRecording) Color.Red else Color.White
                        ) 
                    },
                    colors = if (isRecording) {
                        ChipDefaults.chipColors(
                            backgroundColor = Color(0xFFD32F2F).copy(alpha = 0.5f),
                            contentColor = Color.White,
                            secondaryContentColor = Color.White.copy(alpha = 0.7f),
                            iconColor = Color.Red
                        )
                    } else {
                        ChipDefaults.primaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }

            item { Chip(onClick = onDismiss, label = { Text("Close") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        }
    }
}

private fun maybeRequestMount(context: android.content.Context, countryId: String?, trigger: String) {
    if (!countryId.isNullOrEmpty() &&
        MapManager.nativeGetStatus(countryId) != CountryItem.STATUS_DONE &&
        !VirtualMwmManager.isMounted(countryId) &&
        VirtualMwmManager.shouldRequestMetadata(countryId)
    ) {
        Log.d("MapPanel", "DEBUG_WEAR_PIPELINE: $trigger MWM mount request: $countryId")
        WearCommandService.requestMwmMetadata(context, countryId)
    }
}
