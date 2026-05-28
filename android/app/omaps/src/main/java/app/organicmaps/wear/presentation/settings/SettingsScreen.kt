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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen() {
    var showPoiSettings by remember { mutableStateOf(false) }
    var showLayerSettings by remember { mutableStateOf(false) }

    if (showPoiSettings) {
        PoiSettingsScreen(onBack = { showPoiSettings = false })
    } else if (showLayerSettings) {
        LayerSettingsScreen(onBack = { showLayerSettings = false })
    } else {
        MainSettingsList(
            onOpenPoiSettings = { showPoiSettings = true },
            onOpenLayerSettings = { showLayerSettings = true }
        )
    }
}

@Composable
fun MainSettingsList(onOpenPoiSettings: () -> Unit, onOpenLayerSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navState by NavigationStateHolder.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    
    // Use derived state from navState to ensure UI is reactively in sync
    val mapEnabled = navState.mapEnabled
    val watchLocalMode = navState.watchLocalMode
    val standaloneMode = navState.standaloneMode
    val allowMobileData = navState.allowMobileData
    val forceGuiButtons = navState.forceGuiButtons
    val autoDownload = navState.autoDownloadRouteMaps
    val mapDownloadMode = navState.mapDownloadMode
    val backend = navState.backend
    
    val mapStyle = navState.mapStyle
    val locationSource = navState.locationSource
    val measurementUnits = navState.measurementUnits

    // Request fresh settings when screen is opened
    LaunchedEffect(Unit) {
        app.organicmaps.wear.WearCommandService.requestPreferences(context)
        app.organicmaps.wear.WearCommandService.sendPing(context)
    }
    
    // Listen for remote updates to briefly disable local syncing
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // Remote updates are now handled via NavigationState.lastSettingsInteractionTime 
                // and the BluetoothWearDataListenerService.
            }
        }
        val filter = android.content.IntentFilter("app.organicmaps.wear.SETTINGS_CHANGED")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
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

        // --- DISPLAY GROUP ---
        item { SettingHeader("Display") }
        
        item {
            ToggleChip(
                checked = mapEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("mapEnabled", newVal).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            mapEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Map UI") },
                secondaryLabel = { Text(if (mapEnabled) "Map is visible" else "Map is hidden") },
                toggleControl = {
                    Switch(checked = mapEnabled, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = navState.showOnLockScreen,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_show_on_lock_screen", newVal).apply()
                        current.copy(
                            showOnLockScreen = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Show on Lock Screen") },
                secondaryLabel = { Text("Display nav when locked") },
                toggleControl = {
                    Switch(checked = navState.showOnLockScreen, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            val styleLabels = listOf("Day", "Night", "Auto", "Nav Auto")
            val styleValues = listOf("default", "night", "auto", "nav_auto")
            Chip(
                onClick = {
                    NavigationStateHolder.update { current ->
                        val currentIdx = styleValues.indexOf(current.mapStyle).coerceAtLeast(0)
                        val nextIdx = (currentIdx + 1) % styleValues.size
                        val nextStyle = styleValues[nextIdx]
                        prefs.edit().putString("pref_map_style", nextStyle).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            mapStyle = nextStyle,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Map Style") },
                secondaryLabel = { Text(styleLabels[styleValues.indexOf(mapStyle).coerceAtLeast(0)]) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }

        item {
            Chip(
                onClick = onOpenLayerSettings,
                label = { Text("Map Layers") },
                secondaryLabel = { Text("Subway, Biking, Hiking") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }

        // --- CONNECTIVITY GROUP ---
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SettingHeader("Connectivity") }

        item {
            ToggleChip(
                checked = standaloneMode,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("disconnectFromPhone", newVal).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            standaloneMode = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Standalone Mode") },
                secondaryLabel = { Text(if (standaloneMode) "Disconnected from phone" else "Connected to phone") },
                toggleControl = {
                    Switch(checked = standaloneMode, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        if (!standaloneMode) {
            item {
                ToggleChip(
                    checked = watchLocalMode,
                    onCheckedChange = { newVal ->
                        NavigationStateHolder.update { current ->
                            prefs.edit().putBoolean("watchLocalMode", newVal).apply()
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                watchLocalMode = newVal,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text("Local Maps") },
                    secondaryLabel = { Text(if (watchLocalMode) "Using watch storage" else "Streaming from phone") },
                    toggleControl = {
                        Switch(checked = watchLocalMode, enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            item {
                ToggleChip(
                    checked = allowMobileData,
                    onCheckedChange = { newVal ->
                        NavigationStateHolder.update { current ->
                            prefs.edit().putBoolean("pref_mobile_data", newVal).apply()
                            if (newVal) {
                                try {
                                    app.organicmaps.sdk.downloader.MapManager.nativeEnableDownloadOn3g()
                                } catch (_: Throwable) {}
                            }
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                allowMobileData = newVal,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text("Mobile Data") },
                    secondaryLabel = { Text(if (allowMobileData) "Enabled for downloads" else "Disabled") },
                    toggleControl = {
                        Switch(checked = allowMobileData, enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            item {
                val modeLabels = listOf("Phone Sync", "Direct (Internet)")
                val modeValues = listOf("PHONE_SYNC", "INTERNET")
                Chip(
                    onClick = {
                        NavigationStateHolder.update { current ->
                            val currentIdx = modeValues.indexOf(current.mapDownloadMode).coerceAtLeast(0)
                            val nextIdx = (currentIdx + 1) % modeValues.size
                            val nextMode = modeValues[nextIdx]
                            prefs.edit().putString("mapDownloadMode", nextMode).apply()
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                mapDownloadMode = nextMode,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text("Map Sync Mode") },
                    secondaryLabel = { Text(modeLabels[modeValues.indexOf(mapDownloadMode).coerceAtLeast(0)]) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            item {
                ToggleChip(
                    checked = navState.syncNotificationsEnabled,
                    onCheckedChange = { newVal ->
                        NavigationStateHolder.update { current ->
                            prefs.edit().putBoolean("pref_sync_notifications", newVal).apply()
                            current.copy(
                                syncNotificationsEnabled = newVal,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text("Sync Notifications") },
                    secondaryLabel = { Text("Show progress in status bar") },
                    toggleControl = {
                        Switch(checked = navState.syncNotificationsEnabled, enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            item {
                val sourceLabels = listOf("Auto (Fused)", "Phone Only")
                val sourceValues = listOf("AUTO", "PHONE_ONLY")
                Chip(
                    onClick = {
                        NavigationStateHolder.update { current ->
                            val currentIdx = sourceValues.indexOf(current.locationSource).coerceAtLeast(0)
                            val nextIdx = (currentIdx + 1) % sourceValues.size
                            val nextSource = sourceValues[nextIdx]
                            prefs.edit().putString("locationSource", nextSource).apply()
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                locationSource = nextSource,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text("Location Source") },
                    secondaryLabel = { Text(sourceLabels[sourceValues.indexOf(locationSource).coerceAtLeast(0)]) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }

        item {
            ToggleChip(
                checked = autoDownload,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("autoDownloadRouteMaps", newVal).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            autoDownloadRouteMaps = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text(if (backend == "GMS") "Auto-Sync" else "Auto-Download") },
                secondaryLabel = { Text(if (backend == "GMS") "Request maps from phone" else "Fetch maps for routes") },
                toggleControl = {
                    Switch(checked = autoDownload, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        // --- ROUTING GROUP ---
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SettingHeader("Routing & General") }

        item {
            val unitLabels = listOf("Metric (km)", "Imperial (mi)")
            Chip(
                onClick = {
                    NavigationStateHolder.update { current ->
                        val nextUnits = (current.measurementUnits + 1) % 2
                        prefs.edit().putInt("pref_munits", nextUnits).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            measurementUnits = nextUnits,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Measurement Units") },
                secondaryLabel = { Text(unitLabels[measurementUnits.coerceIn(0, 1)]) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }
        
        item {
            Chip(
                onClick = onOpenPoiSettings,
                label = { Text("POI Visibility") },
                secondaryLabel = { Text("Choose shown categories") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }
    }
}

@Composable
fun SettingHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption1,
        color = Color.Gray,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}
