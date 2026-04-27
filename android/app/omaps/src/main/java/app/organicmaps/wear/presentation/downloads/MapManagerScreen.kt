package app.organicmaps.wear.presentation.downloads

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.wear.NavigationStateHolder

@Composable
fun MapManagerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navState = NavigationStateHolder.state.value
    val centerLat = if (navState.lat != 0.0) navState.lat else 48.2082
    val centerLon = if (navState.lon != 0.0) navState.lon else 16.3738

    var mapName by remember { mutableStateOf("Locating...") }
    var mapId by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Checking map...") }
    var isDownloaded by remember { mutableStateOf(false) }

    // User overrides
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    var forceOffline by remember { mutableStateOf(prefs.getBoolean("forceWatchOfflineMaps", false)) }

    LaunchedEffect(centerLat, centerLon) {
        withContext(Dispatchers.Main) {
            try {
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                wearApp.waitForInitializationSuspend()
                val countryId = MapManager.nativeFindCountry(centerLat, centerLon)
                mapId = countryId
                if (countryId != null && countryId.isNotEmpty()) {
                    while (true) {
                        try {
                            val item = CountryItem.fill(countryId)
                            mapName = item.name
                            
                            if (item.status == CountryItem.STATUS_DONE) {
                                statusText = "Installed • " + String.format("%.1f MB", item.totalSize / 1024.0 / 1024.0)
                                isDownloaded = true
                            } else if (item.status == CountryItem.STATUS_DOWNLOADABLE) {
                                statusText = "Ready • " + String.format("%.1f MB", item.totalSize / 1024.0 / 1024.0)
                                isDownloaded = false
                            } else if (item.status == CountryItem.STATUS_PROGRESS || item.status == CountryItem.STATUS_ENQUEUED) {
                                statusText = "Downloading: ${item.progress.toInt()}%"
                                isDownloaded = false
                            } else {
                                statusText = "Status: ${item.status}"
                                isDownloaded = false
                            }
                        } catch (e: Exception) {
                            mapName = "Unknown Region"
                            statusText = "Cannot check map status"
                        }
                        delay(1000)
                    }
                } else {
                    mapName = "Unknown Area"
                    statusText = "Move to a valid region"
                }
            } catch (e: Throwable) {
                mapName = "Error"
                statusText = "Engine not ready"
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 10.dp, end = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Map Region",
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        item {
            Text(
                text = mapName,
                style = MaterialTheme.typography.body1,
                color = Color.White
            )
        }

        item {
            Text(
                text = statusText,
                style = MaterialTheme.typography.caption2,
                color = if (isDownloaded) Color.Green else Color.LightGray,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
        }

        // Live stream vs Offline Mode toggle
        item {
            ToggleChip(
                checked = !forceOffline, // True means we use Phone Live Stream
                onCheckedChange = { useLiveStream ->
                    forceOffline = !useLiveStream
                    prefs.edit().putBoolean("forceWatchOfflineMaps", forceOffline).apply()
                    
                    // Immediately apply state change
                    NavigationStateHolder.update(navState.copy(offlineMapsEnabled = forceOffline))
                },
                label = { Text("Stream from Phone", style = MaterialTheme.typography.button) },
                secondaryLabel = { Text("Saves watch battery & memory", style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.LightGray) },
                toggleControl = { RadioButton(selected = !forceOffline, enabled = true) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
        }

        item {
            ToggleChip(
                checked = forceOffline,
                onCheckedChange = { useOffline ->
                    forceOffline = useOffline
                    prefs.edit().putBoolean("forceWatchOfflineMaps", forceOffline).apply()
                    NavigationStateHolder.update(navState.copy(offlineMapsEnabled = forceOffline))
                },
                label = { Text("Standalone Map", style = MaterialTheme.typography.button) },
                secondaryLabel = { Text("Runs fully on watch without phone", style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.LightGray) },
                toggleControl = { RadioButton(selected = forceOffline, enabled = true) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
        }

        if (forceOffline && !isDownloaded && mapId != null) {
            item {
                Button(
                    onClick = {
                        try {
                            app.organicmaps.sdk.downloader.MapManager.startDownload(mapId!!)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF00E5FF))
                ) {
                    Text("Download over Wi-Fi", color = Color.Black)
                }
            }
        }

        if (forceOffline && isDownloaded && mapId != null) {
            item {
                Button(
                    onClick = {
                        try {
                            app.organicmaps.sdk.downloader.MapManager.nativeDelete(mapId!!)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Delete Map")
                }
            }
        }
    }
}
