package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BookmarkDeleteHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val name = String(data, StandardCharsets.UTF_8)
        Log.d("BookmarkDelete", "Deleting category '$name'")
        
        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
        val category = manager.getCategories().find { it.name.equals(name, ignoreCase = true) }
        category?.let { manager.deleteCategory(it.id) }
        
        NavigationStateHolder.update { current ->
            val updated = current.bookmarkCategories.filterNot { it.name.equals(name, ignoreCase = true) }
            current.copy(bookmarkCategories = updated)
        }
    }
}
