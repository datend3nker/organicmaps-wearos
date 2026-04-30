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
        private const val PATH_MAP_TILE_REQUEST = "/map/tile/request"
        private const val PATH_PING = "/ping"

        private const val MSG_TYPE_COMMAND = 10.toByte()
    }

    private var activeSocket: BluetoothSocket? = null

    override fun stopNavigation(context: Context) {
        sendMessage(context, PATH_STOP_NAVIGATION, byteArrayOf())
    }

    override fun search(context: Context, query: String) {
        sendMessage(context, PATH_SEARCH_QUERY, query.toByteArray(StandardCharsets.UTF_8))
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
        sendMessage(context, PATH_SEARCH_SELECT, buffer.array())
    }

    override fun requestMapTile(context: Context, requestId: Long, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES + (Double.SIZE_BYTES * 4))
        buffer.putLong(requestId)
        buffer.putDouble(minLat)
        buffer.putDouble(minLon)
        buffer.putDouble(maxLat)
        buffer.putDouble(maxLon)
        sendMessage(context, PATH_MAP_TILE_REQUEST, buffer.array())
    }

    override fun sendPing(context: Context) {
        sendMessage(context, PATH_PING, byteArrayOf())
    }

    override fun syncPreferences(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val forceOffline = prefs.getBoolean("forceWatchOfflineMaps", false)
        val backend = prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS"
        
        val backendBytes = backend.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 4 + backendBytes.size)
        buffer.put((if (forceOffline) 1 else 0).toByte())
        buffer.putInt(backendBytes.size)
        buffer.put(backendBytes)
        
        // Use a new message type for watch -> phone preferences
        sendMessage(context, "/preferences/watch", buffer.array())
    }

    private fun sendMessage(context: Context, path: String, data: ByteArray) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = getOrConnectSocket(appContext) ?: return@launch
                val out = socket.outputStream
                
                val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
                val totalLen = 4 + pathBytes.size + data.size
                
                val header = ByteBuffer.allocate(5)
                header.put(MSG_TYPE_COMMAND)
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

    private fun getOrConnectSocket(context: Context): BluetoothSocket? {
        synchronized(this) {
            if (activeSocket?.isConnected == true) return activeSocket
            
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
