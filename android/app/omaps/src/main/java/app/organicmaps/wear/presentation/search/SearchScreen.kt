package app.organicmaps.wear.presentation.search

import android.content.Intent
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.WearApplication
import android.util.Log
import kotlinx.coroutines.launch
import app.organicmaps.sdk.search.SearchEngine
import app.organicmaps.sdk.search.SearchListener
import app.organicmaps.sdk.search.SearchResult
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.Router

import app.organicmaps.sdk.Framework

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.MainViewModel

@Composable
fun SearchScreen(modifier: Modifier = Modifier, mainViewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    
    var searchQuery by mainViewModel.searchQuery
    var selectedResult by remember { mutableStateOf<SearchResultItem?>(null) }
    val focusRequester = remember { FocusRequester() }
    val navState by NavigationStateHolder.state.collectAsState()
    val listState = rememberScalingLazyListState()

    // Standalone Search Logic
    DisposableEffect(navState.watchLocalMode) {
        val listener = object : SearchListener {
            override fun onResultsUpdate(results: Array<out SearchResult>, timestamp: Long) {
                Log.d("SearchScreen", "Received ${results.size} standalone results")
                NavigationStateHolder.update { current ->
                    if (current.watchLocalMode) {
                        val converted = results.map {
                            SearchResultItem(
                                name = it.getTitle(context) ?: "",
                                description = if (it.description != null) it.description.localizedFeatureType ?: "" else "",
                                lat = it.lat,
                                lon = it.lon,
                                type = it.type,
                            )
                        }
                        current.copy(
                            searchResults = converted,
                            isSearching = true
                        )
                    } else current
                }
            }

            override fun onResultsEnd(timestamp: Long) {
                NavigationStateHolder.update { current ->
                    if (current.watchLocalMode) {
                        current.copy(isSearching = false)
                    } else current
                }
            }
        }
        if (navState.watchLocalMode) {
            SearchEngine.INSTANCE.addListener(listener)
        }
        onDispose {
            SearchEngine.INSTANCE.removeListener(listener)
        }
    }

    // Request history on launch
    LaunchedEffect(Unit) {
        if (!navState.watchLocalMode) {
            WearCommandService.requestSearchHistory(context)
        }
    }

    val performSearch: (String) -> Unit = { query ->
        coroutineScope.launch {
            NavigationStateHolder.update { it.copy(
                isSearching = true,
                searchResults = emptyList()
            ) }
            val state = NavigationStateHolder.state.value
            if (state.watchLocalMode) {
                try {
                    (context.applicationContext as WearApplication).waitForInitializationSuspend()
                    SearchEngine.INSTANCE.cancel()
                    Framework.nativeRestoreDownloadQueue()
                    
                    val currentLat = if (state.lat != 0.0) state.lat else 48.2082
                    val currentLon = if (state.lon != 0.0) state.lon else 16.3738
                    val hasLocation = (state.lat != 0.0 && state.lon != 0.0)
                    
                    Framework.nativeSetSearchViewport(currentLat, currentLon, 14)
                    SearchEngine.INSTANCE.search(context, query, false, System.currentTimeMillis(), hasLocation, currentLat, currentLon)
                } catch (e: Exception) {
                    Log.e("SearchScreen", "Search failed: ${e.message}")
                    NavigationStateHolder.update { it.copy(isSearching = false) }
                }
            } else {
                WearCommandService.search(context, query)
            }
        }
    }

    val onQueryChanged: (TextFieldValue) -> Unit = { newValue ->
        searchQuery = newValue
    }

    // Voice input launcher
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val query = data?.get(0)
        if (!query.isNullOrEmpty()) {
            searchQuery = TextFieldValue(text = query, selection = TextRange(query.length))
            performSearch(query)
            coroutineScope.launch {
                listState.animateScrollToItem(1)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        timeText = {
            TimeText(
                startLinearContent = {
                    if (!navState.isPhoneConnected && !navState.watchLocalMode) {
                        Text("OFFLINE", style = TextStyle(color = Color.Red, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                },
                startCurvedContent = {
                    if (!navState.isPhoneConnected && !navState.watchLocalMode) {
                        curvedText(
                            text = "OFFLINE",
                            style = CurvedTextStyle(
                                color = Color.Red,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            )
        }
    ) {
        if (selectedResult != null) {
            ModeSelectionScreen(
                result = selectedResult!!,
                onModeSelected = { routerType ->
                    coroutineScope.launch {
                        val state = NavigationStateHolder.state.value
                        if (state.watchLocalMode) {
                            try {
                                val wearApp = context.applicationContext as WearApplication
                                wearApp.waitForInitializationSuspend()
                                val startPoint = wearApp.organicMaps.locationHelper.myPosition
                                val destination = MapObject.createMapObject(MapObject.POI, selectedResult!!.name, selectedResult!!.description, selectedResult!!.lat, selectedResult!!.lon)
                                val router = when (routerType) {
                                    0 -> Router.Vehicle
                                    1 -> Router.Pedestrian
                                    2 -> Router.Bicycle
                                    else -> Router.Transit
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
                                    routePoints = emptyList(),
                                    distToTurn = "",
                                    nextStreet = "",
                                    distToTarget = "",
                                    eta = 0,
                                    completionPercent = 0.0,
                                    turnLat = 0.0,
                                    turnLon = 0.0
                                ) }
                            } catch (e: Exception) {
                                Log.e("SearchScreen", "Route planning failed: ${e.message}")
                            }
                        } else {
                            WearCommandService.selectSearchResult(context, selectedResult!!, routerType)
                        }
                        selectedResult = null
                        searchQuery = TextFieldValue("")
                    }
                },
                onCancel = { 
                    selectedResult = null 
                }
            )
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = 32.dp, start = 8.dp, end = 8.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
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
        }
    }
}

@Composable
fun ModeSelectionScreen(
    result: SearchResultItem,
    onModeSelected: (Int) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val displayName = result.name.ifEmpty { result.description }
        Text(displayName, style = MaterialTheme.typography.title3, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { onModeSelected(0) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.DirectionsCar, contentDescription = "Car", modifier = Modifier.size(20.dp))
            }
            Button(onClick = { onModeSelected(1) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Walk", modifier = Modifier.size(20.dp))
            }
            Button(onClick = { onModeSelected(2) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = "Bike", modifier = Modifier.size(20.dp))
            }
            Button(onClick = { onModeSelected(3) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.DirectionsTransit, contentDescription = "Transit", modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.height(32.dp).fillMaxWidth()
        ) {
            Text("Cancel", style = MaterialTheme.typography.caption2)
        }
    }
}

@Composable
fun SearchResultChip(result: SearchResultItem, onClick: () -> Unit) {
    val title = result.name.ifEmpty { result.description }
    val subTitle = if (result.name.isNotEmpty()) result.description else ""
    
    Chip(
        onClick = onClick,
        label = { Text(title, maxLines = 1) },
        secondaryLabel = if (subTitle.isNotEmpty()) { { Text(subTitle, maxLines = 1) } } else null,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors()
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun SearchScreenPreview() {
    SearchScreen()
}
