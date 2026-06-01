package app.organicmaps.wear.presentation

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
import androidx.wear.compose.material.*
import androidx.lifecycle.viewmodel.compose.viewModel

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
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.presentation.search.PlacePage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs



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
    val navState by NavigationStateHolder.state.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        if (isVisible) focusRequester.requestFocus()
    }
    
    var showQuickMenu by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<SearchResultItem?>(null) }

    val currentLat = navState.lat
    val currentLon = navState.lon

    var isMapDownloaded by remember { mutableStateOf(false) }
    var isWorldMapPresent by remember { mutableStateOf(true) }
    LaunchedEffect(currentLat, currentLon, navState.watchLocalMode, navState.standaloneMode, navState.isPhoneConnected, hApp.isFullyInitialized) {
        if (hApp.isFullyInitialized) {
            delay(500) // Reduced delay
            isMapDownloaded = withContext(Dispatchers.Default) {
                if (currentLat != 0.0) {
                    Framework.nativeIsDownloadedMapAtLocation(currentLat, currentLon)
                } else {
                    // If no GPS fix, check if any maps are downloaded at all
                    app.organicmaps.sdk.downloader.MapManager.nativeGetDownloadedCount() > 0
                }
            }
            isWorldMapPresent = withContext(Dispatchers.Default) {
                app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World") == CountryItem.STATUS_DONE
            }
        }
    }


    val scope = rememberCoroutineScope()

    LaunchedEffect(navState.routePoints, navState.isActive, navState.watchLocalMode) {
        if (!navState.watchLocalMode && navState.isActive && navState.routePoints.isNotEmpty()) {
            val lats = navState.routePoints.map { it.first }.toDoubleArray()
            val lons = navState.routePoints.map { it.second }.toDoubleArray()
            Framework.nativeDrawRouteLine(lats, lons, 6.0f, 0xBB1E90FF.toInt())
        } else if (!navState.isActive || navState.watchLocalMode) {
            Framework.nativeRemoveRouteLine()
        }
    }

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

    val downloadState by WearMapDownloader.downloadState.collectAsState()
    val isOverlayActive = (!isMapDownloaded || !isWorldMapPresent) || showQuickMenu || (tappedDestination != null) || (downloadState != WearMapDownloader.DownloadState.IDLE && downloadState != WearMapDownloader.DownloadState.COMPLETED && downloadState != WearMapDownloader.DownloadState.CANCELLED)

    Box(
        modifier = Modifier.then(modifier).fillMaxSize().clipToBounds()
            .background(Color.Black) // Wear OS: default to Black to avoid grey flashes
            .focusRequester(focusRequester)
            .onKeyEvent {
                if (!hApp.isFullyInitialized || isOverlayActive) return@onKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_STEM_1 -> { showQuickMenu = true; true }
                    KeyEvent.KEYCODE_STEM_2 -> {
                        if (navState.isMapUnlocked) {
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW || mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                                LocationState.nativeSwitchToNextMode()
                            }
                        } else {
                            Framework.nativeStopLocationFollow()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (navState.isMapUnlocked) {
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW || mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
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
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        getMap().setLocationHelper(hApp.organicMaps.locationHelper)
                        isLongClickable = true
                        setOnLongClickListener {
                            if (hApp.isFullyInitialized && !isOverlayActive) {
                                if (!navState.isMapUnlocked) {
                                    Framework.nativeStopLocationFollow()
                                }
                                showQuickMenu = true
                            }
                            true
                        }
                    }
                },
                update = { mapView ->
                    mapView.setMapLocked(isOverlayActive || !navState.isMapUnlocked)
                    mapView.isClickable = !isOverlayActive
                    mapView.setOnTouchListener { _, _ -> isOverlayActive }
                    
                    val map = mapView.getMap()
                    
                    // Position Compass below the lock icon (which is at top=40dp)
                    val density = context.resources.displayMetrics.density
                    val offsetY = (75 * density).toInt()
                    map.updateCompassOffset(context, -1, offsetY, false)

                    if (!isVisible || isAmbient) map.onPause() else map.onResume()

                    val targetStyle = when (navState.mapStyle) {
                        "night" -> MapStyle.Dark
                        "auto" -> if (isSystemDark) MapStyle.Dark else MapStyle.Clear
                        else -> MapStyle.Clear
                    }
                    if (MapStyle.get() != targetStyle) {
                        MapStyle.set(targetStyle)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )


            if (isOverlayActive) {
                // Full-screen blocker to prevent map interaction when any overlay/dialog is active
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
        }
        
        // Point 4: LOCK/UNLOCK VISUAL FEEDBACK
        if (!isAmbient) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 25.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !isOverlayActive) {
                        if (hApp.isFullyInitialized) {
                            if (navState.isMapUnlocked) {
                                repeat(5) {
                                    val mode = LocationState.getMode()
                                    if (mode == LocationState.FOLLOW || mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
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
                    imageVector = if (navState.isMapUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = if (navState.isMapUnlocked) "Unlock Map" else "Lock Map",
                    tint = if (navState.isMapUnlocked) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        if (navState.isActive && navState.distToTurn.isNotEmpty() && !navState.isMapUnlocked) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 35.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) { Icon(painter = painterResource(id = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection, navState.exitNum)), contentDescription = null, modifier = Modifier.fillMaxSize(), tint = Color.White) }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = navState.distToTurn, style = MaterialTheme.typography.title3.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                        if (navState.nextStreet.isNotEmpty()) Text(text = navState.nextStreet, style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                    }
                }
            }
        }

        if (navState.isMapUnlocked) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (navState.isRouteBuilt && !navState.isNavigating) 60.dp else 24.dp)) {
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

        if (navState.isEffectivelyStandalone && navState.lat == 0.0 && !isAmbient) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(6.dp)); Text("Searching for GPS...", style = MaterialTheme.typography.caption3, color = Color.White) }
            }
        }
        
        if (navState.isRouteBuilt && !navState.isNavigating) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (navState.isMapUnlocked) 60.dp else 24.dp)) {
                Button(onClick = { if (hApp.isFullyInitialized) RoutingController.get().start() }, modifier = Modifier.height(40.dp).fillMaxWidth(0.5f), colors = ButtonDefaults.primaryButtonColors()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Start") }
                }
            }
        }

        // MAP MISSING NOTIFICATION
        if ((!isMapDownloaded || !isWorldMapPresent) && hApp.isFullyInitialized && !isAmbient) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .clickable(enabled = true, onClick = {}) // Consume clicks
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = if (!isWorldMapPresent) Icons.Default.Warning else Icons.Default.Map, contentDescription = null, tint = if (!isWorldMapPresent) Color.Yellow else Color.White, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(if (!isWorldMapPresent) "Missing World Map" else "No Local Map Data", style = MaterialTheme.typography.caption2, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(if (!isWorldMapPresent) "(Required for rendering)" else "(Pan to center or sync)", style = MaterialTheme.typography.caption3, color = Color.LightGray, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        CompactChip(onClick = { NavigationStateHolder.update { it.copy(openMapManager = true) } }, label = { Text("Manage", style = MaterialTheme.typography.caption3) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.height(28.dp).weight(1f))
                        Spacer(modifier = Modifier.width(4.dp))
                        CompactChip(
                            onClick = {
                                scope.launch {
                                    if (!isWorldMapPresent) {
                                        WearMapDownloader.downloadOrStreamMap(context, "World", "")
                                    } else {
                                        val countryId = withContext(Dispatchers.Default) { MapManager.nativeFindCountry(currentLat, currentLon) }
                                        if (!countryId.isNullOrEmpty()) {
                                            WearMapDownloader.downloadOrStreamMap(context, countryId!!, "")
                                        } else {
                                            NavigationStateHolder.update { it.copy(openMapManager = true) }
                                        }
                                    }
                                }
                            },
                            label = { Text(if (!isWorldMapPresent) "Get World" else "Sync Local", style = MaterialTheme.typography.caption3) },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.height(28.dp).weight(1f)
                        )
                    }
                }
            }
        }
        
        if (showQuickMenu) QuickMenu(onDismiss = { showQuickMenu = false })
        if (tappedDestination != null) {
            androidx.wear.compose.material.dialog.Dialog(showDialog = true, onDismissRequest = { tappedDestination = null }) {
                PlacePage(result = tappedDestination!!, onNavigate = { routerType, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved ->
                    scope.launch {
                        val state = NavigationStateHolder.state.value
                        if (state.standaloneMode || (!state.isPhoneConnected && state.watchLocalMode)) {
                            if (hApp.isFullyInitialized && !Framework.nativeIsDownloadedMapAtLocation(tappedDestination!!.lat, tappedDestination!!.lon)) { android.widget.Toast.makeText(context, "Map not downloaded for destination", android.widget.Toast.LENGTH_LONG).show(); return@launch }
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
                                    android.util.Log.e("MapPanel", "No GPS position for routing")
                                    android.widget.Toast.makeText(context, "No GPS position for routing", android.widget.Toast.LENGTH_LONG).show()
                                    NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                                    return@launch 
                                }
                                val destination = MapObject.createMapObject(MapObject.POI, tappedDestination!!.name, tappedDestination!!.description, tappedDestination!!.lat, tappedDestination!!.lon)
                                val router = when (routerType) { 0 -> Router.Vehicle; 1 -> Router.Pedestrian; 2 -> Router.Bicycle; else -> Router.Transit }
                                val controller = RoutingController.get()
                                controller.prepare(startPoint!!, destination, router)
                                controller.checkAndBuildRoute()
                                NavigationStateHolder.update { it.copy(distToTurn = "", nextStreet = "", distToTarget = "", eta = 0, completionPercent = 0.0, turnLat = 0.0, turnLon = 0.0, avoidTolls = avoidTolls, avoidMotorways = avoidMotorways, avoidFerries = avoidFerries, avoidUnpaved = avoidUnpaved) }
                            } catch (e: Exception) { android.util.Log.e("MapPanel", "Route planning failed: ${e.message}"); android.widget.Toast.makeText(context, "Routing failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show(); NavigationStateHolder.update { it.copy(isRouteBuilding = false) } }
                        } else { WearCommandService.selectSearchResult(context, tappedDestination!!, routerType); NavigationStateHolder.update { it.copy(isActive = true, isNavigating = false, destinationName = tappedDestination!!.name, isRouteBuilding = true) } }
                        tappedDestination = null
                    }
                }, onDismiss = { 
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
                    tappedDestination = null 
                })
            }
        }
    }
}

