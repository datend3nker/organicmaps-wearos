package app.organicmaps.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class GmsWearSyncBackend : IWearSyncBackend {
    companion object {
        private const val PATH_STOP_NAVIGATION = "/navigation/stop"
        private const val PATH_SEARCH_QUERY = "/search/query"
        private const val PATH_SEARCH_SELECT = "/search/select"
        private const val PATH_SEARCH_HISTORY_REQUEST = "/search/history/request"
        private const val PATH_PING = "/ping"
        private const val PATH_PREFERENCES_REQUEST = "/preferences/request"
        private const val PATH_START_NAVIGATION_REQUEST = "/navigation/start/request"
        private const val PATH_POI_SHOW = "/poi/show"
        private const val PATH_MAP_DOWNLOAD_CANCEL = "/map/download/cancel"
        private const val PATH_MAP_DOWNLOAD_REQUEST = "/map/download/request"
        private const val PATH_BACKEND_SWITCH = "/backend/switch"
        private const val PATH_TRACK_RECORDING_TOGGLE = "/track/recording/toggle"
        private const val PATH_BOOKMARK_VISIBLE_TOGGLE = "/bookmark/visible/toggle"
        private const val PATH_BOOKMARK_SYNC_REQUEST = "/bookmark/sync/request"
        private const val PATH_BOOKMARKS_REQUEST = "/bookmarks/request"
        private const val PATH_BOOKMARK_SHOW = "/bookmark/show"
        private const val PATH_BOOKMARK_UPDATE = "/bookmark/update"
        private const val PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request"
        private const val PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request"
    }

    private val manualListener = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { event ->
        WearMessageRouter.onMessageReceived(WearApplication.instance, event.path, event.data, event.sourceNodeId)
    }

    init {
        // Plan B: Manual Message Listener to ensure we always respond to messages even if Service is not up
        Wearable.getMessageClient(WearApplication.instance).addListener(manualListener)
    }

    override fun stop() {
        Wearable.getMessageClient(WearApplication.instance).removeListener(manualListener)
    }

    override fun stopNavigation(context: Context) {
        sendMessage(context, PATH_STOP_NAVIGATION, byteArrayOf())
    }

    override fun cancelMapSync(context: Context, mapId: String) {
        sendMessage(context, PATH_MAP_DOWNLOAD_CANCEL, mapId.toByteArray(StandardCharsets.UTF_8))
    }

    override fun search(context: Context, query: String, lat: Double, lon: Double) {
        val queryBytes = query.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + queryBytes.size)
        buffer.putDouble(lat)
        buffer.putDouble(lon)
        buffer.put(queryBytes)
        sendMessage(context, PATH_SEARCH_QUERY, buffer.array())
    }

    override fun requestSearchHistory(context: Context) {
        sendMessage(context, PATH_SEARCH_HISTORY_REQUEST, byteArrayOf())
    }

    override fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + 4 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.putInt(routerType)
        buffer.put(nameBytes)

        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            destinationName = result.name,
            routerType = routerType
        ))

        sendMessage(context, PATH_SEARCH_SELECT, buffer.array())
    }



    override fun sendPing(context: Context) {
        sendMessage(context, PATH_PING, byteArrayOf())
    }

    override fun sendPong(context: Context, nodeId: String) {
        sendMessage(context, "/pong", byteArrayOf())
    }

    override fun syncPreferences(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val watchLocalMode = prefs.getBoolean("watchLocalMode", false)
        val standaloneMode = prefs.getBoolean("disconnectFromPhone", false)
        val autoDownload = prefs.getBoolean("autoDownloadRouteMaps", true)
        val downloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
        val backend = prefs.getString("pref_wear_os_backend", "GMS")
        val poiMask = prefs.getInt("poiCategoriesMask", 0x3F)
        
        val is3dEnabled = prefs.getBoolean("pref_wear_os_3d", true)
        val is3dBuildingsEnabled = prefs.getBoolean("pref_wear_os_3d_buildings", true)
        val isAutoZoomEnabled = prefs.getBoolean("pref_wear_os_auto_zoom", true)
        val mUnits = prefs.getInt("pref_wear_os_munits", 0)
        val mapStyle = prefs.getString("pref_wear_os_map_style", "default")

        val transitEnabled = prefs.getBoolean("pref_wear_os_transit", false)
        val bikingEnabled = prefs.getBoolean("pref_wear_os_biking", false)
        val hikingEnabled = prefs.getBoolean("pref_wear_os_hiking", false)
        val isolinesEnabled = prefs.getBoolean("pref_wear_os_isolines", false)

        val avoidTolls = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Toll)
        val avoidMotorways = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Motorway)
        val avoidFerries = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Ferry)
        val avoidUnpaved = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Dirty)
        val syncNotificationsEnabled = prefs.getBoolean("pref_sync_notifications", true)

        val currentState = NavigationStateHolder.state.value
        val now = System.currentTimeMillis()
        
        // CHECK IF ACTUALLY CHANGED to avoid infinite sync loops
        // Only skip if it's not a forced sync and values are identical
        val lastSentTimestamp = prefs.getLong("last_sent_prefs_timestamp", 0)
        val lastSyncTimestamp = prefs.getLong("last_sync_timestamp", 0)
        
        // If we received a more recent update from phone, don't send back unless we interacted
        if (lastSyncTimestamp > lastSentTimestamp && currentState.lastSettingsInteractionTime <= lastSyncTimestamp) {
            android.util.Log.d("GmsWearSync", "DEBUG_GMS: Skipping sync to phone, remote state is newer/current")
            return
        }

        // DEDUPLICATION: Check if values actually changed
        val currentMapEnabled = prefs.getBoolean("mapEnabled", false)
        // ... (We rely on lastSettingsInteractionTime being updated by UI interaction)
        
        android.util.Log.d("GmsWearSync", "DEBUG_GMS: Syncing preferences to phone: mapEnabled=$mapEnabled, watchLocal=$watchLocalMode")

        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/preferences/watch")
        val map = putDataMapReq.dataMap
        map.putBoolean("mapEnabled", mapEnabled)
        map.putBoolean("watchLocalMode", watchLocalMode)
        map.putBoolean("standaloneMode", standaloneMode)
        map.putBoolean("autoDownloadRouteMaps", autoDownload)
        map.putString("mapDownloadMode", downloadMode)
        map.putString("backend", backend ?: "GMS")
        map.putInt("poiCategoriesMask", poiMask)
        map.putBoolean("is3dEnabled", is3dEnabled)
        map.putBoolean("is3dBuildingsEnabled", is3dBuildingsEnabled)
        map.putBoolean("isAutoZoomEnabled", isAutoZoomEnabled)
        map.putInt("measurementUnits", mUnits)
        map.putString("mapStyle", mapStyle ?: "default")
        map.putBoolean("transitEnabled", transitEnabled)
        map.putBoolean("bikingEnabled", bikingEnabled)
        map.putBoolean("hikingEnabled", hikingEnabled)
        map.putBoolean("isolinesEnabled", isolinesEnabled)
        map.putBoolean("avoidTolls", avoidTolls)
        map.putBoolean("avoidMotorways", avoidMotorways)
        map.putBoolean("avoidFerries", avoidFerries)
        map.putBoolean("avoidUnpaved", avoidUnpaved)
        map.putBoolean("syncNotificationsEnabled", syncNotificationsEnabled)
        map.putLong("timestamp", now)
        
        prefs.edit().putLong("last_sent_prefs_timestamp", now).apply()

        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent() // Critical for immediate sync
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener { android.util.Log.d("GmsWearSync", "DEBUG_GMS: Preferences sent successfully to phone") }
            .addOnFailureListener { e -> android.util.Log.e("GmsWearSync", "DEBUG_GMS: Failed to send preferences to phone", e) }
    }


    override fun requestPreferences(context: Context) {
        sendMessage(context, PATH_PREFERENCES_REQUEST, byteArrayOf())
    }

    override fun syncSearchHistory(context: Context) {
        app.organicmaps.sdk.search.SearchRecents.refresh()
        val size = app.organicmaps.sdk.search.SearchRecents.getSize()
        if (size == 0) return

        val history = ArrayList<String>()
        for (i in 0 until size) {
            history.add(app.organicmaps.sdk.search.SearchRecents.get(i))
        }

        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/search/history/sync")
        putDataMapReq.dataMap.putStringArrayList("history", history)
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
        putDataMapReq.setUrgent()
        com.google.android.gms.wearable.Wearable.getDataClient(context).putDataItem(putDataMapReq.asPutDataRequest())
    }

    override fun startNavigation(context: Context) {
        sendMessage(context, PATH_START_NAVIGATION_REQUEST, byteArrayOf())
    }

    override fun showOnPhone(context: Context, result: SearchResultItem) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.put(nameBytes)
        sendMessage(context, PATH_POI_SHOW, buffer.array())
    }

    override fun checkConnection(context: Context, callback: (Boolean) -> Unit) {
        val nodeClient = Wearable.getNodeClient(context)
        nodeClient.connectedNodes.addOnCompleteListener { task ->
            if (task.isSuccessful && task.result != null) {
                callback(task.result.isNotEmpty())
            } else {
                callback(false)
            }
        }
    }

    override fun sendBackendSwitch(context: Context, newBackend: String) {
        sendMessage(context, PATH_BACKEND_SWITCH, newBackend.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendMapDownloadRequest(context: Context, mapId: String) {
        sendMessage(context, PATH_MAP_DOWNLOAD_REQUEST, mapId.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendMapProgress(context: Context, mapId: String, progress: Int) {
        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/map/download/progress")
        putDataMapReq.dataMap.putString("countryId", mapId)
        putDataMapReq.dataMap.putInt("progress", progress)
        putDataMapReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataMapReq.asPutDataRequest())
    }

    override fun toggleTrackRecording(context: Context) {
        sendMessage(context, PATH_TRACK_RECORDING_TOGGLE, byteArrayOf())
    }

    override fun requestBookmarks(context: Context) {
        sendMessage(context, PATH_BOOKMARKS_REQUEST, byteArrayOf())
    }

    override fun toggleBookmarkCategory(context: Context, categoryId: Long) {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(categoryId)
        sendMessage(context, PATH_BOOKMARK_VISIBLE_TOGGLE, buffer.array())
    }

    override fun syncCategory(context: Context, categoryId: Long) {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(categoryId)
        sendMessage(context, PATH_BOOKMARK_SYNC_REQUEST, buffer.array())
    }

    override fun showBookmarkOnPhone(context: Context, bmkId: Long) {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(bmkId)
        sendMessage(context, PATH_BOOKMARK_SHOW, buffer.array())
    }

    override fun updateBookmarkOnPhone(context: Context, bmkId: Long, name: String, color: Int) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 4 + nameBytes.size + 4)
        buffer.putLong(bmkId)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putInt(color)
        sendMessage(context, PATH_BOOKMARK_UPDATE, buffer.array())
    }

    override fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int) {
        val nameBytes = mwmName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + nameBytes.size + 8 + 4)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putLong(offset)
        buffer.putInt(size)
        sendMessage(context, PATH_VIRTUAL_MWM_REQUEST, buffer.array())
    }

    override fun requestMwmMetadata(context: Context, mwmName: String) {
        sendMessage(context, PATH_VIRTUAL_MWM_METADATA_REQUEST, mwmName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun launchPhoneApp(context: Context) {
        val nodeClient = Wearable.getNodeClient(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) return@launch
                
                // Use the first connected node to trigger launch
                val remoteActivityHelper = androidx.wear.remote.interactions.RemoteActivityHelper(context)
                // We use the phone's SplashActivity as the entry point
                val result = remoteActivityHelper.startRemoteActivity(
                    android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        .setComponent(android.content.ComponentName(context.packageName, "app.organicmaps.SplashActivity")),
                    nodes[0].id
                )
                android.util.Log.d("GmsWearSync", "Requested phone app launch on ${nodes[0].displayName}")
            } catch (e: Exception) {
                android.util.Log.e("GmsWearSync", "Failed to launch phone app", e)
            }
        }
    }

    private fun sendMessage(context: Context, path: String, data: ByteArray) {
        val messageClient = Wearable.getMessageClient(context)
        val nodeClient = Wearable.getNodeClient(context)
        val capabilityClient = Wearable.getCapabilityClient(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Try to find nodes with the phone app capability first
                val capabilityInfo = withTimeoutOrNull(5000L) {
                    capabilityClient.getCapability("organic_maps_phone_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE).await()
                }
                
                var nodes = capabilityInfo?.nodes ?: emptySet()
                
                if (nodes.isEmpty()) {
                    // Fallback to all connected nodes
                    nodes = withTimeoutOrNull(5000L) {
                        nodeClient.connectedNodes.await().toSet()
                    } ?: emptySet()
                }
                
                android.util.Log.d("GmsWearSync", "DEBUG_GMS: sendMessage to $path: found ${nodes.size} nodes")
                if (nodes.isEmpty()) {
                    android.util.Log.w("GmsWearSync", "DEBUG_GMS: No connected nodes found for $path")
                    if (NavigationStateHolder.state.value.isPhoneConnected) {
                         NavigationStateHolder.update { it.copy(isPhoneConnected = false) }
                    }
                }
                for (node in nodes) {
                    try {
                        withTimeoutOrNull(5000L) {
                            messageClient.sendMessage(node.id, path, data).await()
                        }
                        android.util.Log.d("GmsWearSync", "DEBUG_GMS: Sent message to ${node.displayName} at $path")
                    } catch (e: Exception) {
                        android.util.Log.e("GmsWearSync", "Failed to send message to ${node.displayName}", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GmsWearSync", "Error in sendMessage: $path", e)
            }
        }
    }
}
