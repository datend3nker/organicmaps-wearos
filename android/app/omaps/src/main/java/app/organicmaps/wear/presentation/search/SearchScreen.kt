package app.organicmaps.wear.presentation.search

import android.content.Intent
import android.content.Context
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.WearApplication
import android.util.Log
import kotlinx.coroutines.*
import app.organicmaps.sdk.search.SearchEngine
import app.organicmaps.sdk.search.SearchListener
import app.organicmaps.sdk.search.SearchResult
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.sdk.Framework
import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.MainViewModel
import app.organicmaps.wear.presentation.navigation.RoutingOptionsRow
import app.organicmaps.sdk.settings.RoadType
import app.organicmaps.sdk.routing.RoutingOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material.dialog.Dialog
import app.organicmaps.sdk.bookmarks.data.Metadata

@Composable
fun SearchScreen(modifier: Modifier = Modifier, isVisible: Boolean = true, mainViewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    
    // Auto-hide keyboard if pane becomes invisible
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            keyboardController?.hide()
        }
    }
    
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var selectedResult by remember { mutableStateOf<SearchResultItem?>(null) }
    
    val navState by NavigationStateHolder.state.collectAsState()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(navState.watchLocalMode) {
        val listener = object : SearchListener {
            override fun onResultsUpdate(results: Array<out SearchResult>, timestamp: Long) {
                Log.d("SearchScreen", "Received ${results.size} standalone results")
                NavigationStateHolder.update { current ->
                    val converted = results.map {
                        SearchResultItem(
                            name = it.getTitle(context) ?: "",
                            description = it.description?.localizedFeatureType ?: "",
                            lat = it.lat,
                            lon = it.lon,
                            type = it.type,
                            openingHours = "", // Detailed search results don't have metadata yet
                            website = "",
                            phone = "",
                            address = "",
                            distance = it.description?.distance?.toString(context) ?: "",
                            featureType = it.description?.localizedFeatureType ?: ""
                        )
                    }
                    current.copy(
                        searchResults = converted,
                        isSearching = false
                    )
                }
            }
            override fun onResultsEnd(timestamp: Long) {}
        }
        
        if (navState.watchLocalMode) {
            SearchEngine.INSTANCE.addListener(listener)
        }
        
        onDispose {
            if (navState.watchLocalMode) {
                SearchEngine.INSTANCE.removeListener(listener)
            }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            WearCommandService.requestSearchHistory(context)
        }
    }

    fun performSearch(query: String) {
        coroutineScope.launch {
            NavigationStateHolder.update { it.copy(isSearching = true) }
            if (navState.watchLocalMode) {
                try {
                    val wearApp = context.applicationContext as WearApplication
                    wearApp.waitForInitializationSuspend()
                    val state = NavigationStateHolder.state.value
                    SearchEngine.INSTANCE.search(context, query, false, 0L, state.lat != 0.0, state.lat, state.lon)
                } catch (e: Exception) {
                    Log.e("SearchScreen", "Standalone search failed: ${e.message}")
                    NavigationStateHolder.update { it.copy(isSearching = false) }
                }
            } else {
                WearCommandService.search(context, query)
            }
        }
    }

    val onQueryChanged: (TextFieldValue) -> Unit = {
        searchQuery = it
        if (it.text.isEmpty()) {
            NavigationStateHolder.update { current ->
                current.copy(searchResults = emptyList(), isSearching = false)
            }
        } else if (it.text.length > 2) {
            performSearch(it.text)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull() ?: ""
            if (spokenText.isNotEmpty()) {
                searchQuery = TextFieldValue(spokenText, selection = TextRange(spokenText.length))
                performSearch(spokenText)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 10.dp, end = 10.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .focusRequester(focusRequester),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onQueryChanged,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(color = MaterialTheme.colors.onSurface, fontSize = 16.sp),
                                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Search,
                                    autoCorrectEnabled = false
                                ),
                                keyboardActions = KeyboardActions(onSearch = {
                                    val finalQuery = searchQuery.text
                                    if (finalQuery.isNotEmpty()) {
                                        performSearch(finalQuery)
                                        keyboardController?.hide()
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(1)
                                        }
                                    }
                                }),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.text.isEmpty()) {
                                        Text("Search...", style = TextStyle(color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)))
                                    }
                                    innerTextField()
                                }
                            )
                        }
                        
                        if (searchQuery.text.isNotEmpty()) {
                            Button(
                                onClick = { searchQuery = TextFieldValue("") },
                                modifier = Modifier.size(ButtonDefaults.SmallButtonSize).padding(end = 4.dp),
                                colors = ButtonDefaults.secondaryButtonColors()
                            ) {
                                Icon(Icons.Default.Clear, modifier = Modifier.size(18.dp), contentDescription = "Clear")
                            }
                        }

                        Button(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                }
                                voiceLauncher.launch(intent)
                            },
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                            colors = ButtonDefaults.secondaryButtonColors()
                        ) {
                            Icon(Icons.Default.Mic, modifier = Modifier.size(18.dp), contentDescription = "Voice")
                        }
                    }
                }

                if (searchQuery.text.isEmpty()) {
                    if (navState.searchHistory.isNotEmpty()) {
                        item {
                            Text(
                                "Recent Searches",
                                style = MaterialTheme.typography.caption2,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(navState.searchHistory) { query ->
                            Chip(
                                onClick = { 
                                    searchQuery = TextFieldValue(text = query, selection = TextRange(query.length))
                                    performSearch(query)
                                    keyboardController?.hide()
                                },
                                label = { Text(query, maxLines = 1) },
                                icon = { Icon(Icons.Default.History, modifier = Modifier.size(16.dp), contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ChipDefaults.secondaryChipColors()
                            )
                        }
                    } else {
                        item {
                            Text(
                                "Type or use voice to search",
                                modifier = Modifier.padding(top = 20.dp),
                                style = MaterialTheme.typography.caption2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    if (navState.searchResults.isEmpty() && navState.isSearching) {
                        item { CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp)) }
                    } else if (navState.searchResults.isEmpty()) {
                        item {
                            Text(
                                "No results found",
                                modifier = Modifier.padding(top = 20.dp),
                                style = MaterialTheme.typography.caption2
                            )
                        }
                    } else {
                        items(navState.searchResults) { result ->
                            SearchResultChip(result) {
                                selectedResult = result
                                keyboardController?.hide()
                            }
                        }
                    }
                }
            }
            
            if (selectedResult != null) {
                Dialog(
                    showDialog = true,
                    onDismissRequest = { selectedResult = null }
                ) {
                    PlacePage(
                        result = selectedResult!!,
                        onNavigate = { routerType, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved ->
                            coroutineScope.launch {
                                val state = NavigationStateHolder.state.value
                                if (state.standaloneMode || (!state.isPhoneConnected && state.watchLocalMode)) {
                                    try {
                                        val wearApp = context.applicationContext as WearApplication
                                        wearApp.waitForInitializationSuspend()

                                        // APPLY ROUTING OPTIONS
                                        val roadTypes = app.organicmaps.sdk.settings.RoadType.values()
                                        roadTypes.forEach { app.organicmaps.sdk.routing.RoutingOptions.removeOption(it) }
                                        if (avoidTolls) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Toll)
                                        if (avoidMotorways) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Motorway)
                                        if (avoidFerries) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Ferry)
                                        if (avoidUnpaved) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Dirty)

                                        // FALLBACK FOR STANDALONE ROUTING START POINT
                                        val startPoint = wearApp.organicMaps.locationHelper.myPosition 
                                            ?: wearApp.organicMaps.locationHelper.savedLocation?.let { 
                                                MapObject.createMapObject(MapObject.MY_POSITION, "Previous Fix", "", it.latitude, it.longitude)
                                            }
                                            ?: let {
                                                val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
                                                val lastLat = prefs.getFloat("last_known_lat", 0f).toDouble()
                                                val lastLon = prefs.getFloat("last_known_lon", 0f).toDouble()
                                                if (lastLat != 0.0) {
                                                    MapObject.createMapObject(MapObject.MY_POSITION, "Previous Fix", "Last seen here", lastLat, lastLon)
                                                } else null
                                            }

                                        if (startPoint == null) {
                                            Log.e("SearchScreen", "No GPS position for routing")
                                            NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                                            return@launch
                                        }
                                        val destination = MapObject.createMapObject(MapObject.POI, selectedResult!!.name, selectedResult!!.description, selectedResult!!.lat, selectedResult!!.lon)
                                        val router = when (routerType) {
                                            0 -> app.organicmaps.sdk.Router.Vehicle
                                            1 -> app.organicmaps.sdk.Router.Pedestrian
                                            2 -> app.organicmaps.sdk.Router.Bicycle
                                            else -> app.organicmaps.sdk.Router.Transit
                                        }
                                        val controller = RoutingController.get()
                                        controller.prepare(startPoint, destination, router)
                                        controller.checkAndBuildRoute()
                                        
                                        NavigationStateHolder.update { it.copy(
                                            isActive = true,
                                            isNavigating = false,
                                            routeBuildProgress = 0,
                                            isRouteBuilding = true,
                                            isRouteReady = false,
                                            isRouteBuilt = false,
                                            routePoints = emptyList(),
                                            distToTurn = "",
                                            nextStreet = "",
                                            distToTarget = "",
                                            eta = 0,
                                            completionPercent = 0.0,
                                            turnLat = 0.0,
                                            turnLon = 0.0,
                                            isMapUnlocked = false,
                                            avoidTolls = avoidTolls,
                                            avoidMotorways = avoidMotorways,
                                            avoidFerries = avoidFerries,
                                            avoidUnpaved = avoidUnpaved
                                        ) }
                                    } catch (e: Exception) {
                                        Log.e("SearchScreen", "Route planning failed: ${e.message}")
                                        NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                                    }
                                } else {
                                    WearCommandService.selectSearchResult(context, selectedResult!!, routerType)
                                    NavigationStateHolder.update { it.copy(isActive = true, isMapUnlocked = false, isRouteBuilding = true) }
                                }
                                selectedResult = null
                                searchQuery = TextFieldValue("")
                            }
                        },
                        onDismiss = { 
                            selectedResult = null 
                            val state = NavigationStateHolder.state.value
                            if (state.isRouteBuilding || (state.isActive && !state.isNavigating)) {
                                RoutingController.get().cancel()
                                NavigationStateHolder.update(state.copy(
                                    isRouteBuilding = false, 
                                    isRouteBuilt = false,
                                    isRouteReady = false,
                                    isActive = false,
                                    isMapUnlocked = state.isMapUnlockedBeforeNav
                                ), force = true)
                            } else {
                                NavigationStateHolder.update(state.copy(
                                    isRouteBuilding = false
                                ), force = true)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PlacePage(
    result: SearchResultItem,
    onNavigate: (Int, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var showModeSelection by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    
    var isMapDownloaded by remember { mutableStateOf(true) }
    var isWorldMapDownloaded by remember { mutableStateOf(true) }

    LaunchedEffect(result.lat, result.lon) {
        isMapDownloaded = Framework.nativeIsDownloadedMapAtLocation(result.lat, result.lon)
        isWorldMapDownloaded = Framework.nativeIsWorldMapDownloaded()
    }

    if (showModeSelection) {
        ModeSelectionScreen(
            result = result,
            onModeSelected = onNavigate,
            onCancel = { showModeSelection = false }
        )
    } else {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 10.dp, end = 10.dp)
        ) {
            item {
                Text(result.name.ifEmpty { "Dropped Pin" }, style = MaterialTheme.typography.title3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (result.description.isNotEmpty() && result.description != "POI" && result.description != "Dropped Pin" && result.description != "Previous Fix") {
                item {
                    Text(result.description, style = MaterialTheme.typography.caption2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color(0xFF00E5FF))
                }
            }

            if (!isMapDownloaded && (navState.standaloneMode || navState.watchLocalMode)) {
                item {
                    Card(
                        onClick = { /* Could open map manager */ },
                        modifier = Modifier.padding(vertical = 4.dp),
                        backgroundPainter = CardDefaults.cardBackgroundPainter(Color(0xFFC15746).copy(alpha = 0.3f))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC15746), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Region map missing", style = MaterialTheme.typography.caption2, color = Color.White)
                        }
                    }
                }
            }

            if (!isWorldMapDownloaded && (navState.standaloneMode || navState.watchLocalMode)) {
                item {
                    Card(
                        onClick = { /* Could open map manager */ },
                        modifier = Modifier.padding(vertical = 4.dp),
                        backgroundPainter = CardDefaults.cardBackgroundPainter(Color(0xFFC15746).copy(alpha = 0.3f))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC15746), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("World map missing (Routing)", style = MaterialTheme.typography.caption2, color = Color.White)
                        }
                    }
                }
            }

            if (result.cuisine.isNotEmpty()) {
                item {
                    Text(
                        result.cuisine,
                        style = MaterialTheme.typography.caption2,
                        color = Color.LightGray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (result.stars.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        repeat(result.stars.toIntOrNull() ?: 0) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Yellow)
                        }
                    }
                }
            }

            if (result.brand.isNotEmpty() || result.operator.isNotEmpty()) {
                item {
                    val text = listOf(result.brand, result.operator).filter { it.isNotEmpty() }.joinToString(" - ")
                    Text(text, style = MaterialTheme.typography.caption2, color = Color.Gray)
                }
            }

            if (result.openingHours.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF00E5FF))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Opening Hours", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF))
                    }
                }
                item {
                    Text(
                        result.openingHours, 
                        style = MaterialTheme.typography.caption2, 
                        color = Color.LightGray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            if (result.address.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Address", style = MaterialTheme.typography.caption1, color = Color.Gray)
                    }
                }
                item {
                    Text(
                        result.address, 
                        style = MaterialTheme.typography.caption2, 
                        color = Color.Gray, 
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else {
                item {
                    Text(
                        String.format(java.util.Locale.US, "%.5f, %.5f", result.lat, result.lon),
                        style = MaterialTheme.typography.caption2,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                Chip(
                    onClick = { showModeSelection = true },
                    label = { Text("Navigate") },
                    icon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(),
                    enabled = isMapDownloaded && isWorldMapDownloaded || !navState.standaloneMode
                )
            }

            if (result.website.isNotEmpty()) {
                item {
                    Chip(
                        onClick = { 
                            // Synergy: Open website directly in phone's browser
                            WearCommandService.showOnPhone(context, result)
                        },
                        label = { Text("Website") },
                        secondaryLabel = { Text("Open on Phone", maxLines = 1) },
                        icon = { Icon(Icons.Default.Language, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }

            if (result.phone.isNotEmpty()) {
                item {
                    Chip(
                        onClick = { /* Could add call logic if Wear supports it or open on phone */ },
                        label = { Text("Call") },
                        secondaryLabel = { Text(result.phone) },
                        icon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }

            item {
                Chip(
                    onClick = onDismiss,
                    label = { Text("Close") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ModeSelectionScreen(
    result: SearchResultItem,
    onModeSelected: (Int, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val navState by NavigationStateHolder.state.collectAsState()
    var avoidTolls by remember { mutableStateOf(navState.avoidTolls) }
    var avoidMotorways by remember { mutableStateOf(navState.avoidMotorways) }
    var avoidFerries by remember { mutableStateOf(navState.avoidFerries) }
    var avoidUnpaved by remember { mutableStateOf(navState.avoidUnpaved) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            val displayName = result.name.ifEmpty { result.description }
            Text(displayName, style = MaterialTheme.typography.title3, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { onModeSelected(0, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = "Car", modifier = Modifier.size(20.dp))
                }
                Button(onClick = { onModeSelected(1, false, false, false, false) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Walk", modifier = Modifier.size(20.dp))
                }
                Button(onClick = { onModeSelected(2, false, false, false, false) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = "Bike", modifier = Modifier.size(20.dp))
                }
                Button(onClick = { onModeSelected(3, false, false, false, false) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.DirectionsTransit, contentDescription = "Transit", modifier = Modifier.size(20.dp))
                }
            }
        }

        item {
            RoutingOptionsRow(
                avoidTolls = avoidTolls,
                avoidMotorways = avoidMotorways,
                avoidFerries = avoidFerries,
                avoidUnpaved = avoidUnpaved,
                onOptionToggled = { type, newVal ->
                    when(type) {
                        RoadType.Toll -> avoidTolls = newVal
                        RoadType.Motorway -> avoidMotorways = newVal
                        RoadType.Ferry -> avoidFerries = newVal
                        RoadType.Dirty -> avoidUnpaved = newVal
                        else -> {}
                    }
                }
            )
        }

        item {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.height(32.dp).fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Cancel", style = MaterialTheme.typography.caption2)
            }
        }
    }
}

@Composable
fun SearchResultChip(result: SearchResultItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = result.name.ifEmpty { result.description }
    val subTitle = if (result.name.isNotEmpty() && result.description != "Dropped Pin" && result.description != "Previous Fix") result.description else ""
    
    val iconRes = remember(result.featureType) {
        val name = result.featureType.lowercase().replace(" ", "_")
        var id = context.resources.getIdentifier("ic_category_$name", "drawable", context.packageName)
        if (id == 0) id = context.resources.getIdentifier("ic_bookmark_$name", "drawable", context.packageName)
        if (id == 0) id = context.resources.getIdentifier("ic_$name", "drawable", context.packageName)
        id
    }

    val navState by NavigationStateHolder.state.collectAsState()
    
    var downloadStatus by remember { mutableStateOf(CountryItem.STATUS_DONE) }
    
    LaunchedEffect(result.lat, result.lon, navState.watchLocalMode) {
        if (!navState.watchLocalMode) {
            downloadStatus = CountryItem.STATUS_DONE
            return@LaunchedEffect
        }
        try {
            val countryId = MapManager.nativeFindCountry(result.lat, result.lon)
            if (countryId != null) {
                while (true) {
                    downloadStatus = MapManager.nativeGetStatus(countryId)
                    if (downloadStatus == CountryItem.STATUS_DONE) break
                    delay(2000)
                }
            }
        } catch (_: Throwable) {}
    }

    Chip(
        onClick = onClick,
        label = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, maxLines = 1, modifier = Modifier.weight(1f))
                if (result.distance.isNotEmpty()) {
                    Text(
                        result.distance,
                        style = MaterialTheme.typography.caption2,
                        color = Color(0xFF00E5FF),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        secondaryLabel = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (navState.watchLocalMode && downloadStatus != CountryItem.STATUS_DONE && downloadStatus != CountryItem.STATUS_UNKNOWN) {
                    Text("Syncing Map...", color = Color.Yellow, maxLines = 1)
                } else if (subTitle.isNotEmpty()) {
                    Text(subTitle, maxLines = 1)
                }
            }
        },
        icon = {
            if (iconRes != 0) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Unspecified
                )
            } else {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colors.primary
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors()
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun SearchScreenPreview() {
    SearchScreen()
}
