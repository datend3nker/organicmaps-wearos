package app.organicmaps.wear.presentation.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import app.organicmaps.wear.NavigationStateHolder

@Composable
fun SettingsScreen() {
    var showPoiSettings by remember { mutableStateOf(false) }

    if (showPoiSettings) {
        PoiSettingsScreen(onBack = { showPoiSettings = false })
    } else {
        MainSettingsList(onOpenPoiSettings = { showPoiSettings = true })
    }
}

@Composable
fun MainSettingsList(onOpenPoiSettings: () -> Unit) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    
    var autoDownload by remember { mutableStateOf(prefs.getBoolean("autoDownloadRouteMaps", true)) }
    var mapDownloadMode by remember { mutableStateOf(prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY") ?: "BLUETOOTH_ONLY") }
    var backend by remember { mutableStateOf(prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS") }
    var mapEnabled by remember { mutableStateOf(navState.mapEnabled) }
    var watchLocalMode by remember { mutableStateOf(navState.watchLocalMode) }
    var standaloneMode by remember { mutableStateOf(navState.standaloneMode) }

    // Update local state when navState changes (from phone sync)
    LaunchedEffect(navState.mapEnabled, navState.watchLocalMode, navState.standaloneMode) {
        mapEnabled = navState.mapEnabled
        watchLocalMode = navState.watchLocalMode
        standaloneMode = navState.standaloneMode
    }

    // Request fresh settings when screen is opened
    LaunchedEffect(Unit) {
        app.organicmaps.wear.WearCommandService.requestPreferences(context)
        app.organicmaps.wear.WearCommandService.sendPing(context)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Watch Settings",
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        item {
            Chip(
                onClick = onOpenPoiSettings,
                label = { Text("Map Details") },
                secondaryLabel = { Text("Configure POIs") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }

        item {
            ToggleChip(
                checked = mapEnabled,
                onCheckedChange = { 
                    mapEnabled = it
                    prefs.edit().putBoolean("mapEnabled", it).apply()
                    NavigationStateHolder.update(navState.copy(mapEnabled = it))
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                },
                label = { Text("Map UI") },
                secondaryLabel = { Text(if (mapEnabled) "Map is visible" else "Map is hidden") },
                toggleControl = {
                    Switch(checked = mapEnabled, enabled = !standaloneMode)
                },
                enabled = !standaloneMode,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = watchLocalMode,
                onCheckedChange = { 
                    watchLocalMode = it
                    prefs.edit().putBoolean("watchLocalMode", it).apply()
                    if (!it) prefs.edit().putBoolean("forceWatchLocalMode", false).apply()
                    NavigationStateHolder.update(navState.copy(watchLocalMode = it))
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                },
                label = { Text("Local Maps") },
                secondaryLabel = { Text(if (watchLocalMode) "Using watch storage" else "Streaming from phone") },
                toggleControl = {
                    Switch(checked = watchLocalMode, enabled = !standaloneMode)
                },
                enabled = !standaloneMode,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = standaloneMode,
                onCheckedChange = { 
                    standaloneMode = it
                    prefs.edit().putBoolean("disconnectFromPhone", it).apply()
                    val newState = navState.copy(
                        standaloneMode = it,
                        mapEnabled = if (it) true else navState.mapEnabled,
                        watchLocalMode = if (it) true else navState.watchLocalMode
                    )
                    NavigationStateHolder.update(newState)
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                },
                label = { Text("Standalone") },
                secondaryLabel = { Text(if (standaloneMode) "Independent mode" else "Connected to phone") },
                toggleControl = {
                    Switch(checked = standaloneMode, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = autoDownload,
                onCheckedChange = { 
                    autoDownload = it
                    prefs.edit().putBoolean("autoDownloadRouteMaps", it).apply()
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                },
                label = { Text("Auto-Download") },
                secondaryLabel = { Text("Fetch maps for routes") },
                toggleControl = {
                    Switch(checked = autoDownload, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            val modes = listOf("BLUETOOTH_ONLY", "WIFI_ONLY", "AUTO")
            val modeLabels = listOf("Bluetooth", "Wi-Fi", "Auto")
            
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Download Policy", style = MaterialTheme.typography.caption2, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Chip(
                    onClick = {
                        val nextIdx = (modes.indexOf(mapDownloadMode) + 1) % modes.size
                        mapDownloadMode = modes[nextIdx]
                        prefs.edit().putString("mapDownloadMode", mapDownloadMode).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                    },
                    label = { Text(modeLabels[modes.indexOf(mapDownloadMode)]) },
                    secondaryLabel = { Text("Tap to change") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }

        if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
            item {
                Text(
                    "Sync Backend",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                ToggleChip(
                    checked = backend == "GMS",
                    onCheckedChange = {
                        val newBackend = if (it) "GMS" else "BLUETOOTH"
                        backend = newBackend
                        prefs.edit().putString("pref_wear_os_backend", newBackend).apply()
                        
                        val intent = Intent(context, app.organicmaps.wear.BluetoothWearDataListenerService::class.java)
                        if (newBackend == "BLUETOOTH") {
                            context.startService(intent)
                        } else if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
                            context.stopService(intent)
                        }
                        app.organicmaps.wear.WearCommandService.initBackend(context)
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                    },
                    label = { Text("Google Services") },
                    secondaryLabel = { Text(if (backend == "GMS") "Recommended" else "Using Bluetooth") },
                    toggleControl = {
                        Checkbox(checked = backend == "GMS", enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
        }
    }
}
