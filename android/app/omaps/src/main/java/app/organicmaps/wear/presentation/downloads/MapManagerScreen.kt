package app.organicmaps.wear.presentation.downloads

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.wear.NavigationStateHolder
import java.util.ArrayList

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ChevronRight

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState

@Composable
fun MapManagerScreen(isVisible: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Throttled location to avoid excessive re-polling on every tiny movement (approx 1km resolution)
    val centerLat = remember(navState.lat) { 
        val raw = if (navState.lat != 0.0) navState.lat else 48.2082
        kotlin.math.round(raw * 100.0) / 100.0
    }
    val centerLon = remember(navState.lon) { 
        val raw = if (navState.lon != 0.0) navState.lon else 16.3738
        kotlin.math.round(raw * 100.0) / 100.0
    }

    var pathStack by remember { mutableStateOf(listOf<String>()) }
    val currentRoot = pathStack.lastOrNull()
    
    var countries by remember { mutableStateOf<List<CountryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(value = true) }
    var searchQuery by remember { mutableStateOf("") }

    val listState = rememberScalingLazyListState()

    val downloadState by app.organicmaps.wear.WearMapDownloader.downloadState.collectAsState()
    val downloadProgress by app.organicmaps.wear.WearMapDownloader.downloadProgress.collectAsState()
    val currentMap by app.organicmaps.wear.WearMapDownloader.currentMap.collectAsState()

    LaunchedEffect(currentRoot, centerLat, centerLon, searchQuery, isVisible) {
        if (!isVisible) return@LaunchedEffect
        
        withContext(Dispatchers.Default) {
            try {
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                wearApp.waitForInitializationSuspend()
                
                while (true) {
                    val result = ArrayList<CountryItem>()
                    withContext(Dispatchers.Main) {
                        if (searchQuery.isEmpty()) {
                            MapManager.nativeListItems(currentRoot, centerLat, centerLon, true, false, result)
                        } else {
                            MapManager.nativeSearchItems(searchQuery, result)
                        }
                    }
                    
                    // Improved sorting: downloading first, then done/partly, then category/name
                    val sorted = result.filter { 
                        (it.id != "World" && it.id != "WorldCoasts") || !it.present
                    }.sortedWith { a, b ->
                        // HIGHLIGHT: Prioritize Near Me items for the current view
                        if (a.category == CountryItem.CATEGORY_NEAR_ME && b.category != CountryItem.CATEGORY_NEAR_ME) return@sortedWith -1
                        if (b.category == CountryItem.CATEGORY_NEAR_ME && a.category != CountryItem.CATEGORY_NEAR_ME) return@sortedWith 1

                        val aDone = a.status == CountryItem.STATUS_DONE || a.status == CountryItem.STATUS_PARTLY || a.present
                        val bDone = b.status == CountryItem.STATUS_DONE || b.status == CountryItem.STATUS_PARTLY || b.present
                        
                        // Downloaded/Partly downloaded first
                        if (aDone != bDone) return@sortedWith if (aDone) -1 else 1
                        
                        val aDownloading = a.status == CountryItem.STATUS_PROGRESS || a.status == CountryItem.STATUS_ENQUEUED || a.status == CountryItem.STATUS_APPLYING
                        val bDownloading = b.status == CountryItem.STATUS_PROGRESS || b.status == CountryItem.STATUS_ENQUEUED || b.status == CountryItem.STATUS_APPLYING
                        
                        // Then currently downloading
                        if (aDownloading != bDownloading) return@sortedWith if (aDownloading) -1 else 1
                        
                        // Near Me is category 0, should naturally come before 1 (Downloaded) and 2 (Available)
                        if (a.category != b.category) return@sortedWith a.category.compareTo(b.category)
                        
                        // Prioritize World map if it's in the list (means it's not present)
                        if (a.id == "World") return@sortedWith -1
                        if (b.id == "World") return@sortedWith 1

                        a.name.compareTo(b.name)
                    }
                    
                    withContext(Dispatchers.Main) {
                        countries = sorted
                        loading = false
                    }
                    delay(500) // Increased frequency for better download status responsiveness
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 10.dp, end = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (navState.missingMapId != null) {
            item {
                Card(
                    onClick = {
                        val mapId = navState.missingMapId!!
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            app.organicmaps.wear.WearMapDownloader.downloadOrStreamMap(context, mapId, forceInternet = true)
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Column {
                        Text("Not on Phone", style = MaterialTheme.typography.caption1, color = Color.Red)
                        Text("Map '${navState.missingMapId}' missing. Download via Internet?", style = MaterialTheme.typography.body2)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Download via Internet", color = Color(0xFF00E5FF), style = MaterialTheme.typography.button)
                    }
                }
            }
        }

        if (navState.backend == "GMS" && !navState.standaloneMode) {
            item {
                Text(
                    "Syncing via Phone",
                    style = MaterialTheme.typography.caption2,
                    color = Color.LightGray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        if (downloadState == app.organicmaps.wear.WearMapDownloader.DownloadState.STREAMING_FROM_PHONE || 
            downloadState == app.organicmaps.wear.WearMapDownloader.DownloadState.DOWNLOADING) {
            item {
                Chip(
                    onClick = { app.organicmaps.wear.WearMapDownloader.cancel(context) },
                    label = { Text("Cancel Sync: $currentMap") },
                    secondaryLabel = { Text("${(downloadProgress * 100).toInt()}%") },
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = { Icon(Icons.Default.Close, contentDescription = "Cancel") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
        }

        item {
            val title = if (searchQuery.isNotEmpty()) "Search Results" else if (currentRoot == null) "Map Manager" else MapManager.nativeGetName(currentRoot)
            Text(
                title,
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color(0xFF00E5FF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                    }),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text("Search regions...", style = TextStyle(color = Color.Gray, fontSize = 14.sp))
                        }
                        innerTextField()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = "Clear", 
                        modifier = Modifier.size(16.dp).clickable { 
                            searchQuery = "" 
                            pathStack = emptyList() // Reset to root when clearing search
                        },
                        tint = Color.Gray
                    )
                }
            }
        }

        if (currentRoot != null && searchQuery.isEmpty()) {
            item {
                Button(
                    onClick = { pathStack = pathStack.dropLast(1) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Back")
                }
            }
        }

        if (loading) {
            item { CircularProgressIndicator() }
        } else {
            val downloadedItems = countries.filter { it.present }
            if (currentRoot == null && downloadedItems.isNotEmpty() && searchQuery.isEmpty()) {
                item {
                    Text("Downloaded", style = MaterialTheme.typography.caption1, color = Color(0xFF00FF00), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                items(downloadedItems) { item ->
                    CountryItemRow(item, pathStack) { pathStack = it }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("All Regions", style = MaterialTheme.typography.caption1, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
            }

            val groups = countries.filter { currentRoot != null || !it.present || searchQuery.isNotEmpty() }.groupBy { it.category }
            val categories = groups.keys.sorted()
            
            for (cat in categories) {
                val groupItems = groups[cat] ?: continue
                if (currentRoot == null && groupItems.isNotEmpty() && searchQuery.isEmpty()) {
                    item {
                        val header = when (cat) {
                            CountryItem.CATEGORY_NEAR_ME -> "Near Me"
                            CountryItem.CATEGORY_DOWNLOADED -> "Downloaded"
                            else -> "Available"
                        }
                        if (header != "Downloaded") {
                            Text(header, style = MaterialTheme.typography.caption1, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                    }
                }
                
                items(groupItems) { item ->
                    CountryItemRow(item, pathStack) { pathStack = it }
                }
            }
        }
    }
}

@Composable
fun CountryItemRow(item: CountryItem, pathStack: List<String>, onPathStackChanged: (List<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val isDownloading = item.status == CountryItem.STATUS_PROGRESS || item.status == CountryItem.STATUS_ENQUEUED || item.status == CountryItem.STATUS_APPLYING
    val isInstalled = item.status == CountryItem.STATUS_DONE || item.status == CountryItem.STATUS_PARTLY || item.present
    val isOnPhone = navState.phoneDownloadedMaps.contains(item.id)
    
    val statusText = when {
        item.status == CountryItem.STATUS_DONE -> "Installed"
        isDownloading -> "Downloading ${item.progress.toInt()}%"
        item.status == CountryItem.STATUS_ENQUEUED -> "Enqueued"
        item.status == CountryItem.STATUS_FAILED -> "Error - Tap to retry"
        isOnPhone -> "Pull from Phone (${java.lang.String.format(java.util.Locale.US, "%.1f MB", item.totalSize / 1024.0 / 1024.0)})"
        item.status == CountryItem.STATUS_DOWNLOADABLE -> "Download (${java.lang.String.format(java.util.Locale.US, "%.1f MB", item.totalSize / 1024.0 / 1024.0)})"
        else -> if (item.isExpandable) "${item.totalChildCount} regions" else "Status: ${item.status}"
    }

    Chip(
        onClick = {
            if (item.isExpandable) {
                onPathStackChanged(pathStack + item.id)
            } else if (item.status == CountryItem.STATUS_DOWNLOADABLE || item.status == CountryItem.STATUS_FAILED || isOnPhone) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    app.organicmaps.wear.WearMapDownloader.downloadOrStreamMap(context, item.id)
                }
            }
        },
        label = { Text(item.name, maxLines = 1) },
        secondaryLabel = { 
            Column {
                Text(statusText, color = if (isInstalled) Color.Green else Color.LightGray)
                if (isDownloading && item.progress > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .padding(top = 2.dp)
                            .background(Color.Gray.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.progress / 100f)
                                .fillMaxHeight()
                                .background(Color(0xFF00E5FF))
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = ChipDefaults.secondaryChipColors(),
        icon = {
            if (item.isExpandable) {
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            } else if (isInstalled) {
                // Using Button as a wrapper for Delete icon to make it clickable independently is tricky in Chip icon.
                // We'll use the chip's icon slot for the status or action.
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(24.dp).clickable { MapManager.delete(item.id) })
            }
        }
    )
}
