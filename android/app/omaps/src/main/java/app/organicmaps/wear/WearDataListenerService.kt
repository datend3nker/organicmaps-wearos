package app.organicmaps.wear

import android.content.Intent
import app.organicmaps.wear.presentation.Omaps
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val PATH_START_NAVIGATION = "/navigation/start"

    private fun shouldAutoDownloadMaps(): Boolean {
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val mapDownloadMode = prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY") ?: "BLUETOOTH_ONLY"
        return mapEnabled && mapDownloadMode != "BLUETOOTH_ONLY"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")
        if (messageEvent.path == PATH_START_NAVIGATION) {
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(isActive = true))
            launchOmaps()
        } else if (messageEvent.path == "/map/download/request") {
            val countryId = String(messageEvent.data)
            Log.d(TAG, "Phone requested map download: $countryId")
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(openMapManager = true))

            if (shouldAutoDownloadMaps()) {
                try {
                    System.loadLibrary("organicmaps")
                    val wearApp = applicationContext as app.organicmaps.wear.WearApplication
                    wearApp.waitForInitializationBlocking()
                    app.organicmaps.sdk.downloader.MapManager.startDownload(countryId)
                    app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            } else {
                Log.d(TAG, "Skipping auto-download from phone request due to watch map settings")
            }
            launchOmaps() // Show UI so user sees progress
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
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
                            try {
                                val wearApp = applicationContext as app.organicmaps.wear.WearApplication
                                wearApp.waitForInitializationBlocking()
                                for (mapId in missingMaps) {
                                    app.organicmaps.sdk.downloader.MapManager.startDownload(mapId)
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                          } else if (autoDownload && missingMaps.isNotEmpty()) {
                              Log.d(TAG, "Missing maps received but auto-download blocked by map settings")
                        }
                        
                        val newState = currentState.copy(
                            distToTurn = dataMap.getString("distToTurn") ?: currentState.distToTurn,
                            nextStreet = dataMap.getString("nextStreet") ?: currentState.nextStreet,
                            carDirection = dataMap.getInt("carDirection", currentState.carDirection),
                            pedestrianDirection = dataMap.getInt("pedestrianDirection", currentState.pedestrianDirection),
                            isActive = dataMap.getBoolean("active", currentState.isActive),
                            speedMps = dataMap.getDouble("speedMps", currentState.speedMps),
                            speedLimitMps = dataMap.getDouble("speedLimitMps", currentState.speedLimitMps),
                            distToTarget = dataMap.getString("distToTarget") ?: currentState.distToTarget,
                            eta = dataMap.getInt("eta", currentState.eta),
                            lat = dataMap.getDouble("lat", currentState.lat),
                            lon = dataMap.getDouble("lon", currentState.lon)
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
                        val mapDownloadMode = dataMap.getString("mapDownloadMode", "BLUETOOTH_ONLY")
                        prefs.edit()
                            .putBoolean("mapEnabled", mapEnabled)
                            .putString("mapDownloadMode", mapDownloadMode)
                            .apply()
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                            mapEnabled = mapEnabled
                        ))
                        Log.d(TAG, "Preferences updated: mapEnabled=$mapEnabled, mapDownloadMode=$mapDownloadMode")
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
