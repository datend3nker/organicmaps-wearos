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
            repeat(count) {
                val id = buffer.long
                val nameLen = buffer.int
                val name = String(data, buffer.position(), nameLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + nameLen)
                
                val isVisible = buffer.get().toInt() == 1
                val bmkCount = buffer.int
                val trkCount = buffer.int
                if (buffer.remaining() >= 8) buffer.long // reserved timestamp field, unused

                Log.d("BookmarksHandler", "DEBUG_BOOKMARKS_PIPELINE:   - Category: '$name' (ID: $id, Bookmarks: $bmkCount)")

                val localCat = localCategories.find { it.name.equals(name, ignoreCase = true) }
                val oldCat = currentState.bookmarkCategories.find { it.name == name }
                val isSyncing = oldCat?.isSyncing ?: false

                // Show the larger of the phone's count and the watch's local count so neither
                // direction is hidden: a bookmark added on the watch (phone still reports 0) stays
                // visible instead of being overwritten back to 0, and phone-only bookmarks not yet
                // imported on the watch still show (and drive the sync + detail "syncing" state).
                val displayBmk = maxOf(bmkCount, localCat?.bookmarksCount ?: 0)
                val displayTrk = maxOf(trkCount, localCat?.tracksCount ?: 0)
                categories.add(BookmarkCategoryItem(id, name, isVisible, displayBmk, displayTrk, isSyncing))
            }

            if (categories != currentState.bookmarkCategories) {
                Log.d("BookmarksHandler", "DEBUG_BOOKMARKS_PIPELINE: Updating state with ${categories.size} categories")
                NavigationStateHolder.update(currentState.copy(
                    bookmarkCategories = categories
                ))
            }
        }
    }
}
