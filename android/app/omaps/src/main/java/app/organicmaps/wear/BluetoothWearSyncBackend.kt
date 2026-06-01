package app.organicmaps.wear

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

class BluetoothWearSyncBackend : IWearSyncBackend {
    companion object {
        private const val TAG = "BluetoothBackend"
        private val OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130")

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
        private const val PATH_SEARCH_HISTORY_SYNC = "/search/history/sync"
        private const val PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request"
        private const val PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request"

        private const val MSG_TYPE_COMMAND = 10.toByte()
        private const val MSG_TYPE_VIRTUAL_MWM_REQUEST = 13.toByte()
    }

    private var activeSocket: BluetoothSocket? = null

    private fun sendMessage(context: Context, path: String, data: ByteArray, type: Byte = MSG_TYPE_COMMAND) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = getOrConnectSocket(appContext) ?: return@launch
                val out = socket.outputStream
                
                val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
                val totalLen = 4 + pathBytes.size + data.size
                
                val header = ByteBuffer.allocate(5)
                header.put(type)
                header.putInt(totalLen)
                out.write(header.array())
                
                val subHeader = ByteBuffer.allocate(4)
                subHeader.putInt(pathBytes.size)
                out.write(subHeader.array())
                out.write(pathBytes)
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth send failed: ${e.message}")
                closeSocket()
            }
        }
    }

    override fun stop() {
        closeSocket()
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
        val backend = prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS"
        val poiMask = prefs.getInt("poiCategoriesMask", 0x3F)
        
        val is3dEnabled = prefs.getBoolean("pref_wear_os_3d", true)
        val is3dBuildingsEnabled = prefs.getBoolean("pref_wear_os_3d_buildings", true)
        val isAutoZoomEnabled = prefs.getBoolean("pref_wear_os_auto_zoom", true)
        val mUnits = prefs.getInt("pref_wear_os_munits", 0)
        val mapStyle = prefs.getString("pref_wear_os_map_style", "default") ?: "default"

        val avoidTolls = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Toll)
        val avoidMotorways = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Motorway)
        val avoidFerries = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Ferry)
        val avoidUnpaved = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Dirty)
        val syncNotificationsEnabled = prefs.getBoolean("pref_sync_notifications", true)
        
        val transitEnabled = prefs.getBoolean("pref_wear_os_transit", false)
        val bikingEnabled = prefs.getBoolean("pref_wear_os_biking", false)
        val hikingEnabled = prefs.getBoolean("pref_wear_os_hiking", false)
        val isolinesEnabled = prefs.getBoolean("pref_wear_os_isolines", false)
        val locationSource = prefs.getString("locationSource", "AUTO") ?: "AUTO"
        val isTrackRecording = NavigationStateHolder.state.value.isTrackRecording
        val recordingStartTime = NavigationStateHolder.state.value.trackRecordingStartTime
        
        val backendBytes = backend.toByteArray(StandardCharsets.UTF_8)
        val downloadModeBytes = downloadMode.toByteArray(StandardCharsets.UTF_8)
        val styleBytes = mapStyle.toByteArray(StandardCharsets.UTF_8)
        val locSrcBytes = locationSource.toByteArray(StandardCharsets.UTF_8)
        
        // BUFFER Format: [1:mapEnabled][1:watchLocal][1:standalone][1:autoDownload][4:modeLen][mode][4:backendLen][backend][4:poiMask][1:3d][1:3dBld][1:autoZoom][4:mUnits][4:styleLen][style][1:toll][1:mtw][1:ferry][1:dirty][1:syncNotif][1:transit][1:biking][1:hiking][1:isolines][1:recording][4:locSrcLen][locSrc][8:startTime][8:timestamp]
        val buffer = ByteBuffer.allocate(57 + downloadModeBytes.size + backendBytes.size + styleBytes.size + locSrcBytes.size)
        buffer.put((if (mapEnabled) 1 else 0).toByte())
        buffer.put((if (watchLocalMode) 1 else 0).toByte())
        buffer.put((if (standaloneMode) 1 else 0).toByte())
        buffer.put((if (autoDownload) 1 else 0).toByte())
        buffer.putInt(downloadModeBytes.size)
        buffer.put(downloadModeBytes)
        buffer.putInt(backendBytes.size)
        buffer.put(backendBytes)
        buffer.putInt(poiMask)
        
        buffer.put((if (is3dEnabled) 1 else 0).toByte())
        buffer.put((if (is3dBuildingsEnabled) 1 else 0).toByte())
        buffer.put((if (isAutoZoomEnabled) 1 else 0).toByte())
        buffer.putInt(mUnits)
        buffer.putInt(styleBytes.size)
        buffer.put(styleBytes)

        buffer.put((if (avoidTolls) 1 else 0).toByte())
        buffer.put((if (avoidMotorways) 1 else 0).toByte())
        buffer.put((if (avoidFerries) 1 else 0).toByte())
        buffer.put((if (avoidUnpaved) 1 else 0).toByte())
        buffer.put((if (syncNotificationsEnabled) 1 else 0).toByte())

        buffer.put((if (transitEnabled) 1 else 0).toByte())
        buffer.put((if (bikingEnabled) 1 else 0).toByte())
        buffer.put((if (hikingEnabled) 1 else 0).toByte())
        buffer.put((if (isolinesEnabled) 1 else 0).toByte())
        buffer.put((if (isTrackRecording) 1 else 0).toByte())

        buffer.putInt(locSrcBytes.size)
        buffer.put(locSrcBytes)

        buffer.putLong(recordingStartTime)

        val timestamp = System.currentTimeMillis()
        buffer.putLong(timestamp)
        
        // Update local interaction time to prevent ignoring phone's sync of this change
        NavigationStateHolder.update { it.copy(lastSettingsInteractionTime = timestamp) }
        
        sendMessage(context, "/preferences/watch", buffer.array())
    }

    override fun requestPreferences(context: Context) {
        sendMessage(context, PATH_PREFERENCES_REQUEST, byteArrayOf())
    }

    override fun syncSearchHistory(context: Context) {
        app.organicmaps.sdk.search.SearchRecents.refresh()
        val size = app.organicmaps.sdk.search.SearchRecents.getSize()
        if (size == 0) return

        val historyList = mutableListOf<String>()
        var totalSize = 4
        for (i in 0 until size) {
            val s = app.organicmaps.sdk.search.SearchRecents.get(i)
            historyList.add(s)
            totalSize += 4 + s.toByteArray(StandardCharsets.UTF_8).size
        }

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(size)
        for (s in historyList) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }
        sendMessage(context, PATH_SEARCH_HISTORY_SYNC, buffer.array())
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

    override fun sendBackendSwitch(context: Context, newBackend: String) {
        sendMessage(context, PATH_BACKEND_SWITCH, newBackend.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendMapDownloadRequest(context: Context, mapId: String) {
        sendMessage(context, PATH_MAP_DOWNLOAD_REQUEST, mapId.toByteArray(StandardCharsets.UTF_8))
    }

    override fun sendMapProgress(context: Context, mapId: String, progress: Int) {
        val mapIdBytes = mapId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + mapIdBytes.size + 4)
        buffer.putInt(mapIdBytes.size)
        buffer.put(mapIdBytes)
        buffer.putInt(progress)
        sendMessage(context, "/map/download/progress", buffer.array(), 7.toByte())
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
        sendMessage(context, PATH_VIRTUAL_MWM_REQUEST, buffer.array(), MSG_TYPE_VIRTUAL_MWM_REQUEST)
    }

    override fun requestMwmMetadata(context: Context, mwmName: String) {
        sendMessage(context, PATH_VIRTUAL_MWM_METADATA_REQUEST, mwmName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun launchPhoneApp(context: Context) {
        // Bluetooth (Standalone OSS) can't easily wake up a dead process.
        // Best effort: Log and notify user if possible.
        Log.d(TAG, "Best effort: Phone app launch requested via Bluetooth")
    }

    override fun checkConnection(context: Context, callback: (Boolean) -> Unit) {
        callback(true) // For now, assume Bluetooth is "ready"
    }

    private fun getOrConnectSocket(context: Context): BluetoothSocket? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing")
                return null
            }
        }

        synchronized(this) {
            if (activeSocket?.isConnected == true) return activeSocket
            
            val fromService = BluetoothWearDataListenerService.activeSocket
            if (fromService?.isConnected == true) {
                activeSocket = fromService
                return fromService
            }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return null
            if (!adapter.isEnabled) return null
            
            try {
                val pairedDevices = adapter.bondedDevices ?: return null
                for (device in pairedDevices) {
                    try {
                        val socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID)
                        socket.connect()
                        activeSocket = socket
                        return socket
                    } catch (ignored: Exception) {}
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth permission missing", e)
            }
            return null
        }
    }

    private fun closeSocket() {
        synchronized(this) {
            try { activeSocket?.close() } catch (ignored: Exception) {}
            activeSocket = null
        }
    }
}
