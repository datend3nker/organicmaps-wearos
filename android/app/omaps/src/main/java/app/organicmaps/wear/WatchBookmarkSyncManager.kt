package app.organicmaps.wear

import android.content.Context
import android.util.Log
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult
import app.organicmaps.sdk.bookmarks.data.KmlFileType
import app.organicmaps.sdk.sync.BookmarkTombstoneStore
import androidx.core.content.edit
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object WatchBookmarkSyncManager {
    private const val TAG = "WatchBookmarkSync"
    private const val PREFS_NAME = "bookmark_sync_state"
    
    private val scope = CoroutineScope(Dispatchers.Main)
    
    @Volatile
    var isApplyingRemoteUpdate = false

    private val pendingMerges = ConcurrentHashMap.newKeySet<String>()

    private const val SNAP_PREFS = "bookmark_identity_snapshot"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onLocalBookmarksChanged(context: Context, isUserAction: Boolean = true) {
        // Detect per-bookmark deletions/additions by diffing each category's identity set against the
        // last snapshot, so deletions can be propagated as tombstones (a union-merge never removes).
        detectBookmarkDeletions(context, isUserAction && !isApplyingRemoteUpdate)

        if (isApplyingRemoteUpdate && isUserAction) return

        val now = System.currentTimeMillis()
        val manager = BookmarkManager.INSTANCE
        val categories = manager.getCategories()
        val prefs = getPrefs(context)
        
        prefs.edit {
            for (cat in categories) {
                val name = cat.name
                val bCount = cat.bookmarksCount
                val tCount = cat.tracksCount
                
                val lastB = prefs.getInt("last_count_b_$name", -1)
                val lastT = prefs.getInt("last_count_t_$name", -1)
                
                if (bCount != lastB || tCount != lastT) {
                    if (isUserAction) {
                        putLong("last_local_edit_$name", now)
                    }
                    putInt("last_count_b_$name", bCount)
                    putInt("last_count_t_$name", tCount)
                }
            }
        }

        // Reflect locally added/removed bookmarks in the manager UI immediately. The category list
        // is otherwise driven by phone metadata, so a bookmark added on the watch wouldn't appear
        // until the phone round-tripped fresh metadata back.
        NavigationStateHolder.update { current ->
            val merged = current.bookmarkCategories.toMutableList()
            for (cat in categories) {
                val existing = merged.find { it.name.equals(cat.name, ignoreCase = true) }
                val item = BookmarkCategoryItem(
                    cat.id, cat.name, cat.isVisible, cat.bookmarksCount, cat.tracksCount,
                    isSyncing = existing?.isSyncing ?: false,
                    remoteId = existing?.remoteId ?: 0L,
                    lastModified = existing?.lastModified ?: 0L
                )
                val idx = merged.indexOfFirst { it.name.equals(cat.name, ignoreCase = true) }
                if (idx != -1) merged[idx] = item else merged.add(item)
            }
            current.copy(bookmarkCategories = merged.distinctBy { it.name })
        }
    }

    fun requestSync(context: Context) {
        pushUpsertBatch(context)
        flushTombstones(context)
    }

    /**
     * Push every local bookmark to the phone as a per-bookmark LWW upsert batch (content-addressed
     * identity, no KMZ). Replaces the old category-metadata + KMZ export reconcile that cascaded into
     * the My Places1/11/111 explosion. The phone applies the batch idempotently; deletions ride the
     * separate tombstone channel.
     */
    private fun pushUpsertBatch(context: Context) {
        scope.launch(Dispatchers.Main) {
            try {
                val payload = app.organicmaps.sdk.sync.BookmarkSyncCore.buildUpsertBatch(
                    context, BookmarkManager.INSTANCE)
                WearCommandService.sendBookmarkUpsert(context, payload)
            } catch (e: Exception) {
                Log.w(TAG, "pushUpsertBatch failed: ${e.message}")
            }
        }
    }

    /** Apply an upsert batch received from the phone (per-bookmark LWW), guarded so it isn't echoed. */
    fun handleIncomingUpsert(context: Context, payload: ByteArray) {
        scope.launch(Dispatchers.Main) {
            isApplyingRemoteUpdate = true
            try {
                app.organicmaps.sdk.sync.BookmarkSyncCore.applyUpsertBatch(
                    context, BookmarkManager.INSTANCE, payload)
            } catch (e: Exception) {
                Log.w(TAG, "handleIncomingUpsert failed: ${e.message}")
            } finally {
                isApplyingRemoteUpdate = false
            }
            // Refresh snapshot so applied additions aren't later mistaken for local user edits.
            detectBookmarkDeletions(context, isUserAction = false)
        }
    }

    fun respondToSyncRequest(context: Context, categoryName: String) {
        scope.launch(Dispatchers.Main) {
            val manager = BookmarkManager.INSTANCE
            val cat = manager.getCategories().find { it.name.equals(categoryName, ignoreCase = true) }
            if (cat == null) {
                Log.w(TAG, "Phone requested sync for unknown category: $categoryName")
                return@launch
            }
            Log.i(TAG, "Phone requested PULL of '$categoryName' — exporting for push")
            manager.prepareCategoriesForSharing(longArrayOf(cat.id), KmlFileType.Text)
        }
    }

    /**
     * Diff each category's current bookmark identities against the last snapshot. Removed identities
     * become tombstones (recorded + pushed to the phone); re-added identities clear any stale
     * tombstone. Only generates tombstones for genuine local user deletions ([isUserAction]); for
     * remote-applied changes it just refreshes the snapshot so we don't echo them back.
     */
    private fun detectBookmarkDeletions(context: Context, isUserAction: Boolean) {
        try {
            val manager = BookmarkManager.INSTANCE
            val snap = context.getSharedPreferences(SNAP_PREFS, Context.MODE_PRIVATE)
            val editor = snap.edit()
            val now = System.currentTimeMillis()
            for (cat in manager.getCategories()) {
                val current = BookmarkTombstoneStore.currentIdentities(manager, cat)
                val key = "snap_${cat.name}"
                val previous = snap.getStringSet(key, null)
                if (previous != null && isUserAction) {
                    for (gone in previous - current) {
                        BookmarkTombstoneStore.record(context, gone, now)
                        BookmarkTombstoneStore.encodeFromKey(gone, now)?.let {
                            Log.d(TAG, "Bookmark deleted locally → tombstone $gone")
                            WearCommandService.sendBookmarkTombstone(context, it)
                        }
                    }
                    for (added in current - previous)
                        BookmarkTombstoneStore.clear(context, added)
                }
                editor.putStringSet(key, current)
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "detectBookmarkDeletions failed: ${e.message}")
        }
    }

    /** Re-send every stored tombstone to the phone (covers deletions made while disconnected). */
    fun flushTombstones(context: Context) {
        val all = BookmarkTombstoneStore.all(context)
        if (all.isEmpty()) return
        Log.d(TAG, "Flushing ${all.size} tombstone(s) to phone")
        for ((key, ts) in all)
            BookmarkTombstoneStore.encodeFromKey(key, ts)?.let { WearCommandService.sendBookmarkTombstone(context, it) }
    }

    /**
     * After any bookmark file load/merge completes, purge bookmarks that have been re-introduced by
     * the merge but are tombstoned locally. A union-merge never removes, so without this a deletion
     * would be resurrected by the next incoming file. Runs under the remote-update guard so the
     * purge isn't echoed back as a fresh user deletion.
     */
    fun onBookmarksFileMergeFinished(context: Context) {
        scope.launch(Dispatchers.Main) {
            val manager = BookmarkManager.INSTANCE
            isApplyingRemoteUpdate = true
            try {
                var removed = 0
                for (cat in manager.getCategories())
                    removed += BookmarkTombstoneStore.applyToCategory(context, manager, cat)
                if (removed > 0) Log.d(TAG, "Purged $removed resurrected bookmark(s) after merge")
            } finally {
                isApplyingRemoteUpdate = false
            }
            // refresh the identity snapshot so the purge isn't later seen as a user deletion
            detectBookmarkDeletions(context, isUserAction = false)
        }
    }

    /** Apply a tombstone received from the phone: record it and delete the matching local bookmark. */
    fun applyIncomingTombstone(context: Context, payload: ByteArray) {
        val parsed = BookmarkTombstoneStore.decode(payload) ?: return
        BookmarkTombstoneStore.record(context, parsed.key(), parsed.ts)
        scope.launch(Dispatchers.Main) {
            val manager = BookmarkManager.INSTANCE
            val cat = manager.getCategories().find { it.name.equals(parsed.categoryName, ignoreCase = true) } ?: return@launch
            isApplyingRemoteUpdate = true
            try {
                BookmarkTombstoneStore.applyToCategory(context, manager, cat)
            } finally {
                isApplyingRemoteUpdate = false
            }
            // refresh snapshot so the local deletion isn't re-detected as a user action
            onLocalBookmarksChanged(context, isUserAction = false)
        }
    }

    fun addPendingMerge(categoryName: String) {
        pendingMerges.add(categoryName)
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

        // BookmarkManager.getCategories()/prepareCategoriesForSharing assert CalledOnOriginalThread
        // (native thread-checker). The transport listener invokes this off the engine's main thread,
        // so run the whole reconcile on Main — otherwise nativeGetBookmarkCategories aborts (SIGABRT).
        scope.launch(Dispatchers.Main) {
        val manager = BookmarkManager.INSTANCE
        val localCategories = manager.getCategories()
        val prefs = getPrefs(context)

        repeat(count) {
            val nameLen = buffer.int
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes, StandardCharsets.UTF_8)
            val remoteBmkCount = buffer.int
            val remoteTrkCount = buffer.int
            val remoteLastEdit = buffer.long
            buffer.long // remoteLastSynced (unused)

            val localLastEdit = prefs.getLong("last_local_edit_$name", 0)
            val localLastSynced = prefs.getLong("last_synced_$name", 0)
            val cat = localCategories.find { it.name == name }

            val localChanged = localLastEdit > localLastSynced
            val remoteChanged = remoteLastEdit > localLastSynced
            val countsDiffer = cat != null &&
                (remoteBmkCount != cat.bookmarksCount || remoteTrkCount != cat.tracksCount)
            val neverSynced = localLastSynced == 0L

            // Full-mirror reconcile: both devices must converge to the same set.
            var doPull = false
            var doPush = false
            when {
                cat == null -> doPull = true                       // remote-only category → create here
                localChanged && remoteChanged -> { doPull = true; doPush = true }
                remoteChanged -> doPull = true
                localChanged -> doPush = true
                countsDiffer || neverSynced -> { doPull = true; doPush = true } // drifted / never reconciled
            }

            if (doPull) {
                Log.d(TAG, "Bookmark reconcile PULL: $name")
                WearCommandService.syncCategory(context, name)
            }
            if (doPush && cat != null && (cat.bookmarksCount > 0 || cat.tracksCount > 0)) {
                Log.d(TAG, "Bookmark reconcile PUSH: $name")
                pendingMerges.add(name)
                // already on Main (see launch above) — safe to call the thread-checked native export.
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
            
            pendingMerges.remove(catName)
            // Sync pushes are always a non-destructive union-merge on the receiver; the native
            // merge de-dups, so this is idempotent and never overwrites the phone's bookmarks.
            val shouldMerge = true

            scope.launch(Dispatchers.IO) {
                val file = File(path)
                val length = file.length()
                var sent = 0L
                Log.i(TAG, "Sending watch bookmark file: $catName ($length bytes, merge=$shouldMerge)")
                
                val context = WearApplication.instance
                try {
                    FileInputStream(file).use { fis ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            sent += read
                            val isLast = sent >= length
                            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                            WearCommandService.sendBookmarkFile(context, catName, chunk, isLast, shouldMerge)
                            
                            if (isLast) {
                                withContext(Dispatchers.Main) {
                                    getPrefs(context).edit {
                                        putLong("last_synced_$catName", System.currentTimeMillis())
                                    }
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
