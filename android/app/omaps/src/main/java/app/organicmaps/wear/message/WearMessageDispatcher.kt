package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.wear.SyncStateManager
import java.nio.ByteBuffer

class WearMessageDispatcher {
    companion object {
        private const val TAG = "WearMessageDispatcher"
    }

    private val handlers = mapOf<Byte, WearMessageHandler>(
        WearProtocol.TYPE_NAV_STATUS to NavStatusHandler(),
        WearProtocol.TYPE_SEARCH_RESULTS to SearchResultsHandler(),
        WearProtocol.TYPE_SEARCH_HISTORY to SearchHistoryHandler(),
        WearProtocol.TYPE_PREFERENCES to PreferenceHandler(),
        WearProtocol.TYPE_PREFERENCES_UPDATES to PreferenceHandler(),
        WearProtocol.TYPE_MAP_DOWNLOAD_REQUEST to MapDownloadHandler(),
        WearProtocol.TYPE_MAP_DOWNLOAD_PROGRESS to MapProgressHandler(),
        WearProtocol.TYPE_ROUTE_BUILD_PROGRESS to RouteBuildProgressHandler(),
        WearProtocol.TYPE_TRACK_RECORDING to TrackRecordingHandler(),
        WearProtocol.TYPE_BOOKMARKS to BookmarksHandler(),
        WearProtocol.TYPE_COMMAND to CommandHandler(),
        WearProtocol.TYPE_MAP_CHUNK to MapChunkHandler(),
        WearProtocol.TYPE_BOOKMARK_FILE to BookmarkFileHandler(SyncStateManager.bookmarkOutputStreams),
        WearProtocol.TYPE_MAP_PHONE_DOWNLOADED to DownloadedMapsHandler(),
        WearProtocol.TYPE_BOOKMARK_RENAME to BookmarkRenameHandler(),
        WearProtocol.TYPE_BOOKMARK_DELETE to BookmarkDeleteHandler(),
        WearProtocol.TYPE_VIRTUAL_MWM_DATA to VirtualMwmDataHandler(),
        WearProtocol.TYPE_VIRTUAL_MWM_MOUNT to VirtualMwmMountHandler()
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
        val type = WearProtocol.getMessageType(path)
        dispatch(type, payload, context)
    }
}
