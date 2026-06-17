package app.organicmaps.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds

import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.sync.WearProtocolDataConverter

class GmsWearSyncBackend : IWearSyncBackend {
    companion object {
        @JvmStatic
        var activePeerId: String? 
            get() = SyncStateManager.activePeerId
            set(value) { SyncStateManager.activePeerId = value }
        @JvmStatic
        var sLocalNodeId: String? 
            get() = SyncStateManager.localNodeId
            set(value) { SyncStateManager.localNodeId = value }
    }

    init {
        // Capability registration is handled statically in wearable_capabilities.xml
    }

    override fun stop() {
    }

    override fun stopNavigation(context: Context) {
        sendMessage(context, WearProtocol.PATH_NAVIGATION_STOP, byteArrayOf())
    }

    override fun cancelMapSync(context: Context, mapId: String) {
        sendMessage(context, WearProtocol.PATH_MAP_DOWNLOAD_CANCEL, mapId.toByteArray(StandardCharsets.UTF_8))
    }

    override fun requestDownloadedMaps(context: Context) {
        sendMessage(context, WearProtocol.PATH_MAP_PHONE_DOWNLOADED_REQUEST, byteArrayOf())
    }

    override fun search(context: Context, query: String, lat: Double, lon: Double) {
        val queryBytes = query.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + queryBytes.size)
        buffer.putDouble(lat)
        buffer.putDouble(lon)
        buffer.put(queryBytes)
        sendMessage(context, WearProtocol.PATH_SEARCH_QUERY, buffer.array())
    }

    override fun requestSearchHistory(context: Context) {
        sendMessage(context, WearProtocol.PATH_SEARCH_HISTORY_REQUEST, byteArrayOf())
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

        sendMessage(context, WearProtocol.PATH_SEARCH_SELECT, buffer.array())
    }



    override fun sendPing(context: Context) {
        sendMessage(context, WearProtocol.PATH_PING, byteArrayOf())
    }

    override fun sendPong(context: Context, nodeId: String) {
        val pongData = byteArrayOf(WearProtocol.PROTOCOL_VERSION)
        Wearable.getMessageClient(context).sendMessage(nodeId, WearProtocol.PATH_PONG, pongData)
            .addOnSuccessListener { Log.d("GmsWearSync", "DEBUG_GMS: Sent pong to $nodeId") }
    }

    override fun syncPreferences(context: Context) {
        val manager = SettingsSyncManager.getInstance(context)
        val all = manager.getAllSettings()
        Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: syncPreferences (Full Sync) - Items: ${all.size}")
        
        val putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_PREFERENCES_WATCH)
        val map = putDataMapReq.dataMap
        map.putByte("protocolVersion", WearProtocol.PROTOCOL_VERSION)
        
        for (update in all) {
            putValue(map, update.key, update.value)
            map.putLong("ts_" + update.key, update.timestamp)
            map.putLong("v_" + update.key, update.version)
        }
        
        map.putLong("timestamp", System.currentTimeMillis())
        
        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener { 
                Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Successfully putDataItem for full preferences")
                manager.markAsSynced(all)
            }
            .addOnFailureListener { e -> Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Failed to putDataItem for full preferences", e) }
    }

    override fun syncPreferenceUpdates(context: Context, updates: List<BaseSettingsSyncManager.SettingUpdate>) {
        if (updates.isEmpty()) return
        Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: syncPreferenceUpdates (Buffered) - Items: ${updates.size}")

        val manager = SettingsSyncManager.getInstance(context)
        val putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_PREFERENCES_UPDATES)
        val map = putDataMapReq.dataMap
        map.putByte("protocolVersion", WearProtocol.PROTOCOL_VERSION)
        
        for (update in updates) {
            Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Buffering setting for transmission: ${update.key} = ${update.value}")
            val item = DataMap()
            putValue(item, "v", update.value)
            item.putLong("t", update.timestamp)
            item.putLong("ver", update.version)
            map.putDataMap(update.key, item)
        }
        
        map.putLong("_trigger", System.currentTimeMillis())

        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
            .addOnSuccessListener { 
                Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Successfully putDataItem for buffered updates")
                manager.markAsSynced(updates)
            }
            .addOnFailureListener { e -> Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Failed to putDataItem for buffered updates", e) }
    }

    private fun putValue(map: DataMap, key: String, value: Any) {
        when (value) {
            is Boolean -> map.putBoolean(key, value)
            is String -> map.putString(key, value)
            is Int -> map.putInt(key, value)
            is Long -> map.putLong(key, value)
        }
    }


    override fun requestPreferences(context: Context) {
        sendMessage(context, WearProtocol.PATH_PREFERENCES_REQUEST, byteArrayOf())
    }

    override fun syncSearchHistory(context: Context) {
        app.organicmaps.sdk.search.SearchRecents.refresh()
        val size = app.organicmaps.sdk.search.SearchRecents.getSize()
        if (size == 0) return

        val history = ArrayList<String>()
        for (i in 0 until size) {
            history.add(app.organicmaps.sdk.search.SearchRecents.get(i))
        }

        val putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_SEARCH_HISTORY_SYNC)
        putDataMapReq.dataMap.putByte("protocolVersion", WearProtocol.PROTOCOL_VERSION)
        putDataMapReq.dataMap.putStringArrayList("history", history)
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())
        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
    }

    override fun startNavigation(context: Context) {
        sendMessage(context, WearProtocol.PATH_NAVIGATION_START, byteArrayOf())
    }

    override fun showOnPhone(context: Context, result: SearchResultItem) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.put(nameBytes)
        sendMessage(context, WearProtocol.PATH_POI_SHOW, buffer.array())
    }

    override fun checkConnection(context: Context, callback: (Boolean, String?) -> Unit) {
        val capabilityClient = Wearable.getCapabilityClient(context)
        Log.d("GmsWearSync", "DEBUG_GMS: checkConnection requested")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val capabilityInfo = capabilityClient.getCapability("organic_maps_phone_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE).await()
                val nodes = capabilityInfo.nodes
                if (nodes.isNotEmpty()) {
                    val nodeName = nodes.first().displayName
                    Log.d("GmsWearSync", "DEBUG_GMS: Found phone via capability: $nodeName")
                    callback(true, nodeName)
                } else {
                    val physicalNodes = Wearable.getNodeClient(context).connectedNodes.await()
                    val hasPhone = physicalNodes.any { !it.displayName.contains("Watch", ignoreCase = true) }
                    if (hasPhone) {
                        Log.d("GmsWearSync", "DEBUG_GMS: Capability not found, but potential phone node exists. Reporting connected.")
                        callback(true, physicalNodes.firstOrNull { !it.displayName.contains("Watch", ignoreCase = true) }?.displayName)
                    } else {
                        Log.d("GmsWearSync", "DEBUG_GMS: No phone nodes found (capability or physical)")
                        callback(false, null)
                    }
                }
            } catch (e: Exception) {
                Log.e("GmsWearSync", "DEBUG_GMS: Failed to check phone connection", e)
                callback(false, null)
            }
        }
    }

    override fun sendBackendSwitch(context: Context, newBackend: String) {
        sendMessage(context, WearProtocol.PATH_BACKEND_SWITCH, newBackend.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendMapDownloadRequest(context: Context, mapId: String, offset: Long, checksum: Long) {
        val mapIdBytes = mapId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + mapIdBytes.size + 8 + 8)
        buffer.putInt(mapIdBytes.size)
        buffer.put(mapIdBytes)
        buffer.putLong(offset)
        buffer.putLong(checksum)
        sendMessage(context, WearProtocol.PATH_MAP_DOWNLOAD_REQUEST, buffer.array())
    }

    override fun sendMapProgress(context: Context, mapId: String, progress: Int) {
        val putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS)
        putDataMapReq.dataMap.putByte("protocolVersion", WearProtocol.PROTOCOL_VERSION)
        putDataMapReq.dataMap.putString("countryId", mapId)
        putDataMapReq.dataMap.putInt("progress", progress)
        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent()
        Wearable.getDataClient(context).putDataItem(putDataReq)
    }

    override fun toggleTrackRecording(context: Context) {
        sendMessage(context, WearProtocol.PATH_TRACK_RECORDING_TOGGLE, byteArrayOf())
    }

    override fun requestBookmarks(context: Context) {
        sendMessage(context, WearProtocol.PATH_BOOKMARKS_REQUEST, byteArrayOf())
    }

    override fun toggleBookmarkCategory(context: Context, categoryName: String) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_VISIBLE_TOGGLE, categoryName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun syncCategory(context: Context, categoryName: String) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_SYNC_REQUEST, categoryName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun renameBookmarkCategory(context: Context, oldName: String, newName: String) {
        val oldBytes = oldName.toByteArray(StandardCharsets.UTF_8)
        val newBytes = newName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + oldBytes.size + 4 + newBytes.size)
        buffer.putInt(oldBytes.size)
        buffer.put(oldBytes)
        buffer.putInt(newBytes.size)
        buffer.put(newBytes)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_RENAME, buffer.array())
    }

    override fun deleteBookmarkCategory(context: Context, name: String) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_DELETE, name.toByteArray(StandardCharsets.UTF_8))
    }

    override fun createBookmarkCategory(context: Context, name: String) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_CATEGORY_CREATE, name.toByteArray(StandardCharsets.UTF_8))
    }

    override fun showBookmarkOnPhone(context: Context, bmkId: Long) {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(bmkId)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_SHOW, buffer.array())
    }

    override fun updateBookmarkOnPhone(context: Context, bmkId: Long, name: String, color: Int, categoryId: Long) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 4 + nameBytes.size + 4 + 8)
        buffer.putLong(bmkId)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putInt(color)
        buffer.putLong(categoryId)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_UPDATE, buffer.array())
    }

    override fun sendBookmarkFile(context: Context, categoryName: String, data: ByteArray, isLast: Boolean, merge: Boolean) {
        val nameBytes = categoryName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 4 + nameBytes.size + data.size)
        val flags = (if (isLast) 1 else 0) or (if (merge) 2 else 0)
        buffer.put(flags.toByte())
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.put(data)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_FILE, buffer.array())
    }

    override fun sendBookmarksMetadata(context: Context, payload: ByteArray) {
        sendMessage(context, WearProtocol.PATH_BOOKMARKS_METADATA, payload)
    }

    override fun sendBookmarkUpsert(context: Context, payload: ByteArray) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_UPSERT, payload)
    }

    override fun sendBookmarkTombstone(context: Context, payload: ByteArray) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_TOMBSTONE, payload)
    }

    override fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int) {
        val nameBytes = mwmName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + nameBytes.size + 8 + 4)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putLong(offset)
        buffer.putInt(size)
        sendMessage(context, WearProtocol.PATH_VIRTUAL_MWM_REQUEST, buffer.array())
    }

    override fun requestMwmMetadata(context: Context, mwmName: String) {
        sendMessage(context, WearProtocol.PATH_VIRTUAL_MWM_METADATA_REQUEST, mwmName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun launchPhoneApp(context: Context) {
        val nodeClient = Wearable.getNodeClient(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) return@launch
                
                val phonePackage = if (BuildConfig.APPLICATION_ID.endsWith(".debug")) 
                                     "app.organicmaps.debug" 
                                   else "app.organicmaps"

                val remoteActivityHelper = androidx.wear.remote.interactions.RemoteActivityHelper(context)
                remoteActivityHelper.startRemoteActivity(
                    android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        .setComponent(android.content.ComponentName(phonePackage, "app.organicmaps.SplashActivity")),
                    nodes[0].id
                )
                Log.d("GmsWearSync", "Requested phone app launch on ${nodes[0].displayName} (Package: $phonePackage)")
            } catch (e: Exception) {
                Log.e("GmsWearSync", "Failed to launch phone app", e)
            }
        }
    }

    override fun sendHandshake(context: Context) {
        val payload = WearProtocolDataConverter.encodeHandshake(BuildConfig.VERSION_CODE, 0.toByte())
        sendMessage(context, WearProtocol.PATH_HANDSHAKE, payload)
    }

    private fun sendMessage(context: Context, path: String, data: ByteArray) {
        val messageClient = Wearable.getMessageClient(context)
        val nodeClient = Wearable.getNodeClient(context)

        val versionedData = ByteArray(data.size + 1)
        versionedData[0] = WearProtocol.PROTOCOL_VERSION
        System.arraycopy(data, 0, versionedData, 1, data.size)

        app.organicmaps.sdk.sync.WearLog.logSent("WATCH", "GMS", path, versionedData.size)

        CoroutineScope(Dispatchers.IO).launch {
            if (sLocalNodeId == null) {
                try {
                    sLocalNodeId = nodeClient.localNode.await().id
                } catch (e: Exception) {
                    app.organicmaps.sdk.sync.WearLog.w("Failed to get local node ID: ${e.message}")
                }
            }
            val localNodeId = sLocalNodeId

            val targetNodes: Set<Node> = try {
                val capNodes = Wearable.getCapabilityClient(context)
                    .getCapability("organic_maps_phone_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                    .await().nodes
                if (capNodes.isNotEmpty()) {
                    capNodes
                } else {
                    app.organicmaps.sdk.sync.WearLog.d("No capability nodes reachable, falling back to all connected nodes for $path")
                    nodeClient.connectedNodes.await().toSet()
                }
            } catch (e: Exception) {
                app.organicmaps.sdk.sync.WearLog.w("Capability lookup failed for $path: ${e.message}")
                try {
                    nodeClient.connectedNodes.await().toSet()
                } catch (ex: Exception) {
                    app.organicmaps.sdk.sync.WearLog.e("Cannot find any nodes for $path", ex)
                    emptySet()
                }
            }

            val validTargets = targetNodes.filter { 
                val isSelf = it.id == localNodeId
                val name = it.displayName.lowercase()
                val isWatch = name.contains("watch") || 
                              name.contains("round") ||
                              name.contains("square") ||
                              name.contains("wear")
                !isSelf && !isWatch 
            }
            
            for (node in validTargets) {
                try {
                    withTimeoutOrNull(5.seconds) {
                        messageClient.sendMessage(node.id, path, versionedData).await()
                    }
                    Log.d("GmsWearSync", "DEBUG_GMS_PIPELINE: Sent message to ${node.displayName} at $path")
                } catch (e: Exception) {
                    Log.e("GmsWearSync", "DEBUG_GMS_PIPELINE: Failed to send to ${node.displayName}: ${e.message}")
                }
            }
        }
    }
}
