package app.organicmaps.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class GmsWearSyncBackend : IWearSyncBackend {
    companion object {
        private const val PATH_STOP_NAVIGATION = "/navigation/stop"
        private const val PATH_SEARCH_QUERY = "/search/query"
        private const val PATH_SEARCH_SELECT = "/search/select"
        private const val PATH_SEARCH_HISTORY_REQUEST = "/search/history/request"
        private const val PATH_MAP_TILE_REQUEST = "/map/tile/request"
        private const val PATH_PING = "/ping"
        private const val PATH_PREFERENCES_REQUEST = "/preferences/request"
    }

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

    override fun requestMapTile(context: Context, requestId: Long, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, routerType: Int) {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES + (Double.SIZE_BYTES * 4) + Int.SIZE_BYTES)
        buffer.putLong(requestId)
        buffer.putDouble(minLat)
        buffer.putDouble(minLon)
        buffer.putDouble(maxLat)
        buffer.putDouble(maxLon)
        buffer.putInt(routerType)
        sendMessage(context, PATH_MAP_TILE_REQUEST, buffer.array())
    }

    override fun sendPing(context: Context) {
        sendMessage(context, PATH_PING, byteArrayOf())
    }

    override fun syncPreferences(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val forceOffline = prefs.getBoolean("forceWatchLocalMode", false)
        val watchLocalMode = prefs.getBoolean("watchLocalMode", false)
        val standaloneMode = prefs.getBoolean("disconnectFromPhone", false)
        val backend = prefs.getString("pref_wear_os_backend", "GMS")
        
        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/preferences/watch")
        val map = putDataMapReq.dataMap
        map.putBoolean("mapEnabled", mapEnabled)
        map.putBoolean("forceWatchLocalMode", forceOffline)
        map.putBoolean("watchLocalMode", watchLocalMode)
        map.putBoolean("standaloneMode", standaloneMode)
        map.putString("backend", backend ?: "GMS")
        
        val putDataReq = putDataMapReq.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(putDataReq)
    }

    override fun requestPreferences(context: Context) {
        sendMessage(context, PATH_PREFERENCES_REQUEST, byteArrayOf())
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

    private fun sendMessage(context: Context, path: String, data: ByteArray) {
        val messageClient = Wearable.getMessageClient(context)
        val nodeClient = Wearable.getNodeClient(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    messageClient.sendMessage(node.id, path, data).await()
                }
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
