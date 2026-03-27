package app.organicmaps.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object WearCommandService {
    private const val PATH_STOP_NAVIGATION = "/navigation/stop"

    fun stopNavigation(context: Context) {
        val messageClient = Wearable.getMessageClient(context)
        val nodeClient = Wearable.getNodeClient(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    messageClient.sendMessage(node.id, PATH_STOP_NAVIGATION, byteArrayOf()).await()
                }
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
