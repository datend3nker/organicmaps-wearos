package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.SyncStateManager
import java.nio.ByteBuffer

class WearMessageDispatcher {
    companion object {
        private const val TAG = "WearMessageDispatcher"
        
        // Mapping from GMS Paths to Bluetooth Types
        private val PATH_TO_TYPE = mapOf(
            "/navigation/status" to 1.toByte(),
            "/search/results" to 2.toByte(),
            "/search/history" to 3.toByte(),
            "/preferences/watch" to 4.toByte(),
            "/preferences/updates" to 19.toByte(),
            "/map/download/request" to 5.toByte(),
            "/map/download/progress" to 7.toByte(),
            "/navigation/route_build_progress" to 16.toByte(),
            "/track/recording" to 8.toByte(),
            "/bookmarks" to 9.toByte(),
            "/bookmark/file" to 12.toByte(),
            "/bookmark/rename" to 17.toByte(),
            "/bookmark/delete" to 18.toByte(),
            "/virtual_mwm/data" to 14.toByte(),
            "/virtual_mwm/mount" to 15.toByte()
        )
    }

    private val handlers = mapOf<Byte, WearMessageHandler>(
        1.toByte() to NavStatusHandler(),
        2.toByte() to SearchResultsHandler(),
        3.toByte() to SearchHistoryHandler(),
        4.toByte() to PreferenceHandler(),
        19.toByte() to PreferenceHandler(),
        5.toByte() to MapDownloadHandler(),
        7.toByte() to MapProgressHandler(),
        16.toByte() to RouteBuildProgressHandler(),
        8.toByte() to TrackRecordingHandler(),
        9.toByte() to BookmarksHandler(),
        10.toByte() to CommandHandler(),
        11.toByte() to MapChunkHandler(SyncStateManager.mapOutputStreams),
        12.toByte() to BookmarkFileHandler(SyncStateManager.bookmarkOutputStreams),
        17.toByte() to BookmarkRenameHandler(),
        18.toByte() to BookmarkDeleteHandler(),
        14.toByte() to VirtualMwmDataHandler(),
        15.toByte() to VirtualMwmMountHandler()
    )

    fun dispatch(type: Byte, payload: ByteArray, context: Context) {
        val handler = handlers[type]
        if (handler != null) {
            try {
                val buffer = ByteBuffer.wrap(payload)
                handler.handle(buffer, payload, context)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message type $type: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "No handler for message type $type")
        }
    }

    fun dispatch(path: String, payload: ByteArray, context: Context) {
        val type = PATH_TO_TYPE[path]
        if (type != null) {
            dispatch(type, payload, context)
        } else {
            // Some paths might need special handling or are handled by CommandHandler fallback
            // But usually CommandHandler is for Bluetooth wrapping paths.
            // For GMS, we can handle them directly if we add them to the map.
            Log.d(TAG, "Unknown path $path, checking fallback")
        }
    }
}
