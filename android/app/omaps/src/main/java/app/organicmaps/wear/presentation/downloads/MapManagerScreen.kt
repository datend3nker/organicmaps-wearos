package app.organicmaps.wear.presentation.downloads

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.wear.NavigationStateHolder
import java.util.ArrayList

@Composable
fun MapManagerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    
    val centerLat = if (navState.lat != 0.0) navState.lat else 48.2082
    val centerLon = if (navState.lon != 0.0) navState.lon else 16.3738

    var pathStack by remember { mutableStateOf(listOf<String>()) }
    val currentRoot = pathStack.lastOrNull()
    
    var countries by remember { mutableStateOf<List<CountryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(value = true) }

    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    var forceOffline by remember { mutableStateOf(prefs.getBoolean("forceWatchOfflineMaps", false)) }
    var disconnectFromPhone by remember { mutableStateOf(prefs.getBoolean("disconnectFromPhone", false)) }

    LaunchedEffect(currentRoot, centerLat, centerLon) {
        withContext(Dispatchers.Main) {
            try {
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                wearApp.waitForInitializationSuspend()
                
                while (true) {
                    val result = ArrayList<CountryItem>()
                    MapManager.nativeListItems(currentRoot, centerLat, centerLon, true, false, result)
                    countries = result
                    loading = false
                    delay(2000)
                }
            } catch (_: Throwable) {
                loading = false
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 10.dp, end = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            val title = if (currentRoot == null) "Map Manager" else MapManager.nativeGetName(currentRoot)
            Text(
                title,
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (currentRoot == null) {
            item {
                ToggleChip(
                    checked = forceOffline,
                    onCheckedChange = { useOffline ->
                        forceOffline = useOffline
                        prefs.edit().putBoolean("forceWatchOfflineMaps", forceOffline).apply()
                        NavigationStateHolder.update(navState.copy(offlineMapsEnabled = forceOffline))
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                    },
                    label = { Text("Offline Mode", style = MaterialTheme.typography.button) },
                    secondaryLabel = { Text(if (forceOffline) "Using local maps" else "Streaming from phone", style = MaterialTheme.typography.caption2) },
                    toggleControl = { Checkbox(checked = forceOffline) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
            
            item {
                ToggleChip(
                    checked = disconnectFromPhone,
                    onCheckedChange = { disconnect ->
                        disconnectFromPhone = disconnect
                        prefs.edit().putBoolean("disconnectFromPhone", disconnect).apply()
                        
                        val bluetoothServiceIntent = Intent(context, app.organicmaps.wear.BluetoothWearDataListenerService::class.java)
                        if (disconnect) {
                            NavigationStateHolder.update(navState.copy(isPhoneConnected = false))
                            context.stopService(bluetoothServiceIntent)
                        } else {
                            // Try to reconnect
                            app.organicmaps.wear.WearCommandService.initBackend(context)
                            val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
                            if (app.organicmaps.wear.BuildConfig.FLAVOR == "oss" || selectedBackend == "BLUETOOTH") {
                                context.startService(bluetoothServiceIntent)
                            }
                        }
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                    },
                    label = { Text("Standalone Mode", style = MaterialTheme.typography.button) },
                    secondaryLabel = { Text(if (disconnectFromPhone) "Phone link cut" else "Phone link active", style = MaterialTheme.typography.caption2) },
                    toggleControl = { Switch(checked = disconnectFromPhone) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
        } else {
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
            val groups = countries.groupBy { it.category }
            val categories = groups.keys.sorted()
            
            for (cat in categories) {
                val groupItems = groups[cat] ?: continue
                if (currentRoot == null && groupItems.isNotEmpty()) {
                    item {
                        val header = when (cat) {
                            CountryItem.CATEGORY_NEAR_ME -> "Near Me"
                            CountryItem.CATEGORY_DOWNLOADED -> "Downloaded"
                            else -> "Available"
                        }
                        Text(header, style = MaterialTheme.typography.caption1, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                }
                
                items(groupItems) { item ->
                    val isDownloading = item.status == CountryItem.STATUS_PROGRESS || item.status == CountryItem.STATUS_ENQUEUED || item.status == CountryItem.STATUS_APPLYING
                    
                    val statusText = when (item.status) {
                        CountryItem.STATUS_DONE -> "Installed"
                        CountryItem.STATUS_DOWNLOADABLE -> "Download (${java.lang.String.format(java.util.Locale.US, "%.1f MB", item.totalSize / 1024.0 / 1024.0)})"
                        CountryItem.STATUS_PROGRESS -> "Downloading ${item.progress.toInt()}%"
                        CountryItem.STATUS_ENQUEUED -> "Enqueued"
                        CountryItem.STATUS_FAILED -> "Error - Tap to retry"
                        else -> if (item.isExpandable) "${item.totalChildCount} regions" else "Status: ${item.status}"
                    }

                    Chip(
                        onClick = {
                            if (item.isExpandable) {
                                pathStack = pathStack + item.id
                            } else if ((item.status == CountryItem.STATUS_DOWNLOADABLE || item.status == CountryItem.STATUS_FAILED)) {
                                MapManager.startDownload(item.id)
                            } else if (item.status == CountryItem.STATUS_DONE) {
                                MapManager.nativeDelete(item.id)
                            }
                        },
                        label = { Text(item.name, maxLines = 1) },
                        secondaryLabel = { 
                            Column {
                                Text(statusText, maxLines = 1, color = if (item.status == CountryItem.STATUS_DONE) Color.Green else Color.LightGray)
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
                        modifier = Modifier.fillMaxWidth(),
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    }
}
