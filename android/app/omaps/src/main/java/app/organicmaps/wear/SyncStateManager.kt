package app.organicmaps.wear

import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object SyncStateManager {
    val mapOutputStreams = ConcurrentHashMap<String, FileOutputStream>()
    val bookmarkOutputStreams = ConcurrentHashMap<String, FileOutputStream>()
}
