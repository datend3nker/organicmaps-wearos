package app.organicmaps.wear

import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object SyncStateManager {
    @Volatile
    var localNodeId: String? = null
    
    @Volatile
    var activePeerId: String? = null

    val bookmarkOutputStreams = ConcurrentHashMap<String, FileOutputStream>()
    val mapOutputStreams = ConcurrentHashMap<String, FileOutputStream>()
}
