package app.organicmaps.wear.message

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.organicmaps.wear.BookmarkCategoryItem
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.UiEvent
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
        val flags = buffer.get().toInt()
        val isLast = (flags and 1) != 0
        val remoteMerge = (flags and 2) != 0
        val nameLen = buffer.int
        if (buffer.remaining() < nameLen) return
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val categoryName = String(nameBytes, StandardCharsets.UTF_8)
        
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Received bookmark chunk for $categoryName. Size: ${chunk.size}, isLast: $isLast, merge: $remoteMerge")
        saveBookmarkChunk(context, categoryName, chunk, isLast, remoteMerge)
    }

    private fun saveBookmarkChunk(context: Context, categoryName: String, data: ByteArray, isLast: Boolean, shouldMerge: Boolean) {
        try {
            if (data.isNotEmpty()) {
                val hexStr = data.take(minOf(data.size, 16)).joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Chunk for $categoryName. Header bytes (hex): $hexStr")
            }

            val safeName = categoryName.replace("[\\\\/:*?\"<>|]", "_")
            val tmpFile = File(context.cacheDir, "$safeName.part")
            val fos = bookmarkOutputStreams.getOrPut(categoryName) {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Starting new bookmark file reception: $safeName")
                WearMapDownloader.setStreamingMap("Bookmarks: $categoryName")
                FileOutputStream(tmpFile)
            }
            fos.write(data)
            if (isLast) {
                fos.close()
                bookmarkOutputStreams.remove(categoryName)

                // The sender exports via prepareCategoriesForSharing, which ALWAYS zips the KML into
                // a KMZ. OM's loader picks the parser by file extension, so a ZIP saved as ".kml"
                // fails to parse and the import never sticks (causing a re-sync storm). Sniff the
                // magic and use ".kmz" for ZIP payloads so OM unzips and imports correctly.
                val isZip = tmpFile.inputStream().use { input ->
                    val sig = ByteArray(4)
                    input.read(sig) == 4 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte() &&
                        sig[2] == 0x03.toByte() && sig[3] == 0x04.toByte()
                }
                val fileName = safeName + if (isZip) ".kmz" else ".kml"
                val finalFile = File(context.cacheDir, fileName)

                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Finalizing bookmark file: $fileName (zip=$isZip, Total size: ${tmpFile.length()} bytes)")

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
                        val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                        if (!wearApp.isFullyInitialized) {
                            Log.w(TAG, "DEBUG_WEAR_PIPELINE: Native framework not yet initialized. Postponing bookmark load.")
                            return@post
                        }

                        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
                        val existingByName = manager.getCategories().find { it.name.equals(categoryName, ignoreCase = true) }
                        
                        app.organicmaps.wear.WatchBookmarkSyncManager.isApplyingRemoteUpdate = true
                        try {
                            if (existingByName != null) {
                                // Background sync is always a non-destructive union-merge: the native
                                // merge de-dups by name+position and resolves same-pin edits by timestamp
                                // (LWW), so importing never drops bookmarks made on this watch offline.
                                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Merging update into existing category '$categoryName' (ID: ${existingByName.id})")
                                manager.loadBookmarksFile(finalFile.absolutePath, true, existingByName.id)
                            } else if (finalFile.exists()) {
                                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Loading bookmarks from file: ${finalFile.absolutePath} (Size: ${finalFile.length()})")
                                manager.loadBookmarksFile(finalFile.absolutePath, true)
                            }
                            
                            if (finalFile.exists()) {
                                NavigationStateHolder.emitEvent(UiEvent.ShowToast("Bookmarks synchronized"))
                                
                                context.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE)
                                    .edit().putLong("last_synced_$categoryName", System.currentTimeMillis()).apply()
                            }
                        } finally {
                            app.organicmaps.wear.WatchBookmarkSyncManager.isApplyingRemoteUpdate = false
                        }
                        
                        NavigationStateHolder.update { current ->
                             val oldCatForTimestamp = current.bookmarkCategories.find { it.name.equals(categoryName, ignoreCase = true) }
                             val timestampToPersist = oldCatForTimestamp?.lastModified ?: 0L
                             if (timestampToPersist > 0) {
                                 Log.d(TAG, "DEBUG_WEAR_PIPELINE: Persisting sync timestamp for $categoryName: $timestampToPersist")
                                 context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
                                     .edit().putLong(categoryName, timestampToPersist).apply()
                             }

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
