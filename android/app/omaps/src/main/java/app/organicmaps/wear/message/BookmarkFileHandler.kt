package app.organicmaps.wear.message

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.organicmaps.wear.BookmarkCategoryItem
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearMapDownloader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BookmarkFileHandler(
    private val bookmarkOutputStreams: MutableMap<String, FileOutputStream>
) : WearMessageHandler {
    companion object {
        private const val TAG = "BookmarkFileHandler"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 5) return
        val isLast = buffer.get().toInt() == 1
        val nameLen = buffer.int
        if (buffer.remaining() < nameLen) return
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val categoryName = String(nameBytes, StandardCharsets.UTF_8)
        
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Received bookmark chunk for $categoryName. Size: ${chunk.size}, isLast: $isLast")
        saveBookmarkChunk(context, categoryName, chunk, isLast)
    }

    private fun saveBookmarkChunk(context: Context, categoryName: String, data: ByteArray, isLast: Boolean) {
        try {
            val fileName = categoryName.replace("[\\\\/:*?\"<>|]", "_") + ".kmz"
            val fos = bookmarkOutputStreams.getOrPut(categoryName) {
                val file = File(context.cacheDir, fileName + ".tmp")
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Starting new bookmark file reception: $fileName")
                WearMapDownloader.setStreamingMap("Bookmarks: $categoryName")
                FileOutputStream(file)
            }
            fos.write(data)
            if (isLast) {
                fos.close()
                bookmarkOutputStreams.remove(categoryName)
                val tmpFile = File(context.cacheDir, fileName + ".tmp")
                val finalFile = File(context.cacheDir, fileName)
                
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Finalizing bookmark file: $fileName (Total size: ${tmpFile.length()} bytes)")
                
                // Use more robust move/rename
                if (finalFile.exists()) finalFile.delete()
                val renamed = tmpFile.renameTo(finalFile)
                if (!renamed) {
                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: Failed to rename tmp file, attempting manual copy")
                    try {
                        tmpFile.inputStream().use { input ->
                            finalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tmpFile.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG_WEAR_PIPELINE: Manual copy failed: ${e.message}")
                    }
                }

                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Successfully received bookmark file: $fileName")
                WearMapDownloader.onDownloadCompleted()
                
                Handler(Looper.getMainLooper()).post {
                    try {
                        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
                        
                        // Robust category removal - try by name
                        val existingByName = manager.getCategories().find { it.name.equals(categoryName, ignoreCase = true) }
                        if (existingByName != null) {
                            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Deleting existing category '$categoryName' (ID: ${existingByName.id}) before importing update")
                            manager.deleteCategory(existingByName.id)
                        }

                        if (finalFile.exists()) {
                            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Loading bookmarks from file: ${finalFile.absolutePath}")
                            manager.loadBookmarksFile(finalFile.absolutePath, true)
                            Toast.makeText(context, "Bookmarks synchronized", Toast.LENGTH_SHORT).show()
                        }
                        
                        NavigationStateHolder.update { current ->
                             val updatedCats = manager.getCategories().map { cat ->
                                 val oldCat = current.bookmarkCategories.find { it.name.equals(cat.name, ignoreCase = true) }
                                 BookmarkCategoryItem(
                                     cat.id, cat.name, cat.isVisible, cat.bookmarksCount, cat.tracksCount,
                                     isSyncing = false,
                                     remoteId = oldCat?.remoteId ?: 0L,
                                     lastModified = oldCat?.lastModified ?: 0L
                                 )
                             }
                             
                             val finalCats = current.bookmarkCategories.toMutableList()
                             updatedCats.forEach { newCat ->
                                 val idx = finalCats.indexOfFirst { it.name.equals(newCat.name, ignoreCase = true) }
                                 if (idx != -1) finalCats[idx] = newCat
                                 else finalCats.add(newCat)
                             }
                             
                             // Clear syncing flag for the one we just processed
                             val processedIdx = finalCats.indexOfFirst { it.name.equals(categoryName, ignoreCase = true) }
                             if (processedIdx != -1) {
                                 finalCats[processedIdx] = finalCats[processedIdx].copy(isSyncing = false)
                             }

                             current.copy(bookmarkCategories = finalCats.distinctBy { it.name })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to load imported bookmarks", e)
                        NavigationStateHolder.update { current ->
                            val updated = current.bookmarkCategories.map {
                                if (it.name.equals(categoryName, ignoreCase = true)) it.copy(isSyncing = false) else it
                            }
                            current.copy(bookmarkCategories = updated)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to save bookmark chunk: ${e.message}")
            bookmarkOutputStreams.remove(categoryName)?.close()
        }
    }
}
