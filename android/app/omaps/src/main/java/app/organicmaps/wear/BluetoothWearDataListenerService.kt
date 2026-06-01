package app.organicmaps.wear

import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.organicmaps.wear.presentation.Omaps
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

/**
 * F-Droid implementation of data listener using raw Bluetooth RFCOMM Sockets.
 * This replaces the Google Play Services WearableListenerService.
 */
class BluetoothWearDataListenerService : Service() {
    companion object {
        private const val TAG = "BluetoothDataListener"
        private val OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130")
        
        @Volatile
        var activeSocket: BluetoothSocket? = null
            private set
    }

    private var isRunning = false
    private val mapOutputStreams = mutableMapOf<String, java.io.FileOutputStream>()
    private val bookmarkOutputStreams = mutableMapOf<Long, java.io.FileOutputStream>()

    override fun onCreate() {
        super.onCreate()
        startListening()
    }

    override fun onDestroy() {
        isRunning = false
        activeSocket?.let {
            try { it.close() } catch (e: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "app.organicmaps.wear.CANCEL_SYNC") {
            val mapId = intent.getStringExtra("mapId")
            if (mapId != null) {
                cancelMapSync(mapId)
            }
        }
        return START_STICKY
    }

    private fun cancelMapSync(mapId: String) {
        Log.d(TAG, "Cleaning up cancelled map sync for $mapId")
        mapOutputStreams.remove(mapId)?.let {
            try { it.close() } catch (ignored: Exception) {}
        }
        
        try {
            val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(this)
            val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
            val versionedPath = java.io.File(storagePath, dataVersion.toString())
            val tmpFile = java.io.File(versionedPath, "$mapId.mwm.tmp")
            if (tmpFile.exists()) {
                tmpFile.delete()
                Log.d(TAG, "Deleted temporary file: ${tmpFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up .tmp file for $mapId", e)
        }
    }

    private fun startListening() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "DEBUG_BT: BLUETOOTH_CONNECT permission missing")
                // Don't stop service, maybe user grants it later
            }
        }
        isRunning = true
        thread {
            Log.d(TAG, "DEBUG_BT: Bluetooth listener thread started (Watch as Client)")
            var retryDelay = 5000L
            while (isRunning) {
                if (activeSocket?.isConnected != true) {
                    val socket = connectToPhone()
                    if (socket != null) {
                        retryDelay = 5000L // Reset delay on success
                        try {
                            handleClient(socket)
                        } catch (e: java.io.IOException) {
                            Log.w(TAG, "DEBUG_BT: Connection lost: ${e.message}")
                            activeSocket?.close()
                            activeSocket = null
                        } catch (e: Exception) {
                            Log.e(TAG, "DEBUG_BT: Error handling socket: ${e.message}")
                        }
                    } else {
                        // Exponential backoff to save power when phone not found
                        retryDelay = (retryDelay * 2).coerceAtMost(60000L) // Max 1 minute
                        Log.d(TAG, "DEBUG_BT: Phone not found, retrying in ${retryDelay/1000}s")
                    }
                }
                Thread.sleep(retryDelay)
            }
            Log.d(TAG, "DEBUG_BT: Bluetooth listener thread exiting")
        }
    }

    private fun connectToPhone(): BluetoothSocket? {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return null
        if (!adapter.isEnabled) return null

        try {
            val pairedDevices = adapter.bondedDevices ?: return null
            for (device in pairedDevices) {
                Log.d(TAG, "Attempting to connect to paired device: ${device.name}")
                try {
                    val socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID)
                    socket.connect()
                    Log.d(TAG, "Successfully connected to ${device.name}")
                    return socket
                } catch (e: Exception) {
                    Log.d(TAG, "Connection to ${device.name} failed: ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
        }
        return null
    }

    private fun handleClient(socket: BluetoothSocket) {
        activeSocket = socket
        Log.d(TAG, "DEBUG_BT: Connected to phone. Updating status.")
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            isPhoneConnected = true
        ))

        WearCommandService.requestPreferences(this)
        WearCommandService.requestBookmarks(this)
        WearCommandService.syncSearchHistory(this)

        // Trigger Virtual MWM for World map if not present locally
        if (app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World") != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
            WearCommandService.requestMwmMetadata(this, "World")
        }
        thread {
            try {
                val input = socket.getInputStream()
                while (isRunning && socket.isConnected) {
                    val typeRaw = input.read()
                    if (typeRaw == -1) {
                        Log.i(TAG, "DEBUG_BT: Socket closed by phone (EOF)")
                        break
                    }
                    val type = typeRaw.toByte()

                    val lengthBuffer = ByteArray(4)
                    input.readFully(lengthBuffer)
                    val length = ByteBuffer.wrap(lengthBuffer).int
                    Log.d(TAG, "DEBUG_BT: Receiving message type $type, size $length")

                    val payload = ByteArray(length)
                    input.readFully(payload)

                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                        isPhoneConnected = true
                    ))
                    NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
                    (application as WearApplication).onActivityReceived()
                    processMessage(type, payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "DEBUG_BT: Receiver thread loop error: ${e.message}")
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                    isPhoneConnected = false
                ))
            } finally {
                if (activeSocket == socket) activeSocket = null
                try { socket.close() } catch (ignored: Exception) {}
                Log.i(TAG, "DEBUG_BT: Connection closed, state reset")
            }
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.IOException("EOF")
            offset += read
        }
    }

    private fun processMessage(type: Byte, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        when (type.toInt()) {
            1 -> { // MSG_TYPE_NAV_STATUS
                (application as WearApplication).onPongReceived()
                val active = buffer.get().toInt() == 1
                val currentState = NavigationStateHolder.state.value
                if (!active) {
                    NavigationStateHolder.update(currentState.copy(
                        isActive = false,
                        isNavigating = false,
                        isRouteBuilding = false
                    ), force = true)
                    return
                }
                val carDir = buffer.get().toInt()
                val pedDir = buffer.get().toInt()
                val exitNum = buffer.get().toInt()
                val progress = buffer.float.toDouble()
                val lat = buffer.double
                val lon = buffer.double
                val turnLat = buffer.double
                val turnLon = buffer.double
                
                val bearing = buffer.float
                val speed = buffer.float.toDouble()
                val speedLimit = buffer.float.toDouble()

                val routeLen = if (buffer.remaining() >= 4) buffer.int else 0
                val streetLen = buffer.int
                val distLen = buffer.int
                
                val street = String(data, buffer.position(), streetLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + streetLen)
                val dist = String(data, buffer.position(), distLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + distLen)

                val routePoints = mutableListOf<Pair<Double, Double>>()
                if (routeLen > 0 && buffer.remaining() >= routeLen * 4 * 2) {
                    val lats = FloatArray(routeLen)
                    val lons = FloatArray(routeLen)
                    for (i in 0 until routeLen) lats[i] = buffer.float
                    for (i in 0 until routeLen) lons[i] = buffer.float
                    for (i in 0 until routeLen) {
                        routePoints.add(Pair(lats[i].toDouble(), lons[i].toDouble()))
                    }
                }
                
                val newState = currentState.copy(
                    isActive = true,
                    isNavigating = true,
                    isRouteBuilding = false,
                    carDirection = carDir,
                    pedestrianDirection = pedDir,
                    exitNum = exitNum,
                    completionPercent = progress,
                    lat = lat,
                    lon = lon,
                    turnLat = turnLat,
                    turnLon = turnLon,
                    bearing = bearing,
                    speedMps = speed,
                    speedLimitMps = speedLimit,
                    nextStreet = street,
                    distToTurn = dist,
                    routePoints = if (routePoints.isNotEmpty()) routePoints else currentState.routePoints,
                    isPhoneConnected = true
                )
                NavigationStateHolder.update(newState)

                // Pass location to native core only if NOT in standalone mode
                // This prevents location jitter where watch and phone fight for control
                if (!newState.standaloneMode) {
                    try {
                        System.loadLibrary("organicmaps")
                        app.organicmaps.sdk.location.LocationState.nativeLocationUpdated(
                            System.currentTimeMillis(),
                            lat, lon,
                            5.0f, // hAcc
                            0.0, // alt
                            speed.toFloat(),
                            bearing
                        )
                    } catch (_: Throwable) {}
                }

                if (!currentState.isActive) launchOmaps()
            }
            2 -> { // MSG_TYPE_SEARCH_RESULTS
                val isSearching = buffer.get().toInt() == 1
                val results = mutableListOf<SearchResultItem>()
                while (buffer.hasRemaining()) {
                    val nameLen = buffer.int
                    val name = String(data, buffer.position(), nameLen, StandardCharsets.UTF_8)
                    buffer.position(buffer.position() + nameLen)
                    
                    val descLen = if (buffer.remaining() >= 4) buffer.int else 0
                    val desc = if (descLen > 0 && buffer.remaining() >= descLen) {
                        val s = String(data, buffer.position(), descLen, StandardCharsets.UTF_8)
                        buffer.position(buffer.position() + descLen)
                        s
                    } else ""
                    
                    val lat = buffer.double
                    val lon = buffer.double

                    val distLen = if (buffer.remaining() >= 4) buffer.int else 0
                    val dist = if (distLen > 0 && buffer.remaining() >= distLen) {
                        val s = String(data, buffer.position(), distLen, StandardCharsets.UTF_8)
                        buffer.position(buffer.position() + distLen)
                        s
                    } else ""

                    val featureLen = if (buffer.remaining() >= 4) buffer.int else 0
                    val feature = if (featureLen > 0 && buffer.remaining() >= featureLen) {
                        val s = String(data, buffer.position(), featureLen, StandardCharsets.UTF_8)
                        buffer.position(buffer.position() + featureLen)
                        s
                    } else ""

                    results.add(SearchResultItem(
                        name = name, 
                        description = desc, 
                        lat = lat, 
                        lon = lon, 
                        distance = dist, 
                        featureType = feature
                    ))
                }
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                    searchResults = results,
                    isSearching = isSearching
                ))
            }
            3 -> { // MSG_TYPE_SEARCH_HISTORY
                val count = buffer.int
                val history = mutableListOf<String>()
                repeat(count) {
                    val len = buffer.int
                    val s = String(data, buffer.position(), len, StandardCharsets.UTF_8)
                    buffer.position(buffer.position() + len)
                    history.add(s)
                }
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(searchHistory = history))
            }
            4 -> { // MSG_TYPE_PREFERENCES
                val mapEnabled = buffer.get().toInt() == 1
                val watchLocalMode = buffer.get().toInt() == 1
                val standaloneMode = buffer.get().toInt() == 1
                val autoDownload = if (buffer.remaining() > 0) buffer.get().toInt() == 1 else true
                
                val modeLen = if (buffer.remaining() >= 4) buffer.int else 0
                val mapDownloadMode = if (modeLen > 0 && buffer.remaining() >= modeLen) {
                    val bytes = ByteArray(modeLen)
                    buffer.get(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                } else "PHONE_SYNC"

                val backendLen = if (buffer.remaining() >= 4) buffer.int else 0
                val backend = if (backendLen > 0 && buffer.remaining() >= backendLen) {
                    val bytes = ByteArray(backendLen)
                    buffer.get(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                } else "GMS"

                val poiMask = if (buffer.remaining() >= 4) buffer.int else 0x3F
                
                var is3dEnabled = true
                var is3dBuildingsEnabled = true
                var isAutoZoomEnabled = true
                var measurementUnits = 0
                var mapStyle = "default"
                
                if (buffer.remaining() >= 3) {
                    is3dEnabled = buffer.get() == 1.toByte()
                    is3dBuildingsEnabled = buffer.get() == 1.toByte()
                    isAutoZoomEnabled = buffer.get() == 1.toByte()
                }
                
                if (buffer.remaining() >= 4) {
                    measurementUnits = buffer.getInt()
                }
                
                if (buffer.remaining() >= 4) {
                    val styleLen = buffer.getInt()
                    if (styleLen > 0 && buffer.remaining() >= styleLen) {
                        val styleBytes = ByteArray(styleLen)
                        buffer.get(styleBytes)
                        mapStyle = String(styleBytes, StandardCharsets.UTF_8)
                    }
                }

                var avoidTolls = false
                var avoidMotorways = false
                var avoidFerries = false
                var avoidUnpaved = false
                var syncNotificationsEnabled = true
                if (buffer.remaining() >= 5) {
                    avoidTolls = buffer.get() == 1.toByte()
                    avoidMotorways = buffer.get() == 1.toByte()
                    avoidFerries = buffer.get() == 1.toByte()
                    avoidUnpaved = buffer.get() == 1.toByte()
                    syncNotificationsEnabled = buffer.get() == 1.toByte()
                } else if (buffer.remaining() >= 4) {
                    avoidTolls = buffer.get() == 1.toByte()
                    avoidMotorways = buffer.get() == 1.toByte()
                    avoidFerries = buffer.get() == 1.toByte()
                    avoidUnpaved = buffer.get() == 1.toByte()
                }

                var transitEnabled = false
                var bikingEnabled = false
                var hikingEnabled = false
                var isolinesEnabled = false
                if (buffer.remaining() >= 4) {
                    transitEnabled = buffer.get() == 1.toByte()
                    bikingEnabled = buffer.get() == 1.toByte()
                    hikingEnabled = buffer.get() == 1.toByte()
                    isolinesEnabled = buffer.get() == 1.toByte()
                }

                var isTrackRecording = false
                if (buffer.remaining() >= 1) {
                    isTrackRecording = buffer.get() == 1.toByte()
                }

                var locationSource = "AUTO"
                if (buffer.remaining() >= 4) {
                    val locSrcLen = buffer.int
                    if (locSrcLen > 0 && buffer.remaining() >= locSrcLen) {
                        val locSrcBytes = ByteArray(locSrcLen)
                        buffer.get(locSrcBytes)
                        locationSource = String(locSrcBytes, StandardCharsets.UTF_8)
                    }
                }

                var recordingStartTime = 0L
                if (buffer.remaining() >= 8) {
                    recordingStartTime = buffer.long
                }

                val timestamp = if (buffer.remaining() >= 8) buffer.long else 0L

                val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                val currentState = NavigationStateHolder.state.value
                
                // TIMESTAMP-BASED WINNING LOGIC
                if (timestamp > 0 && timestamp < currentState.lastSettingsInteractionTime) {
                    Log.d(TAG, "Ignoring stale remote preferences. Remote: $timestamp, LocalInteraction: ${currentState.lastSettingsInteractionTime}")
                    return
                }
                
                val oldMapEnabled = prefs.getBoolean("mapEnabled", false)
                val oldWatchLocalMode = prefs.getBoolean("watchLocalMode", false)
                val oldStandaloneMode = prefs.getBoolean("disconnectFromPhone", false)
                val oldAutoDownload = prefs.getBoolean("autoDownloadRouteMaps", true)
                val oldDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC")
                val oldBackend = prefs.getString("pref_wear_os_backend", "GMS")
                val oldPoiMask = prefs.getInt("poiCategoriesMask", 0x3F)
                val oldIs3d = prefs.getBoolean("pref_wear_os_3d", true)
                val oldIs3dBld = prefs.getBoolean("pref_wear_os_3d_buildings", true)
                val oldAutoZoom = prefs.getBoolean("pref_wear_os_auto_zoom", true)
                val oldUnits = prefs.getInt("pref_wear_os_munits", 0)
                val oldStyle = prefs.getString("pref_wear_os_map_style", "default")
                
                val oldAvoidTolls = prefs.getBoolean("pref_wear_os_avoid_tolls", false)
                val oldAvoidMotorways = prefs.getBoolean("pref_wear_os_avoid_motorways", false)
                val oldAvoidFerries = prefs.getBoolean("pref_wear_os_avoid_ferries", false)
                val oldAvoidUnpaved = prefs.getBoolean("pref_wear_os_avoid_unpaved", false)
                val oldTransit = prefs.getBoolean("pref_wear_os_transit", false)
                val oldBiking = prefs.getBoolean("pref_wear_os_biking", false)
                val oldHiking = prefs.getBoolean("pref_wear_os_hiking", false)
                val oldIsolines = prefs.getBoolean("pref_wear_os_isolines", false)
                val oldLocSrc = prefs.getString("locationSource", "AUTO")
                val oldRecording = NavigationStateHolder.state.value.isTrackRecording
                
                if (oldMapEnabled == mapEnabled && 
                    oldWatchLocalMode == watchLocalMode && 
                    oldStandaloneMode == standaloneMode &&
                    oldAutoDownload == autoDownload &&
                    oldDownloadMode == mapDownloadMode &&
                    oldBackend == backend &&
                    oldPoiMask == poiMask &&
                    oldIs3d == is3dEnabled &&
                    oldIs3dBld == is3dBuildingsEnabled &&
                    oldAutoZoom == isAutoZoomEnabled &&
                    oldUnits == measurementUnits &&
                    oldStyle == mapStyle &&
                    oldAvoidTolls == avoidTolls &&
                    oldAvoidMotorways == avoidMotorways &&
                    oldAvoidFerries == avoidFerries &&
                    oldAvoidUnpaved == avoidUnpaved &&
                    oldTransit == transitEnabled &&
                    oldBiking == bikingEnabled &&
                    oldHiking == hikingEnabled &&
                    oldIsolines == isolinesEnabled &&
                    oldLocSrc == locationSource &&
                    oldRecording == isTrackRecording) {
                    // Probably no change, skip to avoid loops
                } else {
                    val isForcedOffline = prefs.getBoolean("forceWatchLocalMode", false)
                    val finalOfflineState = isForcedOffline || watchLocalMode
                    val finalMapEnabled = standaloneMode || mapEnabled

                    prefs.edit()
                        .putBoolean("mapEnabled", mapEnabled)
                        .putBoolean("watchLocalMode", watchLocalMode)
                        .putBoolean("disconnectFromPhone", standaloneMode)
                        .putBoolean("pref_wear_os_auto_download_route_maps", autoDownload)
                        .putString("mapDownloadMode", mapDownloadMode)
                        .putString("pref_wear_os_backend", backend)
                        .putInt("poiCategoriesMask", poiMask)
                        .putBoolean("pref_wear_os_3d", is3dEnabled)
                        .putBoolean("pref_wear_os_3d_buildings", is3dBuildingsEnabled)
                        .putBoolean("pref_wear_os_auto_zoom", isAutoZoomEnabled)
                        .putInt("pref_wear_os_munits", measurementUnits)
                        .putString("pref_wear_os_map_style", mapStyle)
                        .putBoolean("pref_wear_os_avoid_tolls", avoidTolls)
                        .putBoolean("pref_wear_os_avoid_motorways", avoidMotorways)
                        .putBoolean("pref_wear_os_avoid_ferries", avoidFerries)
                        .putBoolean("pref_wear_os_avoid_unpaved", avoidUnpaved)
                        .putBoolean("pref_wear_os_transit", transitEnabled)
                        .putBoolean("pref_wear_os_biking", bikingEnabled)
                        .putBoolean("pref_wear_os_hiking", hikingEnabled)
                        .putBoolean("pref_wear_os_isolines", isolinesEnabled)
                        .putString("locationSource", locationSource)
                        .apply()
                        
                    // Notify UI that this update came from remote
                    val intent = Intent("app.organicmaps.wear.SETTINGS_CHANGED")
                    intent.putExtra("source", "remote")
                    sendBroadcast(intent)

                    if (oldBackend != backend || oldStandaloneMode != standaloneMode) {
                        WearCommandService.initBackend(this)
                    }

                    // Apply native settings immediately
                    try {
                        System.loadLibrary("organicmaps")
                        app.organicmaps.sdk.Framework.nativeSet3dMode(is3dEnabled, is3dBuildingsEnabled)
                        app.organicmaps.sdk.Framework.nativeSetAutoZoomEnabled(isAutoZoomEnabled)
                        
                        if (avoidTolls) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Toll) else app.organicmaps.sdk.routing.RoutingOptions.removeOption(app.organicmaps.sdk.settings.RoadType.Toll)
                        if (avoidMotorways) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Motorway) else app.organicmaps.sdk.routing.RoutingOptions.removeOption(app.organicmaps.sdk.settings.RoadType.Motorway)
                        if (avoidFerries) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Ferry) else app.organicmaps.sdk.routing.RoutingOptions.removeOption(app.organicmaps.sdk.settings.RoadType.Ferry)
                        if (avoidUnpaved) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Dirty) else app.organicmaps.sdk.routing.RoutingOptions.removeOption(app.organicmaps.sdk.settings.RoadType.Dirty)
                        
                        app.organicmaps.sdk.Framework.nativeSetTransitSchemeEnabled(transitEnabled)
                        app.organicmaps.sdk.Framework.nativeSetCyclingLayerEnabled(bikingEnabled)
                        app.organicmaps.sdk.Framework.nativeSetHikingLayerEnabled(hikingEnabled)
                        app.organicmaps.sdk.Framework.nativeSetIsolinesLayerEnabled(isolinesEnabled)
                    } catch (_: Throwable) {}

                    NavigationStateHolder.update(currentState.copy(
                        mapEnabled = finalMapEnabled,
                        watchLocalMode = finalOfflineState,
                        standaloneMode = standaloneMode,
                        poiCategoriesMask = poiMask,
                        mapDownloadMode = mapDownloadMode,
                        autoDownloadRouteMaps = autoDownload,
                        backend = backend,
                        measurementUnits = measurementUnits,
                        mapStyle = mapStyle,
                        avoidTolls = avoidTolls,
                        avoidMotorways = avoidMotorways,
                        avoidFerries = avoidFerries,
                        avoidUnpaved = avoidUnpaved,
                        locationSource = locationSource,
                        isTrackRecording = isTrackRecording,
                        trackRecordingStartTime = recordingStartTime,
                        lastSettingsInteractionTime = timestamp // Sync local interaction clock
                    ))
                }
            }
            5 -> { // MSG_TYPE_MAP_DOWNLOAD
                val countryId = String(data, StandardCharsets.UTF_8)
                val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                
                // Respect map sync mode
                val mapEnabled = prefs.getBoolean("mapEnabled", false)
                val mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
                if (!mapEnabled || mapDownloadMode != "DIRECT_DOWNLOAD") {
                    Log.d(TAG, "Ignoring map push/download request due to sync mode: $mapDownloadMode")
                    return
                }

                prefs.edit().putBoolean("forceWatchLocalMode", true).apply()
                
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(openMapManager = true, watchLocalMode = true))
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val wearApp = applicationContext as app.organicmaps.wear.WearApplication
                        wearApp.waitForInitializationBlocking()
                        
                        // Check if already downloaded or in progress to prevent native registration crash
                        val status = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(countryId)
                        if (status != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE && 
                            status != app.organicmaps.sdk.downloader.CountryItem.STATUS_PROGRESS && 
                            status != app.organicmaps.sdk.downloader.CountryItem.STATUS_ENQUEUED &&
                            status != app.organicmaps.sdk.downloader.CountryItem.STATUS_APPLYING) {
                            app.organicmaps.sdk.downloader.MapManager.startDownload(countryId)
                        }
                        
                        val worldStatus = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World")
                        if (worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE && 
                            worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_PROGRESS && 
                            worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_ENQUEUED &&
                            worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_APPLYING) {
                            app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
                launchOmaps()
            }
            8 -> { // MSG_TYPE_TRACK_RECORDING
                val isRecording = buffer.get().toInt() == 1
                val startTime = if (buffer.remaining() >= 8) buffer.long else 0L
                Log.d(TAG, "Recording status updated from phone (BT): isRecording=$isRecording, startTime=$startTime")
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                    isTrackRecording = isRecording,
                    trackRecordingStartTime = startTime
                ))
            }
            9 -> { // MSG_TYPE_BOOKMARKS
                val count = buffer.int
                val currentState = NavigationStateHolder.state.value
                val categories = mutableListOf<BookmarkCategoryItem>()
                repeat(count) {
                    val id = buffer.long
                    val nameLen = buffer.int
                    val nameBytes = ByteArray(nameLen)
                    buffer.get(nameBytes)
                    val name = String(nameBytes, StandardCharsets.UTF_8)
                    val isVisible = buffer.get().toInt() == 1
                    val bmkCount = buffer.int
                    val trkCount = buffer.int
                    val oldCat = currentState.bookmarkCategories.find { it.id == id }
                    categories.add(BookmarkCategoryItem(id, name, isVisible, bmkCount, trkCount, oldCat?.isSyncing ?: false))
                }
                NavigationStateHolder.update(currentState.copy(
                    bookmarkCategories = categories
                ))
            }
            7 -> { // MSG_TYPE_MAP_PROGRESS
                val countryLen = if (buffer.remaining() >= 4) buffer.int else 0
                if (countryLen > 0 && buffer.remaining() >= countryLen + 4) {
                    val countryBytes = ByteArray(countryLen)
                    buffer.get(countryBytes)
                    val countryId = String(countryBytes, StandardCharsets.UTF_8)
                    val progress = buffer.int
                    Log.d(TAG, "Received map progress via Bluetooth: $countryId -> $progress%")
                    if (countryId == WearMapDownloader.currentMap.value) {
                        WearMapDownloader.setStreamingProgress(progress / 100f)
                    }
                }
            }
            10 -> { // MSG_TYPE_COMMAND
                val pathLen = if (buffer.remaining() >= 4) buffer.int else 0
                if (pathLen > 0 && buffer.remaining() >= pathLen) {
                    val pathBytes = ByteArray(pathLen)
                    buffer.get(pathBytes)
                    val path = String(pathBytes, StandardCharsets.UTF_8)
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    WearMessageRouter.onMessageReceived(this, path, data, "bluetooth_phone")
                }
            }
            11 -> { // MSG_TYPE_MAP_CHUNK
                if (buffer.remaining() < 5) return
                val mapIdLen = buffer.int
                val mapIdBytes = ByteArray(mapIdLen)
                buffer.get(mapIdBytes)
                val mapId = String(mapIdBytes, StandardCharsets.UTF_8)
                val isLast = buffer.get().toInt() == 1
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                
                saveMapChunk(mapId, chunk, isLast)
            }
            12 -> { // MSG_TYPE_BOOKMARK_FILE
                if (buffer.remaining() < 10) return
                val catId = buffer.long
                val isLast = buffer.get().toInt() == 1
                val fileNameLen = buffer.get().toInt()
                if (buffer.remaining() < fileNameLen) return
                val fileNameBytes = ByteArray(fileNameLen)
                buffer.get(fileNameBytes)
                val fileName = String(fileNameBytes, StandardCharsets.UTF_8)
                
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                saveBookmarkChunk(catId, fileName, chunk, isLast)
            }
            14 -> { // MSG_TYPE_VIRTUAL_MWM_DATA
                val nameLen = buffer.int
                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                val mwmName = String(nameBytes, StandardCharsets.UTF_8)
                val offset = buffer.long
                val mwmData = ByteArray(buffer.remaining())
                buffer.get(mwmData)
                
                VirtualMwmManager.onBytesReceived(mwmName, offset, mwmData)
            }
            15 -> { // MSG_TYPE_VIRTUAL_MWM_MOUNT
                val nameLen = buffer.int
                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                val mwmName = String(nameBytes, StandardCharsets.UTF_8)
                val totalSize = buffer.long
                VirtualMwmManager.mount(this, mwmName, totalSize)
            }
        }
    }

    private fun saveBookmarkChunk(catId: Long, fileName: String, data: ByteArray, isLast: Boolean) {
        try {
            val fos = bookmarkOutputStreams.getOrPut(catId) {
                val file = java.io.File(cacheDir, fileName + ".tmp")
                WearMapDownloader.setStreamingMap("Bookmarks: $catId")
                java.io.FileOutputStream(file)
            }
            fos.write(data)
            if (isLast) {
                fos.close()
                bookmarkOutputStreams.remove(catId)
                val tmpFile = java.io.File(cacheDir, fileName + ".tmp")
                val finalFile = java.io.File(cacheDir, fileName)
                tmpFile.renameTo(finalFile)
                Log.d(TAG, "Successfully received bookmark file: $fileName")
                WearMapDownloader.onDownloadCompleted()
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
                        // Fix duplicates: check if a category with the same name already exists
                        val catName = fileName.removeSuffix(".kml").removeSuffix(".kmz").removeSuffix(".gpx")
                        val existing = manager.categories.find { it.name.equals(catName, ignoreCase = true) }
                        if (existing != null) {
                            Log.d(TAG, "Deleting existing category '$catName' before importing update")
                            manager.deleteCategory(existing.id)
                        }

                        manager.loadBookmarksFile(finalFile.absolutePath, true)
                        android.widget.Toast.makeText(this, "Bookmarks synchronized", android.widget.Toast.LENGTH_SHORT).show()
                        // Trigger a local refresh of bookmark categories to reflect imported data
                        NavigationStateHolder.update { current ->
                             val updatedCats = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories().map { cat ->
                                 val oldCat = current.bookmarkCategories.find { it.id == cat.id }
                                 BookmarkCategoryItem(
                                     cat.id, cat.name, cat.isVisible, cat.bookmarksCount, cat.tracksCount,
                                     isSyncing = if (cat.id == catId) false else (oldCat?.isSyncing ?: false)
                                 )
                             }
                             current.copy(bookmarkCategories = updatedCats)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load imported bookmarks", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bookmark chunk: ${e.message}")
            bookmarkOutputStreams.remove(catId)?.close()
        }
    }

    private fun saveMapChunk(mapId: String, data: ByteArray, isLast: Boolean) {
        try {
            val wearApp = application as WearApplication
            wearApp.waitForInitializationBlocking()
            
            val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(this)
            val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
            val versionedPath = java.io.File(storagePath, dataVersion.toString())
            if (!versionedPath.exists()) versionedPath.mkdirs()

            val fos = mapOutputStreams.getOrPut(mapId) {
                val file = java.io.File(versionedPath, "$mapId.mwm.tmp")
                WearMapDownloader.setStreamingMap(mapId)
                java.io.FileOutputStream(file)
            }
            fos.write(data)
            if (isLast) {
                fos.close()
                mapOutputStreams.remove(mapId)
                val tmpFile = java.io.File(versionedPath, "$mapId.mwm.tmp")
                val finalFile = java.io.File(versionedPath, "$mapId.mwm")
                tmpFile.renameTo(finalFile)
                WearMapDownloader.onDownloadCompleted()
                Log.d(TAG, "Successfully received map via Bluetooth: $mapId")
                
                // Critical: reload world maps to register the new one
                try {
                    app.organicmaps.sdk.Framework.nativeReloadWorldMaps()
                } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save map chunk: ${e.message}")
            mapOutputStreams.remove(mapId)?.close()
        }
    }

    private fun launchOmaps() {
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
