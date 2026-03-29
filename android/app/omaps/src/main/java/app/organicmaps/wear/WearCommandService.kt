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
