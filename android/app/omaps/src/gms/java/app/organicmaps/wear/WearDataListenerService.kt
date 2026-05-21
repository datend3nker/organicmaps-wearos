package app.organicmaps.wear

import android.content.Intent
import app.organicmaps.wear.presentation.Omaps
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        checkPhoneConnection()
        scope.launch {
            delay(2000)
            checkPhoneConnection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onPeerConnected(peer: Node) {
        checkPhoneConnection()
    }

    override fun onPeerDisconnected(peer: Node) {
        checkPhoneConnection()
    }

    private fun checkPhoneConnection() {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@WearDataListenerService).connectedNodes.await()
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = nodes.isNotEmpty()))
                if (nodes.isNotEmpty()) {
                    WearCommandService.requestPreferences(this@WearDataListenerService)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check nodes", e)
            }
        }
    }
    private val PATH_START_NAVIGATION = "/navigation/start"
    private val PATH_MAP_DOWNLOAD_REQUEST = "/map/download/request"
    private val PATH_MAP_TILE_RESPONSE = "/map/tile/response"
    private val PATH_PONG = "/pong"

    private fun shouldAutoDownloadMaps(): Boolean {
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
        return mapEnabled && mapDownloadMode == "DIRECT_DOWNLOAD"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            isPhoneConnected = true
        ))
        NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
        if (messageEvent.path == PATH_PONG) {
            (applicationContext as WearApplication).onPongReceived()
            return
        }
        (applicationContext as WearApplication).onPongReceived()
        if (messageEvent.path == PATH_START_NAVIGATION) {
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(isActive = true))
            launchOmaps()
        } else if (messageEvent.path == PATH_MAP_DOWNLOAD_REQUEST) {
            val countryId = String(messageEvent.data)
            Log.d(TAG, "Phone explicitly requested map download: $countryId")
            
            // Respect map sync mode
            val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
            val mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
            if (mapDownloadMode != "DIRECT_DOWNLOAD") {
                Log.d(TAG, "Ignoring map download request from phone due to sync mode: $mapDownloadMode")
                return
            }

            // Force offline maps to true if the user pushes a map from phone
            prefs.edit().putBoolean("forceWatchLocalMode", true).apply()
            
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(openMapManager = true, watchLocalMode = true))

            val appCtx = applicationContext
            val countryIdToDownload = countryId
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    System.loadLibrary("organicmaps")
                    val wearApp = appCtx as app.organicmaps.wear.WearApplication
                    wearApp.waitForInitializationBlocking()
                    
                    val status = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(countryIdToDownload)
                    if (status != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE && 
                        status != app.organicmaps.sdk.downloader.CountryItem.STATUS_PROGRESS && 
                        status != app.organicmaps.sdk.downloader.CountryItem.STATUS_ENQUEUED &&
                        status != app.organicmaps.sdk.downloader.CountryItem.STATUS_APPLYING) {
                        app.organicmaps.sdk.downloader.MapManager.startDownload(countryIdToDownload)
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
            launchOmaps() // Show UI so user sees progress
        }
