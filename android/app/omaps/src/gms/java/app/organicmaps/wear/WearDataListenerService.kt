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
        val mapDownloadMode = prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY") ?: "BLUETOOTH_ONLY"
        return mapEnabled && mapDownloadMode != "BLUETOOTH_ONLY"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            isPhoneConnected = true,
            lastMessageTimestamp = System.currentTimeMillis()
        ))
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
            
            // Force offline maps to true if the user pushes a map from phone
            val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
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
                    app.organicmaps.sdk.downloader.MapManager.startDownload(countryIdToDownload)
                    app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            launchOmaps() // Show UI so user sees progress
        } else if (messageEvent.path == PATH_MAP_TILE_RESPONSE) {
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
            isPhoneConnected = true,
            lastMessageTimestamp = System.currentTimeMillis()
        ))
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
                            exitNum = dataMap.getInt("exitNum", currentState.exitNum),
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
                        val watchLocalMode = dataMap.getBoolean("watchLocalMode", false)
                        val standaloneMode = dataMap.getBoolean("standaloneMode", false)
                        val mapDownloadMode = dataMap.getString("mapDownloadMode", "BLUETOOTH_ONLY")
                        val backend = dataMap.getString("backend", "GMS")
                        
                        // Standalone mode is a manual link cut or forced from phone
                        val isForcedOffline = prefs.getBoolean("forceWatchLocalMode", false)
                        val finalOfflineState = standaloneMode || isForcedOffline || watchLocalMode
                        val finalMapEnabled = standaloneMode || mapEnabled
                        
                        prefs.edit()
                            .putBoolean("mapEnabled", mapEnabled)
                            .putBoolean("watchLocalMode", watchLocalMode)
                            .putBoolean("disconnectFromPhone", standaloneMode)
                            .putString("mapDownloadMode", mapDownloadMode)
                            .putString("pref_wear_os_backend", backend)
                            .apply()

                        // Sync backend implementation
                        WearCommandService.initBackend(this)
                        if (backend == "BLUETOOTH") {
                            startService(Intent(this, BluetoothWearDataListenerService::class.java))
                        } else if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
                            stopService(Intent(this, BluetoothWearDataListenerService::class.java))
                        }

                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                            mapEnabled = finalMapEnabled,
                            watchLocalMode = finalOfflineState, // Apply forced state if set
                            standaloneMode = standaloneMode
                        ))
                        Log.d(TAG, "Preferences updated: mapEnabled=$finalMapEnabled, phoneWatchLocalMode=$watchLocalMode, finalUsedState=$finalOfflineState, standalone=$standaloneMode, backend=$backend")
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
