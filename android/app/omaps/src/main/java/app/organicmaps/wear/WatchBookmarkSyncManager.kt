package app.organicmaps.wear

import android.content.Context
import android.util.Log
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult
import app.organicmaps.sdk.bookmarks.data.KmlFileType
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object WatchBookmarkSyncManager {
    private const val TAG = "WatchBookmarkSync"
    private const val PREFS_NAME = "bookmark_sync_state"
    
    private val scope = CoroutineScope(Dispatchers.Main)
    
    @Volatile
    var isApplyingRemoteUpdate = false

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onLocalBookmarksChanged(context: Context, isUserAction: Boolean = true) {
        if (isApplyingRemoteUpdate && isUserAction) return
        
        val now = System.currentTimeMillis()
        val manager = BookmarkManager.INSTANCE
        val categories = manager.getCategories()
        val prefs = getPrefs(context)
        
        var anyChanged = false
        val editor = prefs.edit()
        
        for (cat in categories) {
            val name = cat.name
            val bCount = cat.bookmarksCount
            val tCount = cat.tracksCount
            
            val lastB = prefs.getInt("last_count_b_$name", -1)
            val lastT = prefs.getInt("last_count_t_$name", -1)
            
            if (bCount != lastB || tCount != lastT) {
                if (isUserAction) {
                    editor.putLong("last_local_edit_$name", now)
                }
                editor.putInt("last_count_b_$name", bCount)
                editor.putInt("last_count_t_$name", tCount)
                anyChanged = true
            }
        }
        
        if (anyChanged) {
            editor.apply()
        }
    }

    fun requestSync(context: Context) {
        sendMetadata(context)
    }

    private fun sendMetadata(context: Context) {
        val manager = BookmarkManager.INSTANCE
        val categories = manager.getCategories()
        val prefs = getPrefs(context)
        
        val buffer = ByteBuffer.allocate(4 + categories.size * 256) 
        buffer.putInt(categories.size)
        
        for (cat in categories) {
            val nameBytes = cat.name.toByteArray(StandardCharsets.UTF_8)
            buffer.putInt(nameBytes.size)
            buffer.put(nameBytes)
            buffer.putInt(cat.bookmarksCount)
            buffer.putInt(cat.tracksCount)
            buffer.putLong(prefs.getLong("last_local_edit_${cat.name}", 0))
            buffer.putLong(prefs.getLong("last_synced_${cat.name}", 0))
        }
        
        val payload = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(payload)
        
        Log.d(TAG, "Sending bookmarks metadata to phone. Categories: ${categories.size}")
        WearCommandService.sendBookmarksMetadata(context, payload)
    }

    fun handleIncomingMetadata(context: Context, payload: ByteArray) {
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.remaining() < 4) return
        val count = buffer.int
        
        val manager = BookmarkManager.INSTANCE
        val localCategories = manager.getCategories()
        val prefs = getPrefs(context)

        for (i in 0 until count) {
            val nameLen = buffer.int
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes, StandardCharsets.UTF_8)
            buffer.int // bmkCount
            buffer.int // trkCount
            val remoteLastEdit = buffer.long
            val remoteLastSynced = buffer.long
            
            val localLastEdit = prefs.getLong("last_local_edit_$name", 0)
            val localLastSynced = prefs.getLong("last_synced_$name", 0)
            
            val localChanged = localLastEdit > localLastSynced
            val remoteChanged = remoteLastEdit > remoteLastSynced
            
            if (localChanged && remoteChanged) {
                Log.i(TAG, "Conflict detected for $name. Waiting for phone to resolve.")
            } else if (remoteChanged) {
                Log.d(TAG, "Remote has updates for $name. Requesting file from phone.")
                WearCommandService.syncCategory(context, name)
            } else if (localChanged) {
                Log.d(TAG, "Local has updates for $name. Preparing file for phone.")
                val cat = localCategories.find { it.name == name }
                if (cat != null) {
                    manager.prepareCategoriesForSharing(longArrayOf(cat.id), KmlFileType.Text)
                }
            }
        }
    }

    val sharingListener = BookmarkManager.BookmarksSharingListener { result ->
        if (result.code == BookmarkSharingResult.SUCCESS) {
            val path = result.sharingPath
            val catIds = result.categoriesIds
            if (catIds == null || catIds.isEmpty()) return@BookmarksSharingListener
            
            val manager = BookmarkManager.INSTANCE
            val cat = manager.getCategoryById(catIds[0]) ?: return@BookmarksSharingListener
            val catName = cat.name
            
            scope.launch(Dispatchers.IO) {
                val file = File(path)
                val length = file.length()
                var sent = 0L
                Log.i(TAG, "Sending watch bookmark file: $catName ($length bytes)")
                
                val context = WearApplication.instance
                try {
                    FileInputStream(file).use { fis ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            sent += read
                            val isLast = sent >= length
                            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                            WearCommandService.sendBookmarkFile(context, catName, chunk, isLast)
                            
                            if (isLast) {
                                withContext(Dispatchers.Main) {
                                    getPrefs(context).edit().putLong("last_synced_$catName", System.currentTimeMillis()).apply()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send bookmark file", e)
                }
            }
        }
    }
}
