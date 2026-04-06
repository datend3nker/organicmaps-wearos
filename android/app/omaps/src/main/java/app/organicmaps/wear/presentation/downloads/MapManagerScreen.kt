package app.organicmaps.wear.presentation.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    var statusText by remember { mutableStateOf("Checking...") }
    var isDownloaded by remember { mutableStateOf(false) }

    LaunchedEffect(centerLat, centerLon) {
        withContext(Dispatchers.IO) {
            try {
                // Ensure native is loaded
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
                                statusText = "Downloaded"
                                isDownloaded = true
                            } else if (item.status == CountryItem.STATUS_DOWNLOADABLE) {
                                statusText = "Not downloaded"
                                isDownloaded = false
                            } else if (item.status == CountryItem.STATUS_PROGRESS || item.status == CountryItem.STATUS_ENQUEUED) {
                                statusText = "Downloading: ${item.progress.toInt()}%"
                                isDownloaded = false
                            } else if (item.status == CountryItem.STATUS_FAILED) {
                                statusText = "Failed"
                                isDownloaded = false
                            } else {
                                statusText = "Status: ${item.status}"
                                isDownloaded = false
                            }
                        } catch (e: Exception) {
                            mapName = "Error in loop"
                            statusText = e.message ?: e.toString()
                            e.printStackTrace()
                        }
                        delay(1000)
                    }
                } else {
                    mapName = "Unknown Area"
                    statusText = "Cannot find map"
                }
            } catch (e: Throwable) {
                mapName = "Error"
                statusText = e.message ?: e.toString()
                e.printStackTrace()
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Map Manager",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        item {
            Text(
                "Current Location:",
                style = MaterialTheme.typography.caption2,
                color = Color.LightGray
            )
        }
        item {
            Text(
                text = mapName,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        item {
            Text(
                text = statusText,
                style = MaterialTheme.typography.caption2,
                color = if (isDownloaded) Color.Green else Color.LightGray,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (!isDownloaded && mapId != null) {
            item {
                Button(
                    onClick = {
                        try {
                            MapManager.startDownload(mapId!!)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download Map")
                }
            }
        }

        if (isDownloaded && mapId != null) {
            item {
                Button(
                    onClick = {
                        try {
                            MapManager.nativeDelete(mapId!!)
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
