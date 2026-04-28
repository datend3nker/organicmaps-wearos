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

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPongTime = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        checkPhoneConnection()
        startPingLoop()
    }

    private fun startPingLoop() {
        scope.launch {
            while (true) {
                WearCommandService.sendPing(this@WearDataListenerService)
                delay(10000) // Ping every 10 seconds
                
                // If no pong for 25 seconds, mark as disconnected
                if (System.currentTimeMillis() - lastPongTime > 25000) {
                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = false))
                }
            }
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
                if (nodes.isEmpty()) {
                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = false))
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
        val mapDownloadMode = prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY") ?: "BLUETOOTH_ONLY"
        return mapEnabled && mapDownloadMode != "BLUETOOTH_ONLY"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")
        if (messageEvent.path == PATH_PONG) {
            lastPongTime = System.currentTimeMillis()
            NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = true))
            return
        }
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = true))
        if (messageEvent.path == PATH_START_NAVIGATION) {
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(isActive = true))
            launchOmaps()
        } else if (messageEvent.path == PATH_MAP_DOWNLOAD_REQUEST) {
            val countryId = String(messageEvent.data)
            Log.d(TAG, "Phone explicitly requested map download: $countryId")
            
            // Force offline maps to true if the user pushes a map from phone
            val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("forceWatchOfflineMaps", true).apply()
            
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(openMapManager = true, offlineMapsEnabled = true))

            val appCtx = applicationContext
            val countryIdToDownload = countryId
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    System.loadLibrary("organicmaps")
                    val wearApp = appCtx as app.organicmaps.wear.WearApplication
                    wearApp.waitForInitializationBlocking()
                    app.organicmaps.sdk.downloader.MapManager.startDownload(countryIdToDownload)
                    app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            launchOmaps() // Show UI so user sees progress
        } else if (messageEvent.path == PATH_MAP_TILE_RESPONSE) {
            val buffer = ByteBuffer.wrap(messageEvent.data)
            if (buffer.remaining() < 8) { // 1 long (requestId)
                Log.w(TAG, "Received malformed map tile response")
                return
            }

            val requestId = buffer.long
            val features = ByteArray(buffer.remaining())
            buffer.get(features)
            MapTileStateHolder.update(requestId, features)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        if (dataEvents.count > 0) {
            NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = true))
        }
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
                                    for (mapId in mapsToDownload) {
                                        app.organicmaps.sdk.downloader.MapManager.startDownload(mapId)
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
                            isActive = dataMap.getBoolean("active", currentState.isActive),
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
                        val mapEnabled = dataMap.getBoolean("mapEnabled", false)
                        val offlineMapsEnabled = dataMap.getBoolean("offlineMapsEnabled", false)
                        val mapDownloadMode = dataMap.getString("mapDownloadMode", "BLUETOOTH_ONLY")
                        
                        // Check if watch user explicitly overrode this
                        val isForcedOffline = prefs.getBoolean("forceWatchOfflineMaps", false)
                        val finalOfflineState = isForcedOffline || offlineMapsEnabled
                        
                        prefs.edit()
                            .putBoolean("mapEnabled", mapEnabled)
                            .putBoolean("offlineMapsEnabled", offlineMapsEnabled) // original phone state
                            .putString("mapDownloadMode", mapDownloadMode)
                            .apply()
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                            mapEnabled = mapEnabled,
                            offlineMapsEnabled = finalOfflineState // Apply forced state if set
                        ))
                        Log.d(TAG, "Preferences updated: mapEnabled=$mapEnabled, phoneOfflineMaps=$offlineMapsEnabled, finalUsedState=$finalOfflineState")
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
