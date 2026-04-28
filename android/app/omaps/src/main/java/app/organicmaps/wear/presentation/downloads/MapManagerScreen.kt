package app.organicmaps.wear.presentation.downloads

import android.content.Context
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

    var currentRoot by remember { mutableStateOf<String?>(null) }
    var countries by remember { mutableStateOf<List<CountryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    var forceOffline by remember { mutableStateOf(prefs.getBoolean("forceWatchOfflineMaps", false)) }

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
            } catch (e: Throwable) {
                loading = false
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
                "Map Manager",
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 8.dp)
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
                label = { Text("Standalone Mode", style = MaterialTheme.typography.button) },
                secondaryLabel = { Text(if (forceOffline) "Using local maps" else "Streaming from phone", style = MaterialTheme.typography.caption2) },
                toggleControl = { Checkbox(checked = forceOffline) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        if (currentRoot != null) {
            item {
                Button(
                    onClick = { currentRoot = null },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Back to Top")
                }
            }
        }

        if (loading) {
            item { CircularProgressIndicator() }
        } else {
            items(countries) { item ->
                val statusText = when (item.status) {
                    CountryItem.STATUS_DONE -> "Installed"
                    CountryItem.STATUS_DOWNLOADABLE -> "Download (${String.format("%.1f MB", item.totalSize / 1024.0 / 1024.0)})"
                    CountryItem.STATUS_PROGRESS -> "Downloading ${item.progress.toInt()}%"
                    CountryItem.STATUS_ENQUEUED -> "Enqueued"
                    CountryItem.STATUS_FAILED -> "Error - Tap to retry"
                    else -> if (item.isExpandable) "${item.childCount} regions" else "Status: ${item.status}"
                }

                Chip(
                    onClick = {
                        if (item.isExpandable) {
                            currentRoot = item.id
                        } else if (item.status == CountryItem.STATUS_DOWNLOADABLE || item.status == CountryItem.STATUS_FAILED) {
                            MapManager.startDownload(item.id)
                        } else if (item.status == CountryItem.STATUS_DONE) {
                            MapManager.nativeDelete(item.id)
                        }
                    },
                    label = { Text(item.name, maxLines = 1) },
                    secondaryLabel = { Text(statusText, maxLines = 1, color = if (item.status == CountryItem.STATUS_DONE) Color.Green else Color.LightGray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
