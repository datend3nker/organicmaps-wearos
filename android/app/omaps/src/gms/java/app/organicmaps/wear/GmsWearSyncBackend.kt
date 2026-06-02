package app.organicmaps.wear

import android.content.Context
import android.util.Log
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
        private const val PATH_BOOKMARK_RENAME = "/bookmark/rename"
        private const val PATH_BOOKMARK_DELETE = "/bookmark/delete"
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
        val pongData = byteArrayOf(IWearSyncBackend.PROTOCOL_VERSION)
        Wearable.getMessageClient(context).sendMessage(nodeId, "/pong", pongData)
            .addOnSuccessListener { Log.d("GmsWearSync", "DEBUG_GMS: Sent pong to $nodeId") }
    }

    override fun syncPreferences(context: Context) {
        val all = SettingsSyncManager.getAllSettings(context)
        Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: syncPreferences (Full Sync) - Items: ${all.size}")
        
        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/preferences/watch")
        val map = putDataMapReq.dataMap
        map.putByte("protocolVersion", IWearSyncBackend.PROTOCOL_VERSION)
        
        for (update in all) {
            putValue(map, update.key, update.value)
            map.putLong("ts_" + update.key, update.timestamp)
        }
        
        map.putLong("timestamp", System.currentTimeMillis())
        
        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener { 
                Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Successfully putDataItem for full preferences")
                SettingsSyncManager.markAsSynced(context, all)
            }
            .addOnFailureListener { e -> Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Failed to putDataItem for full preferences", e) }
    }

    override fun syncPreferenceUpdates(context: Context, updates: List<SettingsSyncManager.SettingUpdate>) {
        if (updates.isEmpty()) return
        Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: syncPreferenceUpdates (Buffered) - Items: ${updates.size}")

        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/preferences/updates")
        val map = putDataMapReq.dataMap
        map.putByte("protocolVersion", IWearSyncBackend.PROTOCOL_VERSION)
        
        for (update in updates) {
            Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Buffering setting for transmission: ${update.key} = ${update.value}")
            val item = com.google.android.gms.wearable.DataMap()
            putValue(item, "v", update.value)
            item.putLong("t", update.timestamp)
            map.putDataMap(update.key, item)
        }
        
        map.putLong("_trigger", System.currentTimeMillis())

        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener { 
                Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Successfully putDataItem for buffered updates")
                SettingsSyncManager.markAsSynced(context, updates)
            }
            .addOnFailureListener { e -> Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Failed to putDataItem for buffered updates", e) }
    }

    private fun putValue(map: com.google.android.gms.wearable.DataMap, key: String, value: Any) {
        when (value) {
            is Boolean -> map.putBoolean(key, value)
            is String -> map.putString(key, value)
            is Int -> map.putInt(key, value)
            is Long -> map.putLong(key, value)
        }
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
        putDataMapReq.dataMap.putByte("protocolVersion", IWearSyncBackend.PROTOCOL_VERSION)
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
            if (task.isSuccessful() && task.result != null) {
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
        putDataMapReq.dataMap.putByte("protocolVersion", IWearSyncBackend.PROTOCOL_VERSION)
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

    override fun toggleBookmarkCategory(context: Context, categoryName: String) {
        sendMessage(context, PATH_BOOKMARK_VISIBLE_TOGGLE, categoryName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun syncCategory(context: Context, categoryName: String) {
        sendMessage(context, PATH_BOOKMARK_SYNC_REQUEST, categoryName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun renameBookmarkCategory(context: Context, oldName: String, newName: String) {
        val oldBytes = oldName.toByteArray(StandardCharsets.UTF_8)
        val newBytes = newName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + oldBytes.size + 4 + newBytes.size)
        buffer.putInt(oldBytes.size)
        buffer.put(oldBytes)
        buffer.putInt(newBytes.size)
        buffer.put(newBytes)
        sendMessage(context, PATH_BOOKMARK_RENAME, buffer.array())
    }

    override fun deleteBookmarkCategory(context: Context, name: String) {
        sendMessage(context, PATH_BOOKMARK_DELETE, name.toByteArray(StandardCharsets.UTF_8))
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
        
        val versionedData = ByteArray(data.size + 1)
        versionedData[0] = IWearSyncBackend.PROTOCOL_VERSION
        System.arraycopy(data, 0, versionedData, 1, data.size)

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
                
                Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: sendMessage to $path (payload=${versionedData.size} bytes): found ${nodes.size} nodes")
                if (nodes.isEmpty()) {
                    Log.w("GmsWearSync", "DEBUG_GMS_PIPELINE: No connected nodes found for $path")
                    if (NavigationStateHolder.state.value.isPhoneConnected) {
                         NavigationStateHolder.update { it.copy(isPhoneConnected = false) }
                    }
                }
                for (node in nodes) {
                    try {
                        withTimeoutOrNull(5000L) {
                            messageClient.sendMessage(node.id, path, versionedData).await()
                        }
                        Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Successfully sent message to ${node.displayName} at $path")
                    } catch (e: Exception) {
                        Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Failed to send message to ${node.displayName} at $path: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Error in sendMessage: $path", e)
            }
        }
    }
}
