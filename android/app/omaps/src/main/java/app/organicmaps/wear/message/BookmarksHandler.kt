package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.wear.BookmarkCategoryItem
import app.organicmaps.wear.NavigationStateHolder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BookmarksHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val count = buffer.int
        val currentState = NavigationStateHolder.state.value
        val categories = mutableListOf<BookmarkCategoryItem>()
        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
        val localCategories = manager.getCategories()
        val missingCategories = mutableListOf<String>()

        repeat(count) {
            val id = buffer.long
            val nameLen = buffer.int
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes, StandardCharsets.UTF_8)
            val isVisible = buffer.get().toInt() == 1
            val bmkCount = buffer.int
            val trkCount = buffer.int
            val timestamp = if (buffer.remaining() >= 8) buffer.long else 0L
            
            val localCat = localCategories.find { it.name.equals(name, ignoreCase = true) }
            val isSyncing = if (localCat == null) {
                missingCategories.add(name)
                true
            } else {
                // If local exists, we might still want to sync if remote is newer
                // but the prompt specifically says "sync the fiel if it does not already exist"
                false
            }

            val oldCat = currentState.bookmarkCategories.find { it.name == name }
            categories.add(BookmarkCategoryItem(id, name, isVisible, bmkCount, trkCount, oldCat?.isSyncing ?: isSyncing, lastModified = timestamp))
        }
        
        NavigationStateHolder.update(currentState.copy(
            bookmarkCategories = categories
        ))

        if (missingCategories.isNotEmpty() && !currentState.standaloneMode && currentState.isPhoneConnected) {
            missingCategories.forEach { app.organicmaps.wear.WearCommandService.syncCategory(context, it) }
        }
    }
}
