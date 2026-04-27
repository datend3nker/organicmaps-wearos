package app.organicmaps.wear

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * F-Droid implementation of WearCommandService using standard Bluetooth RFCOMM Sockets.
 */
object WearCommandService {
    private const val TAG = "WearCommandServiceFdroid"
    private val OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130")

    private const val PATH_STOP_NAVIGATION = "/navigation/stop"
    private const val PATH_SEARCH_QUERY = "/search/query"
    private const val PATH_SEARCH_SELECT = "/search/select"
    private const val PATH_SEARCH_HISTORY_REQUEST = "/search/history/request"
    private const val PATH_MAP_TILE_REQUEST = "/map/tile/request"
    private const val PATH_PING = "/ping"

    private const val MSG_TYPE_COMMAND = 10.toByte()

    private var activeSocket: BluetoothSocket? = null

    fun stopNavigation(context: Context) {
        sendMessage(PATH_STOP_NAVIGATION, byteArrayOf())
    }

    fun search(context: Context, query: String) {
        sendMessage(PATH_SEARCH_QUERY, query.toByteArray(StandardCharsets.UTF_8))
    }

    fun requestSearchHistory(context: Context) {
        sendMessage(PATH_SEARCH_HISTORY_REQUEST, byteArrayOf())
    }

    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + 4 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.putInt(routerType)
        buffer.put(nameBytes)
        sendMessage(PATH_SEARCH_SELECT, buffer.array())
    }

    fun requestMapTile(context: Context, x: Int, y: Int, zoom: Int, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * 3 + (Double.SIZE_BYTES * 4))
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putInt(zoom)
        buffer.putDouble(minLat)
        buffer.putDouble(minLon)
        buffer.putDouble(maxLat)
        buffer.putDouble(maxLon)
        sendMessage(PATH_MAP_TILE_REQUEST, buffer.array())
    }

    fun sendPing(context: Context) {
        sendMessage(PATH_PING, byteArrayOf())
    }

    private fun sendMessage(path: String, data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = getOrConnectSocket() ?: return@launch
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

    private fun getOrConnectSocket(): BluetoothSocket? {
        synchronized(this) {
            if (activeSocket?.isConnected == true) return activeSocket
            
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!adapter.isEnabled) return null
            
            val pairedDevices = adapter.bondedDevices ?: return null
            for (device in pairedDevices) {
                try {
                    val socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID)
                    socket.connect()
                    activeSocket = socket
                    return socket
                } catch (ignored: Exception) {}
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