else if (messageEvent.path == PATH_MAP_TILE_RESPONSE) {
            val buffer = ByteBuffer.wrap(messageEvent.data)
            if (buffer.remaining() < 9) { // 1 long (requestId) + 1 byte (compressed flag)
                Log.w(TAG, "Received malformed map tile response")
                return
            }

            val requestId = buffer.long
            val compressed = buffer.get().toInt() == 1
            var features = ByteArray(buffer.remaining())
            buffer.get(features)

            if (compressed) {
                try {
                    features = GzipUtils.decompress(features)
                } catch (e: Exception) {
                    Log.e(TAG, "Decompression failed", e)
                    return
                }
            }
            MapTileStateHolder.update(requestId, features)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            isPhoneConnected = true
        ))
        NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                when (uri.path) {
                    "/navigation/status" -> {
                        val currentState = NavigationStateHolder.state.value
                        
                        val missingMaps = dataMap.getStringArrayList("missingMaps") ?: emptyList<String>()
                        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                        val autoDownload = prefs.getBoolean("autoDownloadRouteMaps", true)
                          val autoDownloadAllowedByMapSettings = shouldAutoDownloadMaps()
                        
                          if (autoDownload && autoDownloadAllowedByMapSettings && missingMaps.isNotEmpty()) {
                            Log.d(TAG, "Auto-downloading missing maps for route: $missingMaps")
                            val appCtx = applicationContext
                            val mapsToDownload = missingMaps.toList()
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    val wearApp = appCtx as app.organicmaps.wear.WearApplication
                                    wearApp.waitForInitializationBlocking()
                                    val distinctMaps = mapsToDownload.distinct()
                                    for (mapId in distinctMaps) {
                                        val status = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(mapId)
                                        if (status != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE && 
                                            status != app.organicmaps.sdk.downloader.CountryItem.STATUS_PROGRESS && 
                                            status != app.organicmaps.sdk.downloader.CountryItem.STATUS_ENQUEUED &&
                                            status != app.organicmaps.sdk.downloader.CountryItem.STATUS_APPLYING) {
                                            app.organicmaps.sdk.downloader.MapManager.startDownload(mapId)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                }
                            }
                          } else if (autoDownload && missingMaps.isNotEmpty()) {
                              Log.d(TAG, "Missing maps received but auto-download blocked by map settings")
                        }
                        
                        val routeLats = dataMap.getFloatArray("routeLats") ?: floatArrayOf()
                        val routeLons = dataMap.getFloatArray("routeLons") ?: floatArrayOf()
                        val routePoints = if (routeLats.size == routeLons.size && routeLats.isNotEmpty()) {
                            routeLats.zip(routeLons).map { it.first.toDouble() to it.second.toDouble() }
                        } else {
                            currentState.routePoints
                        }

                        val newState = currentState.copy(
                            distToTurn = dataMap.getString("distToTurn") ?: currentState.distToTurn,
                            nextStreet = dataMap.getString("nextStreet") ?: currentState.nextStreet,
                            carDirection = dataMap.getInt("carDirection", currentState.carDirection),
                            pedestrianDirection = dataMap.getInt("pedestrianDirection", currentState.pedestrianDirection),
                            exitNum = dataMap.getInt("exitNum", currentState.exitNum),
                            isActive = dataMap.getBoolean("active", currentState.isActive),
                            isNavigating = dataMap.getBoolean("active", currentState.isNavigating),
                            speedMps = dataMap.getDouble("speedMps", currentState.speedMps),
                            speedLimitMps = dataMap.getDouble("speedLimitMps", currentState.speedLimitMps),
                            bearing = dataMap.getFloat("bearing", currentState.bearing),
                            distToTarget = dataMap.getString("distToTarget") ?: currentState.distToTarget,
                            eta = dataMap.getInt("eta", currentState.eta),
                            lat = dataMap.getDouble("lat", currentState.lat),
                            lon = dataMap.getDouble("lon", currentState.lon),
                            turnLat = dataMap.getDouble("turnLat", currentState.turnLat),
                            turnLon = dataMap.getDouble("turnLon", currentState.turnLon),
                            distToTurnMeters = dataMap.getDouble("distToTurnMeters", currentState.distToTurnMeters),
                            routerType = dataMap.getInt("routerType", currentState.routerType),
                            routePoints = routePoints
                        )

                        // Pass location to native core only if NOT in standalone mode
                        // This prevents location jitter where watch and phone fight for control
                        if (!newState.standaloneMode) {
                            try {
                                System.loadLibrary("organicmaps")
                                app.organicmaps.sdk.location.LocationState.nativeLocationUpdated(
                                    System.currentTimeMillis(),
                                    newState.lat, newState.lon,
                                    5.0f, // hAcc
                                    0.0, // alt
                                    newState.speedMps.toFloat(),
                                    newState.bearing
                                )
                            } catch (_: Throwable) {}
                        }

                        NavigationStateHolder.update(newState)
                        if (newState.isActive && !currentState.isActive) launchOmaps()
                    }
                    "/search/results" -> {
                        val resultMaps = dataMap.getDataMapArrayList("results") ?: emptyList()
                        val isSearching = dataMap.getBoolean("isSearching", false)
                        val results = resultMaps.map {
                            SearchResultItem(
                                name = it.getString("name") ?: "",
                                description = it.getString("description") ?: "",
                                lat = it.getDouble("lat", 0.0),
                                lon = it.getDouble("lon", 0.0),
                                type = it.getInt("type", 2)
                            )
                        }
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                            searchResults = results,
                            isSearching = isSearching
                        ))
                    }
                    "/search/history" -> {
                        val history = dataMap.getStringArrayList("history") ?: emptyList()
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(searchHistory = history))
                    }
                    "/preferences" -> {
                        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                        val timestamp = dataMap.getLong("timestamp", 0)
                        val currentState = NavigationStateHolder.state.value

                        // TIMESTAMP-BASED WINNING LOGIC
                        if (timestamp > 0 && timestamp < currentState.lastSettingsInteractionTime) {
                            Log.d(TAG, "Ignoring stale remote preferences. Remote: $timestamp, LocalInteraction: ${currentState.lastSettingsInteractionTime}")
                            return
                        }

                        val mapEnabled = dataMap.getBoolean("mapEnabled", false)
                        val watchLocalMode = dataMap.getBoolean("watchLocalMode", false)
                        val standaloneMode = dataMap.getBoolean("standaloneMode", false)
                        val autoDownload = dataMap.getBoolean("autoDownloadRouteMaps", true)
                        val mapDownloadMode = dataMap.getString("mapDownloadMode", "BLUETOOTH_ONLY")
                        val backend = dataMap.getString("backend", "GMS")
                        val poiMask = dataMap.getInt("poiCategoriesMask", 0x3F)
                        
                        val is3dEnabled = dataMap.getBoolean("is3dEnabled", true)
                        val is3dBuildingsEnabled = dataMap.getBoolean("is3dBuildingsEnabled", true)
                        val isAutoZoomEnabled = dataMap.getBoolean("isAutoZoomEnabled", true)
                        val mUnits = dataMap.getInt("measurementUnits", 0)
                        val mapStyle = dataMap.getString("mapStyle", "default")
                        
                        val avoidTolls = dataMap.getBoolean("avoidTolls", false)
                        val avoidMotorways = dataMap.getBoolean("avoidMotorways", false)
                        val avoidFerries = dataMap.getBoolean("avoidFerries", false)
                        val avoidUnpaved = dataMap.getBoolean("avoidUnpaved", false)

                        // Check if actually changed to avoid unnecessary updates
                        val oldMapEnabled = prefs.getBoolean("mapEnabled", false)
                        val oldWatchLocalMode = prefs.getBoolean("watchLocalMode", false)
                        val oldStandaloneMode = prefs.getBoolean("disconnectFromPhone", false)
                        val oldAutoDownload = prefs.getBoolean("autoDownloadRouteMaps", true)
                        val oldDownloadMode = prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY")
                        val oldBackend = prefs.getString("pref_wear_os_backend", "GMS")
                        val oldPoiMask = prefs.getInt("poiCategoriesMask", 0x3F)
                        
                        val oldIs3d = prefs.getBoolean("pref_3d", true)
                        val oldIs3dBld = prefs.getBoolean("pref_3d_buildings", true)
                        val oldAutoZoom = prefs.getBoolean("pref_auto_zoom", true)
                        val oldUnits = prefs.getInt("pref_munits", 0)
                        val oldStyle = prefs.getString("pref_map_style", "default")
                        
                        val oldAvoidTolls = prefs.getBoolean("avoid_tolls", false)
                        val oldAvoidMotorways = prefs.getBoolean("avoid_motorways", false)
                        val oldAvoidFerries = prefs.getBoolean("avoid_ferries", false)
                        val oldAvoidUnpaved = prefs.getBoolean("avoid_dirty_roads", false)

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
                            oldUnits == mUnits &&
                            oldStyle == mapStyle &&
                            oldAvoidTolls == avoidTolls &&
                            oldAvoidMotorways == avoidMotorways &&
                            oldAvoidFerries == avoidFerries &&
                            oldAvoidUnpaved == avoidUnpaved &&
                            timestamp <= prefs.getLong("last_sync_timestamp", 0)) {
                            return
                        }

                        val isForcedOffline = prefs.getBoolean("forceWatchLocalMode", false)
                        val finalOfflineState = isForcedOffline || watchLocalMode
                        val finalMapEnabled = standaloneMode || mapEnabled
                        
                        prefs.edit()
                            .putLong("last_sync_timestamp", timestamp)
                            .putBoolean("mapEnabled", mapEnabled)
                            .putBoolean("watchLocalMode", watchLocalMode)
                            .putBoolean("disconnectFromPhone", standaloneMode)
                            .putBoolean("autoDownloadRouteMaps", autoDownload)
                            .putString("mapDownloadMode", mapDownloadMode)
                            .putString("pref_wear_os_backend", backend)
                            .putInt("poiCategoriesMask", poiMask)
                            .putBoolean("pref_3d", is3dEnabled)
                            .putBoolean("pref_3d_buildings", is3dBuildingsEnabled)
                            .putBoolean("pref_auto_zoom", isAutoZoomEnabled)
                            .putInt("pref_munits", mUnits)
                            .putString("pref_map_style", mapStyle)
                            .putBoolean("avoid_tolls", avoidTolls)
                            .putBoolean("avoid_motorways", avoidMotorways)
                            .putBoolean("avoid_ferries", avoidFerries)
                            .putBoolean("avoid_dirty_roads", avoidUnpaved)
                            .apply()

                        // Sync backend implementation
                        WearCommandService.initBackend(this)
                        if (backend == "BLUETOOTH") {
                            startService(Intent(this, BluetoothWearDataListenerService::class.java))
                        } else if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
                            stopService(Intent(this, BluetoothWearDataListenerService::class.java))
                        }

                        // Apply native settings immediately
                        try {
                            System.loadLibrary("organicmaps")
                            app.organicmaps.sdk.Framework.nativeSet3dMode(is3dEnabled, is3dBuildingsEnabled)
                            app.organicmaps.sdk.Framework.nativeSetAutoZoomEnabled(isAutoZoomEnabled)
                            
                            if (avoidTolls) RoutingOptions.addOption(RoadType.Toll) else RoutingOptions.removeOption(RoadType.Toll)
                            if (avoidMotorways) RoutingOptions.addOption(RoadType.Motorway) else RoutingOptions.removeOption(RoadType.Motorway)
                            if (avoidFerries) RoutingOptions.addOption(RoadType.Ferry) else RoutingOptions.removeOption(RoadType.Ferry)
                            if (avoidUnpaved) RoutingOptions.addOption(RoadType.Dirty) else RoutingOptions.removeOption(RoadType.Dirty)
                        } catch (_: Throwable) {}

                        NavigationStateHolder.update(currentState.copy(
                            mapEnabled = finalMapEnabled,
                            watchLocalMode = finalOfflineState,
                            standaloneMode = standaloneMode,
                            autoDownloadRouteMaps = autoDownload,
                            mapDownloadMode = mapDownloadMode,
                            backend = backend,
                            poiCategoriesMask = poiMask,
                            is3dEnabled = is3dEnabled,
                            is3dBuildingsEnabled = is3dBuildingsEnabled,
                            isAutoZoomEnabled = isAutoZoomEnabled,
                            measurementUnits = mUnits,
                            mapStyle = mapStyle,
                            avoidTolls = avoidTolls,
                            avoidMotorways = avoidMotorways,
                            avoidFerries = avoidFerries,
                            avoidUnpaved = avoidUnpaved,
                            lastSettingsInteractionTime = timestamp
                        ))
                        Log.d(TAG, "Preferences updated: mapEnabled=$finalMapEnabled, watchLocal=$watchLocalMode, autoDownload=$autoDownload, standalone=$standaloneMode, backend=$backend")
                    }
                    "/map/download/progress" -> {
                        val countryId = dataMap.getString("countryId") ?: return
                        val progress = dataMap.getInt("progress", 0)
                        Log.d(TAG, "Received map progress from phone: $countryId -> $progress%")
                        // In a real app we might want to update some state that the MapManager can pick up
                        // For now we rely on the watch's own MapManager to update UI if it's also downloading
                        // or we could trigger a local download status refresh.
                    }
                }
            }
        }
    }

    private fun launchOmaps() {
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
