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
        private const val PATH_BOOKMARK_RENAME = "/bookmark/rename"
        private const val PATH_BOOKMARK_DELETE = "/bookmark/delete"
        private const val PATH_BOOKMARKS_REQUEST = "/bookmarks/request"
        private const val PATH_BOOKMARK_SHOW = "/bookmark/show"
        private const val PATH_BOOKMARK_UPDATE = "/bookmark/update"
        private const val PATH_SEARCH_HISTORY_SYNC = "/search/history/sync"
        private const val PATH_PREFERENCES_UPDATES = "/preferences/updates"
        private const val PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request"
        private const val PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request"

        private const val MSG_TYPE_COMMAND = 10.toByte()
        private const val MSG_TYPE_PREFERENCES = 4.toByte()
        private const val MSG_TYPE_PREFERENCES_UPDATES = 19.toByte()
        private const val MSG_TYPE_VIRTUAL_MWM_REQUEST = 13.toByte()
    }

    private var activeSocket: BluetoothSocket? = null

    private fun sendMessage(context: Context, path: String, data: ByteArray, type: Byte = MSG_TYPE_COMMAND) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = getOrConnectSocket(appContext) ?: run {
                    Log.w(TAG, "DEBUG_BT_PIPELINE: Cannot send to $path, no Bluetooth socket available")
                    return@launch
                }
                val out = socket.outputStream
                
                val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
                val totalLen = 4 + pathBytes.size + data.size
                
                Log.d(TAG, "DEBUG_BT_PIPELINE: sendMessage to $path (type=$type, payload=${data.size} bytes). Total packet size=${totalLen + 6}")
                
                val header = ByteBuffer.allocate(6)
                header.put(IWearSyncBackend.PROTOCOL_VERSION)
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
                Log.e(TAG, "DEBUG_BT_PIPELINE: Bluetooth send failed for $path: ${e.message}")
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
        val all = SettingsSyncManager.getAllSettings(context)
        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferences (Full Sync) - Items: ${all.size}")
        
        // BUFFER Format: [4:count] { [4:keyLen][key][1:type][4:valLen][val][8:timestamp] }
        var totalSize = 4
        val keyBytesList = mutableListOf<ByteArray>()
        val valBytesList = mutableListOf<ByteArray>()
        
        for (update in all) {
            val kb = update.key.toByteArray(StandardCharsets.UTF_8)
            keyBytesList.add(kb)
            val vb = serializeValue(update.value)
            valBytesList.add(vb)
            totalSize += 4 + kb.size + 1 + 4 + vb.size + 8
        }

        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferences - Calculated Buffer Size: $totalSize bytes")
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(all.size)
        for (i in all.indices) {
            val update = all[i]
            val kb = keyBytesList[i]
            val vb = valBytesList[i]
            
            buffer.putInt(kb.size)
            buffer.put(kb)
            buffer.put(getValueType(update.value))
            buffer.putInt(vb.size)
            buffer.put(vb)
            buffer.putLong(update.timestamp)
        }
        
        sendMessage(context, "/preferences/watch", buffer.array(), MSG_TYPE_PREFERENCES)
        SettingsSyncManager.markAsSynced(context, all)
    }

    override fun syncPreferenceUpdates(context: Context, updates: List<SettingsSyncManager.SettingUpdate>) {
        if (updates.isEmpty()) return
        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferenceUpdates (Buffered) - Items: ${updates.size}")

        var totalSize = 4
        val keyBytesList = mutableListOf<ByteArray>()
        val valBytesList = mutableListOf<ByteArray>()
        
        for (update in updates) {
            val kb = update.key.toByteArray(StandardCharsets.UTF_8)
            keyBytesList.add(kb)
            val vb = serializeValue(update.value)
            valBytesList.add(vb)
            totalSize += 4 + kb.size + 1 + 4 + vb.size + 8
        }

        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferenceUpdates - Calculated Buffer Size: $totalSize bytes")
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(updates.size)
        for (i in updates.indices) {
            val update = updates[i]
            Log.d(TAG, "DEBUG_BT_PIPELINE: Buffering setting for transmission: ${update.key} = ${update.value}")
            val kb = keyBytesList[i]
            val vb = valBytesList[i]
            
            buffer.putInt(kb.size)
            buffer.put(kb)
            buffer.put(getValueType(update.value))
            buffer.putInt(vb.size)
            buffer.put(vb)
            buffer.putLong(update.timestamp)
        }
        
        sendMessage(context, PATH_PREFERENCES_UPDATES, buffer.array(), MSG_TYPE_PREFERENCES_UPDATES)
        SettingsSyncManager.markAsSynced(context, updates)
    }

    private fun getValueType(v: Any): Byte {
        return when (v) {
            is Boolean -> 1
            is String -> 2
            is Int -> 3
            is Long -> 4
            else -> 0
        }
    }

    private fun serializeValue(v: Any): ByteArray {
        return when (v) {
            is Boolean -> byteArrayOf(if (v) 1 else 0)
            is String -> v.toByteArray(StandardCharsets.UTF_8)
            is Int -> ByteBuffer.allocate(4).putInt(v).array()
            is Long -> ByteBuffer.allocate(8).putLong(v).array()
            else -> byteArrayOf()
        }
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

    override fun toggleBookmarkCategory(context: Context, categoryName: String) {
        val nameBytes = categoryName.toByteArray(StandardCharsets.UTF_8)
        sendMessage(context, PATH_BOOKMARK_VISIBLE_TOGGLE, nameBytes)
    }

    override fun syncCategory(context: Context, categoryName: String) {
        val nameBytes = categoryName.toByteArray(StandardCharsets.UTF_8)
        sendMessage(context, PATH_BOOKMARK_SYNC_REQUEST, nameBytes)
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
                    val socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID)
                    try {
                        socket.connect()
                        activeSocket = socket
                        return socket
                    } catch (ignored: Exception) {
                        try { socket.close() } catch (e: Exception) {}
                    }
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
