package app.organicmaps.wear

import android.content.Intent
import app.organicmaps.wear.presentation.Omaps
import android.util.Log
import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import app.organicmaps.wear.ReloadWorldMapsDebouncer
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.sync.WearProtocolDataConverter
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DEBUG_GMS: Watch WearDataListenerService.onCreate()")
        
        // Use synchronous Tasks.await in a background thread to ensure ID is available ASAP
        scope.launch {
            try {
                val node = Wearable.getNodeClient(this@WearDataListenerService).localNode.await()
                GmsWearSyncBackend.sLocalNodeId = node.id
                app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Local node ID identified: ${node.id} (${node.displayName})")
            } catch (e: Exception) {
                app.organicmaps.sdk.sync.WearLog.e("Failed to identify local node ID", e)
            }
        }

        // Log when phone app capability changes
        Wearable.getCapabilityClient(this).addListener(
            { capabilityInfo ->
                Log.d(TAG, "DEBUG_GMS: Capability organic_maps_phone_app changed. Nodes: ${capabilityInfo.nodes.size}")
                capabilityInfo.nodes.forEach { Log.d(TAG, "DEBUG_GMS:   - Node: ${it.displayName} ID: ${it.id}") }
            },
            "organic_maps_phone_app"
        )

        // Manual check on startup
        Wearable.getCapabilityClient(this).getCapability("organic_maps_phone_app", com.google.android.gms.wearable.CapabilityClient.FILTER_ALL)
            .addOnSuccessListener { capabilityInfo ->
                Log.d(TAG, "DEBUG_GMS: Startup check - found ${capabilityInfo.nodes.size} phone nodes")
            }

        checkPhoneConnection()
        scope.launch {
            delay(2000.milliseconds)
            if (!NavigationStateHolder.state.value.isPhoneConnected) {
                checkPhoneConnection()
            }
        }
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

    // These guards MUST be process-scoped, not per-instance: GMS tears down and recreates this
    // WearableListenerService on essentially every delivered message (~every 2s here), so per-instance
    // state reset each recreation, defeating both the 5s throttle and the once-per-reachability guard
    // and re-firing the full initial-sync burst every cycle (the "sync storm"). Companion state
    // survives recreation, so the burst fires once per genuine reachability transition.
    companion object {
        @Volatile private var sLastCheckTime = 0L
        // True once we've prompted a reachable phone node for an initial sync; reset when unreachable.
        // Decouples "node reachable" prompting from the actual app-connected state.
        @Volatile private var sReachablePrompted = false
    }

    private fun checkPhoneConnection() {
        val now = System.currentTimeMillis()
        if (now - sLastCheckTime < 5000) {
            Log.d(TAG, "DEBUG_GMS: checkPhoneConnection skipped (throttled)")
            return
        }
        sLastCheckTime = now
        Log.d(TAG, "DEBUG_GMS: checkPhoneConnection checking for organic_maps_phone_app")
        
        // Diagnostic
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            Log.d(TAG, "DEBUG_GMS: Physical nodes found: ${nodes.size}")
            nodes.forEach { Log.d(TAG, "DEBUG_GMS:   - Node: ${it.displayName} ID: ${it.id} Nearby: ${it.isNearby}") }
        }

        scope.launch {
            // Try capability lookup first — falls back to connected nodes if it fails or returns empty
            // (microG may not support capability discovery but can still route GMS messages)
            val capabilityNodes = try {
                Wearable.getCapabilityClient(this@WearDataListenerService)
                    .getCapability("organic_maps_phone_app", com.google.android.gms.wearable.CapabilityClient.FILTER_ALL)
                    .await().nodes
            } catch (e: Exception) {
                Log.w(TAG, "DEBUG_GMS: Capability lookup failed, falling back to connected nodes: ${e.message}")
                null
            }

            val connected = when {
                capabilityNodes != null && capabilityNodes.isNotEmpty() -> {
                    Log.d(TAG, "DEBUG_GMS: checkPhoneConnection found ${capabilityNodes.size} nodes with capability")
                    true
                }
                else -> {
                    val physicalNodes = try {
                        Wearable.getNodeClient(this@WearDataListenerService).connectedNodes.await()
                    } catch (e: Exception) { emptyList() }
                    Log.d(TAG, "DEBUG_GMS: No capability nodes — physical nodes found: ${physicalNodes.size}")
                    physicalNodes.isNotEmpty()
                }
            }

            if (connected) {
                // A reachable phone NODE over GMS does NOT mean the phone APP is on GMS and will
                // answer — it may be on Bluetooth. So do NOT mark isPhoneConnected here (that was the
                // "GMS shows connected while phone is on Bluetooth" bug). The app connection is set by
                // WearMessageRouter only when a real app message/pong arrives on the selected backend.
                // Prompt the phone once per reachability transition to elicit such a response.
                // Re-prompt as long as we're not actually connected yet, not just once per reachability
                // transition. A one-shot latch here means: if the phone app is still cold-starting and
                // doesn't answer this very first round of requests, isPhoneConnected never flips true,
                // and — since the latch is already spent — checkPhoneConnection's later 5s retries (the
                // ones below this block) see "nodes with capability" forever but never re-issue the
                // request that would actually establish the connection. That's why a restart "fixes" it:
                // the latch resets to false at process start and the phone is warm by then. Gate on
                // actual connection state instead, so this keeps retrying every ~5s until it lands.
                if (!sReachablePrompted || !NavigationStateHolder.state.value.isPhoneConnected) {
                    sReachablePrompted = true
                    Log.i(TAG, "DEBUG_GMS_PIPELINE: Phone node reachable over GMS, requesting initial sync")
                    WearCommandService.syncPreferences()
                    WearCommandService.requestPreferences(this@WearDataListenerService)
                    WearCommandService.requestBookmarks(this@WearDataListenerService)
                    WearCommandService.requestSearchHistory(this@WearDataListenerService)
                    WearCommandService.syncSearchHistory(this@WearDataListenerService)
                    WearCommandService.requestDownloadedMaps(this@WearDataListenerService)

                    if (app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World") != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
                        WearCommandService.requestMwmMetadata(this@WearDataListenerService, "World")
                    }
                }
            } else {
                sReachablePrompted = false
                Log.d(TAG, "DEBUG_GMS_PIPELINE: No phone node reachable (capability or physical)")
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val currentLocalId = GmsWearSyncBackend.sLocalNodeId
        
        if (currentLocalId != null && messageEvent.sourceNodeId == currentLocalId) {
            app.organicmaps.sdk.sync.WearLog.v("Ignoring local loopback message at ${messageEvent.path}")
            return
        }

        // Safety: If ID is not yet known, check by display name if available
        // Note: MessageEvent doesn't have display name, but we can't do much else than wait
        if (currentLocalId == null) {
             app.organicmaps.sdk.sync.WearLog.w("Received message before local node ID identified. Path: ${messageEvent.path}")
        }

        val data = messageEvent.data ?: ByteArray(0)

        // Connection liveness is marked by WearMessageRouter (gated by the selected backend) below,
        // not here — so a GMS message can't flip the indicator when Bluetooth is selected.
        GmsWearSyncBackend.activePeerId = messageEvent.sourceNodeId
        
        val payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)
        if (data.isNotEmpty()) {
            val version = data[0]
            if (version == WearProtocol.PROTOCOL_VERSION) {
                WearMessageRouter.onMessageReceived(this, messageEvent.path, payload, messageEvent.sourceNodeId, currentLocalId)
            } else {
                app.organicmaps.sdk.sync.WearLog.e("Protocol version mismatch at ${messageEvent.path}: received=$version, expected=${WearProtocol.PROTOCOL_VERSION}")
                // For safety, don't route unknown versions
            }
        } else {
            // Empty messages like triggers
            WearMessageRouter.onMessageReceived(this, messageEvent.path, null, messageEvent.sourceNodeId, currentLocalId)
        }
    }

    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        if (channel.path.startsWith("/map/stream/data/")) {
            val mapId = channel.path.substringAfterLast("/")
            val channelClient = Wearable.getChannelClient(this)
            WearMapDownloader.setStreamingMap(mapId)
            
            Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS Channel opened for pulling map: $mapId")
            scope.launch {
                try {
                    (application as WearApplication).waitForInitializationSuspend()
                    val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(this@WearDataListenerService)
                    val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
                    val versionedPath = File(storagePath, dataVersion.toString())
                    if (!versionedPath.exists()) {
                        Log.d(TAG, "DEBUG_GMS_PIPELINE: Creating versioned storage directory: ${versionedPath.absolutePath}")
                        versionedPath.mkdirs()
                    }
                    val finalFile = File(versionedPath, "$mapId.mwm")
                    val tempFile = File(versionedPath, "$mapId.mwm.tmp")
                    
                    // Ensure the parent directory exists for the temp file
                    tempFile.parentFile?.mkdirs()

                    Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS Receiving file into: ${tempFile.absolutePath}")
                    channelClient.receiveFile(channel, android.net.Uri.fromFile(tempFile), false).await()
                    
                    Log.d(TAG, "GMS Pull completed, renaming $mapId to ${finalFile.name}")
                    if (finalFile.exists()) finalFile.delete()
                    tempFile.renameTo(finalFile)
                    
                     WearMapDownloader.onDownloadCompleted()
                    ReloadWorldMapsDebouncer.reload()
                } catch (_: Exception) {
                    Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to pull map $mapId")
                    WearMapDownloader.onDownloadCancelled()
                } finally {
                    channelClient.close(channel)
                }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // GMS DataLayer changes only indicate an app connection when GMS is the selected backend.
        val gmsSelected = getSharedPreferences("wear_prefs", android.content.Context.MODE_PRIVATE)
            .getString("pref_wear_os_backend", "GMS") == "GMS"
        val localId = GmsWearSyncBackend.sLocalNodeId
        var fromRemote = false
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            // Skip our OWN data-layer writes (loopback). GMS notifies a node of its own putDataItem,
            // and treating that as incoming traffic falsely marked the watch "connected" even when the
            // phone was on Bluetooth and nothing actually arrived.
            if (localId != null && uri.host == localId) continue
            fromRemote = true
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                if (dataMap.containsKey("protocolVersion") && dataMap.getByte("protocolVersion") != WearProtocol.PROTOCOL_VERSION) {
                    Log.e(TAG, "Protocol version mismatch in DataItem at ${uri.path}: ${dataMap.getByte("protocolVersion")}")
                    continue
                }

                when (uri.path) {
                    WearProtocol.PATH_PREFERENCES_PHONE, WearProtocol.PATH_PREFERENCES_WATCH -> handlePreferences(dataMap)
                    WearProtocol.PATH_PREFERENCES_UPDATES -> handlePreferenceUpdates(dataMap)
                    WearProtocol.PATH_MAP_PHONE_DOWNLOADED -> {
                        val ids = dataMap.getStringArrayList("mapIds")?.toSet() ?: emptySet()
                        NavigationStateHolder.update { it.copy(phoneDownloadedMaps = ids) }
                        // Clear streaming back-off / missing latch for a freshly-downloaded map.
                        VirtualMwmManager.onPhoneMapsAvailable(ids)
                    }
                    WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS -> {
                        val countryId = dataMap.getString("countryId") ?: continue
                        val progress = dataMap.getInt("progress", 0)
                        if (countryId == WearMapDownloader.currentMap.value) {
                            WearMapDownloader.setStreamingProgress(progress / 100f)
                        }
                    }
                }
            }
        }

        // Mark the app connection live only for genuine remote traffic on the selected backend.
        if (fromRemote && gmsSelected) {
            (applicationContext as WearApplication).onActivityReceived()
        }
    }

    private fun handlePreferences(dataMap: DataMap) {
        val manager = SettingsSyncManager.getInstance(this)
        val updates = mutableListOf<BaseSettingsSyncManager.SettingUpdate>()
        val globalTs = dataMap.getLong("timestamp", 0L)
        for (key in dataMap.keySet()) {
            if (key.startsWith("ts_") || key == "timestamp" || key == "protocolVersion") continue
            val ts = dataMap.getLong("ts_$key", globalTs)
            val ver = dataMap.getLong("v_$key", 0L)
            val value = dataMap.get<Any>(key) ?: continue
            updates.add(BaseSettingsSyncManager.SettingUpdate(key, value, ts, ver))
        }
        manager.applyRemoteUpdates(updates)
    }

    private fun handlePreferenceUpdates(dataMap: DataMap) {
        val manager = SettingsSyncManager.getInstance(this)
        val updates = mutableListOf<BaseSettingsSyncManager.SettingUpdate>()
        for (key in dataMap.keySet()) {
            if (key == "_trigger" || key == "protocolVersion") continue
            val item = dataMap.getDataMap(key) ?: continue
            val value = item.get<Any>("v") ?: continue
            updates.add(BaseSettingsSyncManager.SettingUpdate(key, value, item.getLong("t"), item.getLong("ver", 0L)))
        }
        manager.applyRemoteUpdates(updates)
    }

    /*
    private fun launchOmaps() {
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
    */
}
