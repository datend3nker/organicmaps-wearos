package app.organicmaps.wear.presentation.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.UiEvent
import app.organicmaps.wear.VirtualMwmManager
import app.organicmaps.wear.WatchStorage
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearMapDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MapManagerScreen(isVisible: Boolean = true) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    
    // Throttled location to avoid excessive re-polling on every tiny movement (approx 1km resolution)
    val centerLat = remember(navState.lat) { 
        val raw = if (navState.lat != 0.0) navState.lat else 48.2082
        round(raw * 100.0) / 100.0
    }
    val centerLon = remember(navState.lon) { 
        val raw = if (navState.lon != 0.0) navState.lon else 16.3738
        round(raw * 100.0) / 100.0
    }

    var pathStack by remember { mutableStateOf(listOf<String>()) }
    val currentRoot = pathStack.lastOrNull()
    
    var countries by remember { mutableStateOf<List<CountryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(value = true) }
    var searchQuery by remember { mutableStateOf("") }

    val listState = rememberScalingLazyListState()

    val downloadState by WearMapDownloader.downloadState.collectAsState()
    val downloadProgress by WearMapDownloader.downloadProgress.collectAsState()
    val currentMap by WearMapDownloader.currentMap.collectAsState()

    LaunchedEffect(currentRoot, centerLat, centerLon, searchQuery, isVisible) {
        if (!isVisible) return@LaunchedEffect
        
        withContext(Dispatchers.Default) {
            try {
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as WearApplication
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
                    
                    // Warm the partial-shadow disk cache off the main thread so the per-item
                    // isVirtual() lookups during recomposition stay O(1).
                    VirtualMwmManager.listPartialShadowIds(context)

                    withContext(Dispatchers.Main) {
                        countries = sorted
                        loading = false
                    }
                    delay(500.milliseconds) // Increased frequency for better download status responsiveness
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    val missingMapId = navState.missingMapId
    if (missingMapId != null) {
        val isDownloaded = remember(missingMapId, countries) {
            countries.any { it.id == missingMapId && (it.status == CountryItem.STATUS_DONE || it.present) } ||
            (try { MapManager.nativeGetStatus(missingMapId) == CountryItem.STATUS_DONE } catch(_: Throwable) { false }) ||
            VirtualMwmManager.isVirtual(context, missingMapId)
        }
        if (isDownloaded) {
            LaunchedEffect(missingMapId) {
                NavigationStateHolder.update { it.copy(missingMapId = null) }
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
                        scope.launch(Dispatchers.Main) {
                            WearMapDownloader.downloadOrStreamMap(context, mapId, forceInternet = true)
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

        // Storage awareness: free space + current streaming-cache usage. Helps the user pick
        // between full Copy to Watch (ample storage) and bounded streaming (tight storage).
        item {
            val freeBytes = remember(navState.lat, navState.lon) { WatchStorage.freeBytes(context) }
            val cacheBytes by VirtualMwmManager.cacheBytes.collectAsState()
            Text(
                "Free: ${WatchStorage.formatBytes(freeBytes)}  •  Stream cache: ${WatchStorage.formatBytes(cacheBytes)}",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (downloadState == WearMapDownloader.DownloadState.STREAMING_FROM_PHONE || 
            downloadState == WearMapDownloader.DownloadState.DOWNLOADING) {
            item {
                Chip(
                    onClick = { WearMapDownloader.cancel(context) },
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
            val downloadedItems = countries.filter { it.present && !VirtualMwmManager.isVirtual(context, it.id) }
            if (currentRoot == null && downloadedItems.isNotEmpty() && searchQuery.isEmpty()) {
                item {
                    Text("Downloaded", style = MaterialTheme.typography.caption1, color = Color(0xFF00FF00), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                items(downloadedItems) { item ->
                    CountryItemRow(item, pathStack, { pathStack = it }, scope)
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("All Regions", style = MaterialTheme.typography.caption1, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
            }

            val groups = countries
                .filter { currentRoot != null || !it.present || VirtualMwmManager.isVirtual(context, it.id) || searchQuery.isNotEmpty() }
                // Streamed shadows are, by definition, the region under you — surface them in "Near Me"
                // (native reports them as present=DOWNLOADED, which would otherwise bury them headerless).
                .groupBy {
                    if (currentRoot == null && searchQuery.isEmpty() && VirtualMwmManager.isVirtual(context, it.id))
                        CountryItem.CATEGORY_NEAR_ME
                    else it.category
                }
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
                    CountryItemRow(item, pathStack, { pathStack = it }, scope)
                }
            }
        }
    }
}

@Composable
fun CountryItemRow(item: CountryItem, pathStack: List<String>, onPathStackChanged: (List<String>) -> Unit, scope: CoroutineScope) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    
    val activeDownloadMap by WearMapDownloader.currentMap.collectAsState()
    val activeDownloadState by WearMapDownloader.downloadState.collectAsState()
    val activeDownloadProgress by WearMapDownloader.downloadProgress.collectAsState()

    val isThisDownloading = activeDownloadMap == item.id && 
        (activeDownloadState.name == "DOWNLOADING" || 
         activeDownloadState.name == "STREAMING_FROM_PHONE" || 
         activeDownloadState.name == "VALIDATING")

    val isDownloading = (item.status == CountryItem.STATUS_PROGRESS ||
                        item.status == CountryItem.STATUS_ENQUEUED ||
                        item.status == CountryItem.STATUS_APPLYING) || isThisDownloading
                        
    val isVirtual = VirtualMwmManager.isVirtual(context, item.id)
    val isInstalled = !isVirtual && (item.status == CountryItem.STATUS_DONE || item.status == CountryItem.STATUS_PARTLY || item.present)
    val isOnPhone = navState.phoneDownloadedMaps.contains(item.id)
    
    val progressPercent = if (isThisDownloading) (activeDownloadProgress * 100).toInt() else item.progress.toInt()
    
    val statusText = when {
        isDownloading -> "Downloading $progressPercent%"
        item.status == CountryItem.STATUS_ENQUEUED -> "Enqueued"
        item.status == CountryItem.STATUS_FAILED -> "Error - Tap to retry"
        // A streamed shadow is also "on phone" (that's where it streams from); show its streamed
        // identity first so it never reads as a plain pull/download target.
        isVirtual -> "Companion Map (Streamed) — tap to download"
        isOnPhone -> "Pull from Phone (${String.format(Locale.US, "%.1f MB", item.totalSize / 1024.0 / 1024.0)})"
        item.status == CountryItem.STATUS_DOWNLOADABLE -> "Download (${String.format(Locale.US, "%.1f MB", item.totalSize / 1024.0 / 1024.0)})"
        item.status == CountryItem.STATUS_DONE -> "Installed"
        else -> if (item.isExpandable) "${item.totalChildCount} regions" else "Status: ${item.status}"
    }

    // Only fully-installed (real) maps get the split chip with the delete action. A streamed shadow
    // is NOT deletable here — tapping its row upgrades it to a full download (downloadOrStreamMap
    // tears the shadow down then full-pulls), so it falls through to the plain Chip branch below.
    if (!item.isExpandable && isInstalled) {
        CustomSplitChip(
            onClick = {},
            onDeleteClick = {
                MapManager.delete(item.id)
            },
            label = { Text(item.name, maxLines = 1) },
            secondaryLabel = {
                Column {
                    Text(statusText, color = Color.Green)
                }
            },
            enabled = false,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    } else {
        Chip(
            onClick = {
                if (item.isExpandable) {
                    onPathStackChanged(pathStack + item.id)
                } else if (isVirtual || item.status == CountryItem.STATUS_DOWNLOADABLE || item.status == CountryItem.STATUS_FAILED || isOnPhone) {
                    if (item.totalSize > 0 && !WatchStorage.fitsComfortably(context, item.totalSize)) {
                        val free = WatchStorage.formatBytes(WatchStorage.freeBytes(context))
                        val size = WatchStorage.formatBytes(item.totalSize)
                        NavigationStateHolder.emitEvent(UiEvent.ShowToast(
                            "$size won't fit ($free free). It will stream on demand instead.",
                            Toast.LENGTH_LONG,
                        ))
                    } else {
                        scope.launch(Dispatchers.Main) {
                            WearMapDownloader.downloadOrStreamMap(context, item.id)
                        }
                    }
                }
            },
            label = { Text(item.name, maxLines = 1) },
            secondaryLabel = {
                Column {
                    Text(statusText, color = if (isInstalled) Color.Green else if (isVirtual) Color(0xFF00E5FF) else Color.LightGray)
                    if (isDownloading) {
                        val progressValue = if (isThisDownloading) activeDownloadProgress else (item.progress / 100f)
                        if (progressValue > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .padding(top = 2.dp)
                                    .background(Color.Gray.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progressValue)
                                        .fillMaxHeight()
                                        .background(Color(0xFF00E5FF))
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            colors = ChipDefaults.secondaryChipColors(),
            icon = {
                if (item.isExpandable) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                } else if (isVirtual) {
                    Icon(Icons.Default.CloudDownload, contentDescription = "Download full map", tint = Color(0xFF00E5FF))
                }
            }
        )
    }
}

@Composable
fun CustomSplitChip(
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    label: @Composable () -> Unit,
    secondaryLabel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.1f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.button.copy(color = if (enabled) Color.White else Color.LightGray)) {
                label()
            }
            Spacer(modifier = Modifier.height(1.dp))
            ProvideTextStyle(value = MaterialTheme.typography.caption2) {
                secondaryLabel()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Color.Gray.copy(alpha = 0.2f))
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(48.dp)
                .clickable(onClick = onDeleteClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.Red,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
