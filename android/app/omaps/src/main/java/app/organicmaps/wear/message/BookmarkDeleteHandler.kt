package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BookmarkDeleteHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val name = String(data, StandardCharsets.UTF_8)
        Log.d("BookmarkDelete", "Deleting category '$name'")
        
        CoroutineScope(Dispatchers.Main).launch {
            val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
            val category = manager.getCategories().find { it.name.equals(name, ignoreCase = true) }
            
            app.organicmaps.wear.WatchBookmarkSyncManager.isApplyingRemoteUpdate = true
            try {
                category?.let { manager.deleteCategory(it.id) }
            } finally {
                app.organicmaps.wear.WatchBookmarkSyncManager.isApplyingRemoteUpdate = false
            }
            
            NavigationStateHolder.update { current ->
                val updated = current.bookmarkCategories.filterNot { it.name.equals(name, ignoreCase = true) }
                current.copy(bookmarkCategories = updated)
            }
        }
    }
}
