package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BookmarkRenameHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 4) return
        val oldNameLen = buffer.int
        if (buffer.remaining() < oldNameLen) return
        val oldNameBytes = ByteArray(oldNameLen)
        buffer.get(oldNameBytes)
        val oldName = String(oldNameBytes, StandardCharsets.UTF_8)
        
        if (buffer.remaining() < 4) return
        val newNameLen = buffer.int
        if (buffer.remaining() < newNameLen) return
        val newNameBytes = ByteArray(newNameLen)
        buffer.get(newNameBytes)
        val newName = String(newNameBytes, StandardCharsets.UTF_8)
        
        Log.d("BookmarkRename", "Renaming category from '$oldName' to '$newName'")
        
        CoroutineScope(Dispatchers.Main).launch {
            val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
            val category = manager.getCategories().find { it.name.equals(oldName, ignoreCase = true) }
            
            app.organicmaps.wear.WatchBookmarkSyncManager.isApplyingRemoteUpdate = true
            try {
                category?.setName(newName)
            } finally {
                app.organicmaps.wear.WatchBookmarkSyncManager.isApplyingRemoteUpdate = false
            }
            
            NavigationStateHolder.update { current ->
                val updated = current.bookmarkCategories.map {
                    if (it.name.equals(oldName, ignoreCase = true)) it.copy(name = newName) else it
                }
                current.copy(bookmarkCategories = updated)
            }
        }
    }
}
