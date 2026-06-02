package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import java.nio.ByteBuffer

class RouteBuildProgressHandler : WearMessageHandler {
    companion object {
        private const val TAG = "RouteBuildProgress"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 4) return
        val progress = buffer.int
        Log.d(TAG, "Received route build progress: $progress%")
        
        NavigationStateHolder.update { current ->
            if (progress < 0) {
                // Error case (e.g. -1 sent from phone)
                current.copy(isRouteBuilding = false, lastRouteError = 8) // Fallback to "No route found"
            } else {
                current.copy(
                    isRouteBuilding = progress < 100,
                    routeBuildProgress = progress,
                    isRouteReady = progress >= 100,
                    isRouteBuilt = progress >= 100
                )
            }
        }
    }
}
