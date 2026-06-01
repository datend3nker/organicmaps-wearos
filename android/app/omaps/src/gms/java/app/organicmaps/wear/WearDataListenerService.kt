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
import java.nio.charset.StandardCharsets

import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bookmarkOutputStreams = mutableMapOf<Long, java.io.FileOutputStream>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DEBUG_GMS: Watch WearDataListenerService.onCreate()")
        checkPhoneConnection()
        scope.launch {
            delay(2000)
            if (!NavigationStateHolder.state.value.isPhoneConnected) {
                checkPhoneConnection()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DEBUG_GMS: Watch WearDataListenerService.onStartCommand()")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        Log.d(TAG, "DEBUG_GMS: Watch WearDataListenerService.onDestroy()")
        super.onDestroy()
        scope.cancel()
    }

    override fun onPeerConnected(peer: Node) {
        Log.d(TAG, "onPeerConnected: ${peer.displayName}")
        checkPhoneConnection()
    }

    override fun onPeerDisconnected(peer: Node) {
        Log.d(TAG, "onPeerDisconnected: ${peer.displayName}")
        checkPhoneConnection()
    }

    private fun checkPhoneConnection() {
        scope.launch {
            try {
                val capabilityInfo = Wearable.getCapabilityClient(this@WearDataListenerService)
                    .getCapability("organic_maps_phone_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                    .await()
                
                val nodes = capabilityInfo.nodes
                val connected = nodes.isNotEmpty()
                Log.d(TAG, "DEBUG_GMS: checkPhoneConnection: found ${nodes.size} nodes with phone app capability: ${nodes.map { it.displayName }}")
                
                if (connected) {
                    WearCommandService.requestPreferences(this@WearDataListenerService)
                    WearCommandService.requestBookmarks(this@WearDataListenerService)
                    WearCommandService.syncSearchHistory(this@WearDataListenerService)
                    
                    // Trigger Virtual MWM for World map if not present locally
                    if (app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World") != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
                        WearCommandService.requestMwmMetadata(this@WearDataListenerService, "World")
                    }
                } else {
                    // Fallback to simple node check if capability not found (legacy or misconfigured)
                    val allNodes = Wearable.getNodeClient(this@WearDataListenerService).connectedNodes.await()
                    Log.d(TAG, "DEBUG_GMS: checkPhoneConnection: found ${allNodes.size} total connected nodes: ${allNodes.map { it.displayName }}")
                    if (allNodes.isEmpty()) {
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                            isPhoneConnected = false
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check phone capability", e)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        WearMessageRouter.onMessageReceived(this, messageEvent.path, messageEvent.data, messageEvent.sourceNodeId)
    }

    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        if (channel.path.startsWith("/map/stream/data/")) {
            val mapId = channel.path.substringAfterLast("/")
            Log.d(TAG, "Receiving map stream for $mapId")
            
            val tempFile = java.io.File(cacheDir, "$mapId.mwm.tmp")
            val channelClient = Wearable.getChannelClient(this)
            
            // Show notification on watch
            WearMapDownloader.setStreamingMap(mapId)
            
            channelClient.receiveFile(channel, android.net.Uri.fromFile(tempFile), false)
                .addOnSuccessListener {
                    scope.launch {
                        try {
                            (application as WearApplication).waitForInitializationSuspend()
                            Log.d(TAG, "Map stream received for $mapId")
                            val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(this@WearDataListenerService)
                            val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
                            val versionedPath = java.io.File(storagePath, dataVersion.toString())
                            if (!versionedPath.exists()) versionedPath.mkdirs()
                            val finalFile = java.io.File(versionedPath, "$mapId.mwm")
                            tempFile.renameTo(finalFile)
                            WearMapDownloader.onDownloadCompleted()
                            
                            try {
                                app.organicmaps.sdk.Framework.nativeReloadWorldMaps()
                            } catch (_: Throwable) {}
                        } catch (e: Exception) {
                            Log.e(TAG, "Error finalizing map download after initialization", e)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to receive map stream for $mapId", e)
                }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "DEBUG_GMS: Watch onDataChanged: received ${dataEvents.count} events")
        if (dataEvents.count > 0) {
            (applicationContext as WearApplication).onActivityReceived()
        }
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            Log.d(TAG, "DEBUG_GMS: Watch onDataChanged path: ${uri.path} type: ${event.type} host: ${uri.host}")
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                when (uri.path) {
                    "/preferences" -> {
                        Log.d(TAG, "DEBUG_GMS: Received /preferences data item update")
                        handlePreferences(dataMap)
                    }
                    "/map/download/progress" -> {
                        val countryId = dataMap.getString("countryId") ?: return
                        val progress = dataMap.getInt("progress", 0)
                        if (countryId == WearMapDownloader.currentMap.value) {
                            WearMapDownloader.setStreamingProgress(progress / 100f)
                        }
                    }
                }
            }
        }
    }

    private fun handlePreferences(dataMap: com.google.android.gms.wearable.DataMap) {
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
        val mapDownloadMode = dataMap.getString("mapDownloadMode", "PHONE_SYNC")
        val backend = dataMap.getString("backend", "GMS")
        val poiMask = dataMap.getInt("poiCategoriesMask", 0x3F)
        val locationSource = dataMap.getString("locationSource", "AUTO")
        
        val is3dEnabled = dataMap.getBoolean("is3dEnabled", true)
        val is3dBuildingsEnabled = dataMap.getBoolean("is3dBuildingsEnabled", true)
        val isAutoZoomEnabled = dataMap.getBoolean("isAutoZoomEnabled", true)
        val mUnits = dataMap.getInt("measurementUnits", 0)
        val mapStyle = dataMap.getString("mapStyle", "default")
        
        val transitEnabled = dataMap.getBoolean("transitEnabled", false)
        val bikingEnabled = dataMap.getBoolean("bikingEnabled", false)
        val hikingEnabled = dataMap.getBoolean("hikingEnabled", false)
        val isolinesEnabled = dataMap.getBoolean("isolinesEnabled", false)
        
        val avoidTolls = dataMap.getBoolean("avoidTolls", false)
        val avoidMotorways = dataMap.getBoolean("avoidMotorways", false)
        val avoidFerries = dataMap.getBoolean("avoidFerries", false)
        val avoidUnpaved = dataMap.getBoolean("avoidUnpaved", false)

        val isForcedOffline = prefs.getBoolean("forceWatchLocalMode", false)
        val finalOfflineState = isForcedOffline || watchLocalMode
        val finalMapEnabled = standaloneMode || mapEnabled
        
        prefs.edit()
            .putLong("last_sync_timestamp", timestamp)
            .putBoolean("mapEnabled", mapEnabled)
            .putBoolean("watchLocalMode", watchLocalMode)
            .putBoolean("disconnectFromPhone", standaloneMode)
            .putBoolean("pref_wear_os_auto_download_route_maps", autoDownload)
            .putString("mapDownloadMode", mapDownloadMode)
            .putString("pref_wear_os_backend", backend)
            .putInt("poiCategoriesMask", poiMask)
            .putString("locationSource", locationSource)
            .putBoolean("pref_wear_os_3d", is3dEnabled)
            .putBoolean("pref_wear_os_3d_buildings", is3dBuildingsEnabled)
            .putBoolean("pref_wear_os_auto_zoom", isAutoZoomEnabled)
            .putInt("pref_wear_os_munits", mUnits)
            .putString("pref_wear_os_map_style", mapStyle)
            .putBoolean("pref_wear_os_avoid_tolls", avoidTolls)
            .putBoolean("pref_wear_os_avoid_motorways", avoidMotorways)
            .putBoolean("pref_wear_os_avoid_ferries", avoidFerries)
            .putBoolean("pref_wear_os_avoid_unpaved", avoidUnpaved)
            .putBoolean("pref_wear_os_transit", transitEnabled)
            .putBoolean("pref_wear_os_biking", bikingEnabled)
            .putBoolean("pref_wear_os_hiking", hikingEnabled)
            .putBoolean("pref_wear_os_isolines", isolinesEnabled)
            .apply()

        // Sync backend implementation
        if (backend != currentState.backend || standaloneMode != currentState.standaloneMode) {
            WearCommandService.initBackend(this)
        }

        // Apply native settings immediately if initialized
        val wearApp = applicationContext as WearApplication
        if (wearApp.isFullyInitialized) {
            try {
                app.organicmaps.sdk.Framework.nativeSet3dMode(is3dEnabled, is3dBuildingsEnabled)
                app.organicmaps.sdk.Framework.nativeSetAutoZoomEnabled(isAutoZoomEnabled)
                app.organicmaps.sdk.Framework.nativeSetTransitSchemeEnabled(transitEnabled)
                app.organicmaps.sdk.Framework.nativeSetCyclingLayerEnabled(bikingEnabled)
                app.organicmaps.sdk.Framework.nativeSetHikingLayerEnabled(hikingEnabled)
                app.organicmaps.sdk.Framework.nativeSetIsolinesLayerEnabled(isolinesEnabled)
                
                if (avoidTolls) RoutingOptions.addOption(RoadType.Toll) else RoutingOptions.removeOption(RoadType.Toll)
                if (avoidMotorways) RoutingOptions.addOption(RoadType.Motorway) else RoutingOptions.removeOption(RoadType.Motorway)
                if (avoidFerries) RoutingOptions.addOption(RoadType.Ferry) else RoutingOptions.removeOption(RoadType.Ferry)
                if (avoidUnpaved) RoutingOptions.addOption(RoadType.Dirty) else RoutingOptions.removeOption(RoadType.Dirty)
            } catch (_: Throwable) {}
        }

        NavigationStateHolder.update(currentState.copy(
            mapEnabled = finalMapEnabled,
            watchLocalMode = finalOfflineState,
            standaloneMode = standaloneMode,
            autoDownloadRouteMaps = autoDownload,
            mapDownloadMode = mapDownloadMode,
            backend = backend,
            poiCategoriesMask = poiMask,
            locationSource = locationSource ?: "AUTO",
            measurementUnits = mUnits,
            mapStyle = mapStyle,
            is3dEnabled = is3dEnabled,
            is3dBuildingsEnabled = is3dBuildingsEnabled,
            isAutoZoomEnabled = isAutoZoomEnabled,
            transitEnabled = transitEnabled,
            bikingEnabled = bikingEnabled,
            hikingEnabled = hikingEnabled,
            isolinesEnabled = isolinesEnabled,
            avoidTolls = avoidTolls,
            avoidMotorways = avoidMotorways,
            avoidFerries = avoidFerries,
            avoidUnpaved = avoidUnpaved,
            lastSettingsInteractionTime = timestamp
        ))
        Log.d(TAG, "Preferences updated: mapEnabled=$finalMapEnabled, watchLocal=$watchLocalMode, backend=$backend")
    }

    private fun launchOmaps() {
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
