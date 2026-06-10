package app.organicmaps.wear

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import app.organicmaps.sdk.sync.BluetoothSyncConnection
import app.organicmaps.sdk.sync.SyncConnection
import app.organicmaps.sdk.sync.TcpSyncConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.sync.WearProtocolDataConverter

class BluetoothWearSyncBackend : IWearSyncBackend {
    companion object {
        private const val TAG = "BluetoothBackend"
        private val OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130")
    }

    private var activeConnection: SyncConnection? = null

    private fun sendMessage(context: Context, path: String, data: ByteArray, type: Byte = WearProtocol.TYPE_COMMAND) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = getOrConnectConnection(appContext) ?: run {
                    Log.w(TAG, "DEBUG_BT_PIPELINE: Cannot send to $path, no connection available")
                    return@launch
                }
                val out = connection.getOutputStream()
                
                val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
                val totalLen = 4 + pathBytes.size + data.size
                
                app.organicmaps.sdk.sync.WearLog.logSent("WATCH", "BLUETOOTH", path, totalLen + 6)
                
                val header = ByteBuffer.allocate(6)
                header.put(WearProtocol.PROTOCOL_VERSION)
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
                Log.e(TAG, "DEBUG_BT_PIPELINE: send failed for $path: ${e.message}")
                closeConnection()
            }
        }
    }

    override fun stop() {
        closeConnection()
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
        sendMessage(context, WearProtocol.PATH_PONG, byteArrayOf())
    }

    override fun syncPreferences(context: Context) {
        val manager = SettingsSyncManager.getInstance(context)
        val all = manager.getAllSettings()
        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferences (Full Sync) - Items: ${all.size}")
        
        val keys = all.map { it.key }
        val values = all.map { it.value }
        val timestamps = all.map { it.timestamp }
        val versions = all.map { it.version }

        val payload = WearProtocolDataConverter.encodePreferenceUpdates(keys, values, timestamps, versions)
        sendMessage(context, WearProtocol.PATH_PREFERENCES_WATCH, payload, WearProtocol.TYPE_PREFERENCES)
        manager.markAsSynced(all)
    }

    override fun syncPreferenceUpdates(context: Context, updates: List<BaseSettingsSyncManager.SettingUpdate>) {
        if (updates.isEmpty()) return
        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferenceUpdates (Buffered) - Items: ${updates.size}")

        val keys = updates.map { it.key }
        val values = updates.map { it.value }
        val timestamps = updates.map { it.timestamp }
        val versions = updates.map { it.version }

        val payload = WearProtocolDataConverter.encodePreferenceUpdates(keys, values, timestamps, versions)
        sendMessage(context, WearProtocol.PATH_PREFERENCES_UPDATES, payload, WearProtocol.TYPE_PREFERENCES_UPDATES)
        SettingsSyncManager.getInstance(context).markAsSynced(updates)
    }

    override fun requestPreferences(context: Context) {
        sendMessage(context, WearProtocol.PATH_PREFERENCES_REQUEST, byteArrayOf())
    }

    override fun syncSearchHistory(context: Context) {
        app.organicmaps.sdk.search.SearchRecents.refresh()
        val size = app.organicmaps.sdk.search.SearchRecents.getSize()
        if (size == 0) return

        val historyList = mutableListOf<String>()
        for (i in 0 until size) {
            historyList.add(app.organicmaps.sdk.search.SearchRecents.get(i))
        }
        val payload = WearProtocolDataConverter.encodeSearchHistory(historyList, 10)
        sendMessage(context, WearProtocol.PATH_SEARCH_HISTORY_SYNC, payload)
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
        val mapIdBytes = mapId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + mapIdBytes.size + 4)
        buffer.putInt(mapIdBytes.size)
        buffer.put(mapIdBytes)
        buffer.putInt(progress)
        sendMessage(context, WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS, buffer.array(), WearProtocol.TYPE_MAP_DOWNLOAD_PROGRESS)
    }

    override fun toggleTrackRecording(context: Context) {
        sendMessage(context, WearProtocol.PATH_TRACK_RECORDING_TOGGLE, byteArrayOf())
    }

    override fun requestBookmarks(context: Context) {
        sendMessage(context, WearProtocol.PATH_BOOKMARKS_REQUEST, byteArrayOf())
    }

    override fun toggleBookmarkCategory(context: Context, categoryName: String) {
        val nameBytes = categoryName.toByteArray(StandardCharsets.UTF_8)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_VISIBLE_TOGGLE, nameBytes)
    }

    override fun syncCategory(context: Context, categoryName: String) {
        val nameBytes = categoryName.toByteArray(StandardCharsets.UTF_8)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_SYNC_REQUEST, nameBytes)
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

    override fun showBookmarkOnPhone(context: Context, bmkId: Long) {
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(bmkId)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_SHOW, buffer.array())
    }

    override fun updateBookmarkOnPhone(context: Context, bmkId: Long, name: String, color: Int) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 4 + nameBytes.size + 4)
        buffer.putLong(bmkId)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putInt(color)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_UPDATE, buffer.array())
    }

    override fun sendBookmarkFile(context: Context, categoryName: String, data: ByteArray, isLast: Boolean) {
        val nameBytes = categoryName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 4 + nameBytes.size + data.size)
        buffer.put(if (isLast) 1.toByte() else 0.toByte())
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.put(data)
        sendMessage(context, WearProtocol.PATH_BOOKMARK_FILE, buffer.array(), WearProtocol.TYPE_BOOKMARK_FILE)
    }

    override fun sendBookmarksMetadata(context: Context, payload: ByteArray) {
        sendMessage(context, WearProtocol.PATH_BOOKMARKS_METADATA, payload, WearProtocol.TYPE_BOOKMARKS_METADATA)
    }

    override fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int) {
        val nameBytes = mwmName.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + nameBytes.size + 8 + 4)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putLong(offset)
        buffer.putInt(size)
        sendMessage(context, WearProtocol.PATH_VIRTUAL_MWM_REQUEST, buffer.array(), WearProtocol.TYPE_VIRTUAL_MWM_REQUEST)
    }

    override fun requestMwmMetadata(context: Context, mwmName: String) {
        sendMessage(context, WearProtocol.PATH_VIRTUAL_MWM_METADATA_REQUEST, mwmName.toByteArray(StandardCharsets.UTF_8))
    }

    override fun launchPhoneApp(context: Context) {
        Log.d(TAG, "Best effort: Phone app launch requested via Bluetooth")
    }

    override fun sendHandshake(context: Context) {
        val payload = WearProtocolDataConverter.encodeHandshake(BuildConfig.VERSION_CODE, 0.toByte())
        sendMessage(context, WearProtocol.PATH_HANDSHAKE, payload, WearProtocol.TYPE_HANDSHAKE)
    }

    override fun checkConnection(context: Context, callback: (Boolean, String?) -> Unit) {
        val conn = activeConnection ?: BluetoothWearDataListenerService.activeConnection
        val isConnected = conn?.isConnected() ?: false
        callback(isConnected, if (isConnected) "Bluetooth Device" else null)
    }

    private fun getOrConnectConnection(context: Context): SyncConnection? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (!(android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("vbox"))) {
                    Log.e(TAG, "BLUETOOTH_CONNECT permission missing")
                    return null
                }
            }
        }

        synchronized(this) {
            if (activeConnection?.isConnected() == true) return activeConnection
            
            val fromService = BluetoothWearDataListenerService.activeConnection
            if (fromService?.isConnected() == true) {
                activeConnection = fromService
                return fromService
            }

            val isEmulator = android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("vbox")
            if (isEmulator) {
                try {
                    Log.d(TAG, "Connecting to phone via TCP (Emulator)...")
                    val socket = java.net.Socket("10.0.2.2", 5610)
                    val connection = TcpSyncConnection(socket)
                    activeConnection = connection
                    return connection
                } catch (e: Exception) {
                    Log.d(TAG, "TCP connection failed: ${e.message}")
                }
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
                        val connection = BluetoothSyncConnection(socket)
                        activeConnection = connection
                        return connection
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

    private fun closeConnection() {
        synchronized(this) {
            try { activeConnection?.close() } catch (ignored: Exception) {}
            activeConnection = null
        }
    }
}
