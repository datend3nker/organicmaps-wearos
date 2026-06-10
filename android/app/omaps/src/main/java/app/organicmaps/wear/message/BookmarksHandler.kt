package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.BookmarkCategoryItem
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BookmarksHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val count = buffer.int
        Log.d("BookmarksHandler", "DEBUG_BOOKMARKS_PIPELINE: Received $count categories from phone")
        
        CoroutineScope(Dispatchers.Main).launch {
            val currentState = NavigationStateHolder.state.value
            val categories = mutableListOf<BookmarkCategoryItem>()
            val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
            val isInitialized = (context.applicationContext as WearApplication).isFullyInitialized
            val localCategories = if (isInitialized) {
                try { manager.getCategories() } catch(e: Exception) { emptyList() }
            } else {
                emptyList()
            }
            val syncNeeded = mutableListOf<String>()
            val syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)

            repeat(count) {
                val id = buffer.long
                val nameLen = buffer.int
                val name = String(data, buffer.position(), nameLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + nameLen)
                
                val isVisible = buffer.get().toInt() == 1
                val bmkCount = buffer.int
                val trkCount = buffer.int
                val timestamp = if (buffer.remaining() >= 8) buffer.long else 0L
                val lastSynced = syncPrefs.getLong(name, 0L)
                
                Log.d("BookmarksHandler", "DEBUG_BOOKMARKS_PIPELINE:   - Category: '$name' (ID: $id, Bookmarks: $bmkCount, Timestamp: $timestamp, LastSynced: $lastSynced)")

                val localCat = localCategories.find { it.name.equals(name, ignoreCase = true) }
                val needsSync = localCat == null || (timestamp > 0 && timestamp > lastSynced)
                
                val oldCat = currentState.bookmarkCategories.find { it.name == name }
                val isSyncing = if (isInitialized && needsSync) {
                    syncNeeded.add(name)
                    true
                } else {
                    oldCat?.isSyncing ?: false
                }

                categories.add(BookmarkCategoryItem(id, name, isVisible, bmkCount, trkCount, isSyncing, lastModified = timestamp))
            }
            
            if (categories != currentState.bookmarkCategories) {
                Log.d("BookmarksHandler", "DEBUG_BOOKMARKS_PIPELINE: Updating state with ${categories.size} categories")
                NavigationStateHolder.update(currentState.copy(
                    bookmarkCategories = categories
                ))
            }

            if (syncNeeded.isNotEmpty() && !currentState.standaloneMode && currentState.isPhoneConnected) {
                syncNeeded.forEach { app.organicmaps.wear.WearCommandService.syncCategory(context, it) }
            }
        }
    }
}
