package app.organicmaps.wear

import android.content.Intent
import app.organicmaps.wear.presentation.Omaps
import android.util.Log
import app.organicmaps.wear.ReloadWorldMapsDebouncer
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                
                NavigationStateHolder.update { it.copy(isPhoneConnected = connected) }

                if (connected) {
                    WearCommandService.syncPreferences(this@WearDataListenerService)
                    WearCommandService.requestPreferences(this@WearDataListenerService)
                    WearCommandService.requestBookmarks(this@WearDataListenerService)
                    WearCommandService.syncSearchHistory(this@WearDataListenerService)
                    
                    if (app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World") != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
                        WearCommandService.requestMwmMetadata(this@WearDataListenerService, "World")
                    }
                } else {
                    val allNodes = Wearable.getNodeClient(this@WearDataListenerService).connectedNodes.await()
                    if (allNodes.isEmpty()) {
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = false))
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
            val channelClient = Wearable.getChannelClient(this)
            WearMapDownloader.setStreamingMap(mapId)
            
            scope.launch {
                try {
                    (application as WearApplication).waitForInitializationSuspend()
                    val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(this@WearDataListenerService)
                    val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
                    val versionedPath = File(storagePath, dataVersion.toString())
                    if (!versionedPath.exists()) versionedPath.mkdirs()
                    val tempFile = File(versionedPath, "$mapId.mwm.tmp")

                    channelClient.getInputStream(channel).await().use { input ->
                        if (app.organicmaps.wear.VirtualMwmManager.isMounted(mapId)) {
                            Log.d(TAG, "GMS: Streaming directly into mounted virtual MWM: $mapId")
                            val buffer = ByteArray(64 * 1024)
                            var offset = 0L
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                app.organicmaps.wear.VirtualMwmManager.onBytesReceived(mapId, offset, buffer.copyOf(bytesRead))
                                offset += bytesRead
                            }
                        } else {
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    if (!app.organicmaps.wear.VirtualMwmManager.isMounted(mapId)) {
                        val finalFile = File(versionedPath, "$mapId.mwm")
                        if (finalFile.exists()) finalFile.delete()
                        tempFile.renameTo(finalFile)
                    }
                    WearMapDownloader.onDownloadCompleted()
                    ReloadWorldMapsDebouncer.reload()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to receive map stream for $mapId", e)
                    WearMapDownloader.onDownloadCancelled()
                } finally {
                    channelClient.close(channel)
                }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        if (dataEvents.count > 0) {
            (applicationContext as WearApplication).onActivityReceived()
        }
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                if (dataMap.containsKey("protocolVersion") && dataMap.getByte("protocolVersion") != IWearSyncBackend.PROTOCOL_VERSION) {
                    Log.e(TAG, "Protocol version mismatch in DataItem at ${uri.path}: ${dataMap.getByte("protocolVersion")}")
                    continue
                }

                when (uri.path) {
                    "/preferences", "/preferences/phone", "/preferences/watch" -> handlePreferences(dataMap)
                    "/preferences/updates" -> handlePreferenceUpdates(dataMap)
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
        val updates = mutableListOf<SettingsSyncManager.SettingUpdate>()
        val globalTs = dataMap.getLong("timestamp", 0L)
        for (key in dataMap.keySet()) {
            if (key.startsWith("ts_") || key == "timestamp" || key == "protocolVersion") continue
            val ts = dataMap.getLong("ts_$key", globalTs)
            val value = dataMap.get<Any>(key) ?: continue
            updates.add(SettingsSyncManager.SettingUpdate(key, value, ts))
        }
        SettingsSyncManager.applyRemoteUpdates(this, updates)
    }

    private fun handlePreferenceUpdates(dataMap: com.google.android.gms.wearable.DataMap) {
        val updates = mutableListOf<SettingsSyncManager.SettingUpdate>()
        for (key in dataMap.keySet()) {
            if (key == "_trigger" || key == "protocolVersion") continue
            val item = dataMap.getDataMap(key) ?: continue
            val value = item.get<Any>("v") ?: continue
            updates.add(SettingsSyncManager.SettingUpdate(key, value, item.getLong("t")))
        }
        SettingsSyncManager.applyRemoteUpdates(this, updates)
    }

    private fun launchOmaps() {
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
