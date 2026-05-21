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
    var showRoutingOptions by remember { mutableStateOf(false) }
    var showLayerSettings by remember { mutableStateOf(false) }

    if (showPoiSettings) {
        PoiSettingsScreen(onBack = { showPoiSettings = false })
    } else if (showRoutingOptions) {
        RoutingOptionsScreen(onBack = { showRoutingOptions = false })
    } else if (showLayerSettings) {
        LayerSettingsScreen(onBack = { showLayerSettings = false })
    } else {
        MainSettingsList(
            onOpenPoiSettings = { showPoiSettings = true },
            onOpenRoutingOptions = { showRoutingOptions = true },
            onOpenLayerSettings = { showLayerSettings = true }
        )
    }
}

@Composable
fun MainSettingsList(onOpenPoiSettings: () -> Unit, onOpenRoutingOptions: () -> Unit, onOpenLayerSettings: () -> Unit) {
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
    
    val is3dEnabled = navState.is3dEnabled
    val is3dBuildingsEnabled = navState.is3dBuildingsEnabled
    val isAutoZoomEnabled = navState.isAutoZoomEnabled
    val measurementUnits = navState.measurementUnits
    val mapStyle = navState.mapStyle

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
            Chip(
                onClick = onOpenLayerSettings,
                label = { Text("Map Layers") },
                secondaryLabel = { Text("Subway, Biking, Hiking") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }

        item {
            ToggleChip(
                checked = is3dEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_3d", newVal).apply()
                        try {
                            app.organicmaps.sdk.Framework.nativeSet3dMode(newVal, current.is3dBuildingsEnabled)
                        } catch (_: Throwable) {}
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            is3dEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("3D View") },
                toggleControl = {
                    Switch(checked = is3dEnabled, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = is3dBuildingsEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_3d_buildings", newVal).apply()
                        try {
                            app.organicmaps.sdk.Framework.nativeSet3dMode(current.is3dEnabled, newVal)
                        } catch (_: Throwable) {}
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            is3dBuildingsEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("3D Buildings") },
                toggleControl = {
                    Switch(checked = is3dBuildingsEnabled, enabled = is3dEnabled)
                },
                enabled = is3dEnabled,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = isAutoZoomEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_auto_zoom", newVal).apply()
                        try {
                            app.organicmaps.sdk.Framework.nativeSetAutoZoomEnabled(newVal)
                        } catch (_: Throwable) {}
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            isAutoZoomEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Auto Zoom") },
                toggleControl = {
                    Switch(checked = isAutoZoomEnabled, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            val unitLabels = listOf("Metric (km)", "Imperial (mi)")
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Units", style = MaterialTheme.typography.caption2, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Chip(
                    onClick = {
                        NavigationStateHolder.update { current ->
                            val nextUnits = (current.measurementUnits + 1) % 2
                            prefs.edit().putInt("pref_munits", nextUnits).apply()
                            // Framework units setup is usually done via UnitLocale.setUnits which calls native code
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                measurementUnits = nextUnits,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text(unitLabels[measurementUnits.coerceIn(0, 1)]) },
                    secondaryLabel = { Text("Tap to change") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }

        item {
            val styleLabels = listOf("Day", "Night", "Auto", "Nav Auto")
            val styleValues = listOf("default", "night", "auto", "nav_auto")
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Map Style", style = MaterialTheme.typography.caption2, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
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
                    label = { Text(styleLabels[styleValues.indexOf(mapStyle).coerceAtLeast(0)]) },
                    secondaryLabel = { Text("Tap to change") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }

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
                    Switch(checked = mapEnabled, enabled = !standaloneMode)
                },
                enabled = !standaloneMode,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = watchLocalMode,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("watchLocalMode", newVal).apply()
                        if (!newVal) prefs.edit().putBoolean("forceWatchLocalMode", false).apply()
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
                    Switch(checked = watchLocalMode, enabled = !standaloneMode)
                },
                enabled = !standaloneMode,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = standaloneMode,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("disconnectFromPhone", newVal).apply()
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        current.copy(
                            standaloneMode = newVal,
                            mapEnabled = if (newVal) true else current.mapEnabled,
                            watchLocalMode = if (newVal) true else current.watchLocalMode,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
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
                checked = allowMobileData,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_mobile_data", newVal).apply()
                        try {
                            app.organicmaps.sdk.util.Config.setUseMobileDataSettings(
                                if (newVal) app.organicmaps.sdk.util.NetworkPolicy.Type.ALWAYS 
                                else app.organicmaps.sdk.util.NetworkPolicy.Type.NEVER
                            )
                        } catch (_: Throwable) {}
                        current.copy(
                            allowMobileData = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Mobile Data") },
                secondaryLabel = { Text("Use LTE if available") },
                toggleControl = {
                    Switch(checked = allowMobileData, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = forceGuiButtons,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_force_gui_buttons", newVal).apply()
                        current.copy(
                            forceGuiButtons = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Always Show Controls") },
                secondaryLabel = { Text("Ignore hardware buttons") },
                toggleControl = {
                    Switch(checked = forceGuiButtons, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
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
                label = { Text("Auto-Download") },
                secondaryLabel = { Text("Fetch maps for routes") },
                toggleControl = {
                    Switch(checked = autoDownload, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            val modes = listOf("BLUETOOTH_ONLY", "AUTO", "WIFI_ONLY")
            val modeLabels = listOf("Always Bluetooth", "Auto (Wi-Fi/BT)", "Wi-Fi Only")
            
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Map Sync Mode", style = MaterialTheme.typography.caption2, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Chip(
                    onClick = {
                        NavigationStateHolder.update { current ->
                            val currentIdx = modes.indexOf(current.mapDownloadMode).coerceAtLeast(0)
                            val nextIdx = (currentIdx + 1) % modes.size
                            val nextMode = modes[nextIdx]
                            prefs.edit().putString("mapDownloadMode", nextMode).apply()
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                mapDownloadMode = nextMode,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text(modeLabels[modes.indexOf(mapDownloadMode).coerceAtLeast(0)]) },
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
                        prefs.edit().putString("pref_wear_os_backend", newBackend).apply()
                        NavigationStateHolder.update(navState.copy(backend = newBackend))
                        
                        // 1. Sync preferences with OLD backend so phone knows to switch
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
                        
                        scope.launch {
                            delay(200)
                            // 2. Re-initialize watch's internal backend
                            app.organicmaps.wear.WearCommandService.initBackend(context)
                            // 3. Sync again with NEW backend
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            
                            val intent = Intent(context, app.organicmaps.wear.BluetoothWearDataListenerService::class.java)
                            if (newBackend == "BLUETOOTH") {
                                context.startService(intent)
                            } else if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
                                context.stopService(intent)
                            }
                        }
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
