package app.organicmaps.wear.presentation.settings

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService
import app.organicmaps.sdk.Framework
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
    
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            // Permission granted, re-init backend
            app.organicmaps.wear.WearCommandService.initBackend(context)
        }
    }

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
                        prefs.edit().putString("pref_wear_os_map_style", nextStyle).apply()
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
            ToggleChip(
                checked = navState.is3dEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_wear_os_3d", newVal).apply()
                        Framework.nativeSet3dMode(newVal, current.is3dBuildingsEnabled)
                        WearCommandService.syncPreferences(context)
                        current.copy(
                            is3dEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("3D Perspective") },
                secondaryLabel = { Text(if (navState.is3dEnabled) "Enabled" else "Disabled") },
                toggleControl = {
                    Switch(checked = navState.is3dEnabled, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = navState.is3dBuildingsEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_wear_os_3d_buildings", newVal).apply()
                        Framework.nativeSet3dMode(current.is3dEnabled, newVal)
                        WearCommandService.syncPreferences(context)
                        current.copy(
                            is3dBuildingsEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("3D Buildings") },
                secondaryLabel = { Text(if (navState.is3dBuildingsEnabled) "Enabled" else "Disabled") },
                toggleControl = {
                    Switch(checked = navState.is3dBuildingsEnabled, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            ToggleChip(
                checked = navState.isAutoZoomEnabled,
                onCheckedChange = { newVal ->
                    NavigationStateHolder.update { current ->
                        prefs.edit().putBoolean("pref_wear_os_auto_zoom", newVal).apply()
                        Framework.nativeSetAutoZoomEnabled(newVal)
                        WearCommandService.syncPreferences(context)
                        current.copy(
                            isAutoZoomEnabled = newVal,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Auto Zoom") },
                secondaryLabel = { Text(if (navState.isAutoZoomEnabled) "Enabled" else "Disabled") },
                toggleControl = {
                    Switch(checked = navState.isAutoZoomEnabled, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        item {
            Chip(
                onClick = onOpenLayerSettings,
                label = { Text("Map Layers") },
                secondaryLabel = { Text("Subway, Biking, Hiking, Contours") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
            )
        }

        // --- CONNECTIVITY GROUP ---
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SettingHeader("Connectivity") }

        item {
            val backendLabels = listOf("Google Play (GSM)", "Bluetooth (OSS)", "Standalone")
            val backendValues = listOf("GMS", "BLUETOOTH", "STANDALONE")
            Chip(
                onClick = {
                    NavigationStateHolder.update { current ->
                        val currentIdx = backendValues.indexOf(current.backend).coerceAtLeast(0)
                        val nextIdx = (currentIdx + 1) % backendValues.size
                        val nextBackend = backendValues[nextIdx]
                        
                        // Check permissions if switching to Bluetooth
                        if (nextBackend == "BLUETOOTH" && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val missing = mutableListOf<String>()
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN)
                            
                            if (missing.isNotEmpty()) {
                                bluetoothLauncher.launch(missing.toTypedArray())
                                return@update current
                            }
                        }

                        val isStandalone = nextBackend == "STANDALONE"
                        
                        // 1. Tell phone to switch too
                        if (!isStandalone) {
                            app.organicmaps.wear.WearCommandService.sendBackendSwitch(context, nextBackend)
                        }

                        // 2. Local prefs
                        prefs.edit()
                            .putString("pref_wear_os_backend", nextBackend)
                            .putBoolean("disconnectFromPhone", isStandalone)
                            .apply()
                            
                        // 3. Re-init
                        app.organicmaps.wear.WearCommandService.initBackend(context)
                        current.copy(
                            backend = nextBackend,
                            standaloneMode = isStandalone,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        )
                    }
                },
                label = { Text("Backend") },
                secondaryLabel = { Text(backendLabels[backendValues.indexOf(backend).coerceAtLeast(0)]) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ChipDefaults.secondaryChipColors()
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
                    label = { Text("Download via") },
                    secondaryLabel = { Text(modeLabels[modeValues.indexOf(mapDownloadMode).coerceAtLeast(0)]) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ChipDefaults.secondaryChipColors()
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
                ToggleChip(
                    checked = autoDownload,
                    onCheckedChange = { newVal ->
                        NavigationStateHolder.update { current ->
                            prefs.edit().putBoolean("pref_wear_os_auto_download_route_maps", newVal).apply()
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(
                                autoDownloadRouteMaps = newVal,
                                lastSettingsInteractionTime = System.currentTimeMillis()
                            )
                        }
                    },
                    label = { Text("Auto-Download Maps for Routes") },
                    secondaryLabel = { Text("Automatically download missing maps during navigation") },
                    toggleControl = {
                        Switch(checked = autoDownload, enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
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
                    secondaryLabel = { 
                        if (android.os.Build.VERSION.SDK_INT >= 33 && 
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            Text("Permission required")
                        } else {
                            Text("Show progress in status bar")
                        }
                    },
                    toggleControl = {
                        Switch(checked = navState.syncNotificationsEnabled, enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            if (android.os.Build.VERSION.SDK_INT >= 33 && 
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                item {
                    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                    ) { _ -> }
                    Chip(
                        onClick = { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                        label = { Text("Grant Notif. Permission") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ChipDefaults.primaryChipColors()
                    )
                }
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

        // --- ROUTING GROUP ---
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SettingHeader("Routing & General") }

        item {
            val unitLabels = listOf("Metric (km)", "Imperial (mi)")
            Chip(
                onClick = {
                    NavigationStateHolder.update { current ->
                        val nextUnits = (current.measurementUnits + 1) % 2
                        prefs.edit().putInt("pref_wear_os_munits", nextUnits).apply()
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