@Composable
fun QuickMenu(onDismiss: () -> Unit) {
    androidx.wear.compose.material.dialog.Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val navState by NavigationStateHolder.state.collectAsState()
        val context = LocalContext.current
        
        var elapsedTime by remember { mutableStateOf("") }
        LaunchedEffect(navState.isTrackRecording, navState.trackRecordingStartTime) {
            if (navState.isTrackRecording && navState.trackRecordingStartTime > 0) {
                while (true) {
                    val ms = System.currentTimeMillis() - navState.trackRecordingStartTime
                    val sec = (ms / 1000) % 60
                    val min = (ms / 60000) % 60
                    val hr = ms / 3600000
                    elapsedTime = if (hr > 0) java.lang.String.format(java.util.Locale.US, "%d:%02d:%02d", hr, min, sec) else java.lang.String.format(java.util.Locale.US, "%02d:%02d", min, sec)
                    delay(1000)
                }
            } else {
                elapsedTime = ""
            }
        }

        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)) {
            item { Text("Quick Menu", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF)) }
            
            if (navState.isActive) {
                item {
                    Chip(
                        onClick = {
                            if (navState.standaloneMode || navState.watchLocalMode) {
                                RoutingController.get().cancel()
                            } else {
                                WearCommandService.stopNavigation(context)
                            }
                            repeat(5) {
                                val mode = LocationState.getMode()
                                if (mode == LocationState.FOLLOW || mode == LocationState.FOLLOW_AND_ROTATE) return@repeat
                                LocationState.nativeSwitchToNextMode()
                            }
                            NavigationStateHolder.update(navState.copy(isActive = false, isNavigating = false, isRouteBuilt = false, isRouteBuilding = false, isMapUnlocked = false), force = true)
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
                val isRecording = navState.isTrackRecording
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
