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
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.sync.SyncSettingsRegistry
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

    fun getK(canonical: String) = SyncSettingsRegistry.getLocalKey(canonical, true)
    
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
                    NavigationStateHolder.updateSettings { current ->
                        prefs.edit().putBoolean(getK(WearProtocol.SETTING_MAP_ENABLED), newVal).commit()
                        current.copy(mapEnabled = newVal)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                    NavigationStateHolder.updateSettings { current ->
                        prefs.edit().putBoolean("pref_show_on_lock_screen", newVal).commit()
                        current.copy(showOnLockScreen = newVal)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                    NavigationStateHolder.updateSettings { current ->
                        val currentIdx = styleValues.indexOf(current.mapStyle).coerceAtLeast(0)
                        val nextIdx = (currentIdx + 1) % styleValues.size
                        val nextStyle = styleValues[nextIdx]
                        prefs.edit().putString(getK(WearProtocol.SETTING_MAP_STYLE), nextStyle).commit()
                        current.copy(mapStyle = nextStyle)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                    NavigationStateHolder.updateSettings { current ->
                        prefs.edit().putBoolean(getK(WearProtocol.SETTING_3D_ENABLED), newVal).commit()
                        Framework.nativeSet3dMode(newVal, current.is3dBuildingsEnabled)
                        current.copy(is3dEnabled = newVal)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                    NavigationStateHolder.updateSettings { current ->
                        prefs.edit().putBoolean(getK(WearProtocol.SETTING_3D_BUILDINGS_ENABLED), newVal).commit()
                        Framework.nativeSet3dMode(current.is3dEnabled, newVal)
                        current.copy(is3dBuildingsEnabled = newVal)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                    NavigationStateHolder.updateSettings { current ->
                        prefs.edit().putBoolean(getK(WearProtocol.SETTING_AUTO_ZOOM_ENABLED), newVal).commit()
                        Framework.nativeSetAutoZoomEnabled(newVal)
                        current.copy(isAutoZoomEnabled = newVal)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
        item { SettingHeader("Operation Mode") }

        item {
            val backendLabels = listOf("Companion (GMS)", "Companion (BT)", "Standalone (Watch only)")
            val backendValues = listOf("GMS", "BLUETOOTH", "STANDALONE")
            Chip(
                onClick = {
                    NavigationStateHolder.updateSettings { current ->
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
                                return@updateSettings current
                            }
                        }

                        val isStandalone = nextBackend == "STANDALONE"
                        
                        // 1. Tell phone to switch too
                        if (!isStandalone) {
                            app.organicmaps.wear.WearCommandService.sendBackendSwitch(context, nextBackend)
                        }

                        // 2. Local prefs
                        prefs.edit()
                            .putString(getK(WearProtocol.SETTING_BACKEND), nextBackend)
                            .putBoolean(getK(WearProtocol.SETTING_STANDALONE_MODE), isStandalone)
                            .apply()
                            
                        // 3. Re-init
                        app.organicmaps.wear.WearCommandService.initBackend(context)
                        current.copy(
                            backend = nextBackend,
                            standaloneMode = isStandalone
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
                        NavigationStateHolder.updateSettings { current ->
                            prefs.edit().putBoolean(getK(WearProtocol.SETTING_WATCH_LOCAL_MODE), newVal).commit()
                            current.copy(watchLocalMode = newVal)
                        }
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                // Must match the PHONE's enum (donottranslate.xml / WearOsSettingsFragment:
                // PHONE_SYNC, DIRECT_DOWNLOAD) so the setting round-trips across devices. The watch
                // previously used "INTERNET" here, which the phone didn't recognize and vice-versa —
                // that was the "mapDownloadMode doesn't propagate / mismatch on both devices" bug.
                val modeValues = listOf("PHONE_SYNC", "DIRECT_DOWNLOAD")
                Chip(
                    onClick = {
                        NavigationStateHolder.updateSettings { current ->
                            val currentIdx = modeValues.indexOf(current.mapDownloadMode).coerceAtLeast(0)
                            val nextIdx = (currentIdx + 1) % modeValues.size
                            val nextMode = modeValues[nextIdx]
                            prefs.edit().putString(getK(WearProtocol.SETTING_MAP_DOWNLOAD_MODE), nextMode).commit()
                            current.copy(mapDownloadMode = nextMode)
                        }
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                        NavigationStateHolder.updateSettings { current ->
                            prefs.edit().putBoolean("pref_mobile_data", newVal).apply()
                            if (newVal) {
                                try {
                                    app.organicmaps.sdk.downloader.MapManager.nativeEnableDownloadOn3g()
                                } catch (_: Throwable) {}
                            }
                            app.organicmaps.wear.WearCommandService.syncPreferences(context)
                            current.copy(allowMobileData = newVal)
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
                        NavigationStateHolder.updateSettings { current ->
                            prefs.edit().putBoolean(getK(WearProtocol.SETTING_AUTO_DOWNLOAD), newVal).commit()
                            current.copy(autoDownloadRouteMaps = newVal)
                        }
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                        NavigationStateHolder.updateSettings { current ->
                            prefs.edit().putBoolean(getK(WearProtocol.SETTING_SYNC_NOTIFICATIONS_ENABLED), newVal).apply()
                            current.copy(syncNotificationsEnabled = newVal)
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
                        NavigationStateHolder.updateSettings { current ->
                            val currentIdx = sourceValues.indexOf(current.locationSource).coerceAtLeast(0)
                            val nextIdx = (currentIdx + 1) % sourceValues.size
                            val nextSource = sourceValues[nextIdx]
                            prefs.edit().putString(getK(WearProtocol.SETTING_LOCATION_SOURCE), nextSource).commit()
                            current.copy(locationSource = nextSource)
                        }
                        app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
                    NavigationStateHolder.updateSettings { current ->
                        val nextUnits = (current.measurementUnits + 1) % 2
                        prefs.edit().putInt(getK(WearProtocol.SETTING_MEASUREMENT_UNITS), nextUnits).commit()
                        current.copy(measurementUnits = nextUnits)
                    }
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
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
