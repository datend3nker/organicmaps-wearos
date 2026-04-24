package app.organicmaps.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object WearCommandService {
    private const val PATH_STOP_NAVIGATION = "/navigation/stop"
    private const val PATH_SEARCH_QUERY = "/search/query"
    private const val PATH_SEARCH_SELECT = "/search/select"
    private const val PATH_SEARCH_HISTORY_REQUEST = "/search/history/request"
    private const val PATH_MAP_TILE_REQUEST = "/map/tile/request"
    private const val PATH_PING = "/ping"

    fun stopNavigation(context: Context) {
        sendMessage(context, PATH_STOP_NAVIGATION, byteArrayOf())
    }

    fun search(context: Context, query: String) {
        sendMessage(context, PATH_SEARCH_QUERY, query.toByteArray(StandardCharsets.UTF_8))
    }

    fun requestSearchHistory(context: Context) {
        sendMessage(context, PATH_SEARCH_HISTORY_REQUEST, byteArrayOf())
    }

    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + 4 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.putInt(routerType)
        buffer.put(nameBytes)
        sendMessage(context, PATH_SEARCH_SELECT, buffer.array())
    }

    fun requestMapTile(
        context: Context,
        x: Int,
        y: Int,
        zoom: Int,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double
    ) {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * 3 + (Double.SIZE_BYTES * 4))
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putInt(zoom)
        buffer.putDouble(minLat)
        buffer.putDouble(minLon)
        buffer.putDouble(maxLat)
        buffer.putDouble(maxLon)
        sendMessage(context, PATH_MAP_TILE_REQUEST, buffer.array())
    }

    fun sendPing(context: Context) {
        sendMessage(context, PATH_PING, byteArrayOf())
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
