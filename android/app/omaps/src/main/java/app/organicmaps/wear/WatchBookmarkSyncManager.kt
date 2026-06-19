package app.organicmaps.wear

import android.content.Context
import android.util.Log
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.sync.BookmarkTombstoneStore
import app.organicmaps.sdk.sync.BookmarkSyncCore
import androidx.core.content.edit
import kotlinx.coroutines.*

object WatchBookmarkSyncManager {
    private const val TAG = "WatchBookmarkSync"
    private const val PREFS_NAME = "bookmark_sync_state"
    
    private val scope = CoroutineScope(Dispatchers.Main)
    
    @Volatile
    var isApplyingRemoteUpdate = false

    private const val SNAP_PREFS = "bookmark_identity_snapshot"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onLocalBookmarksChanged(context: Context, isUserAction: Boolean = true) {
        // Detect per-bookmark deletions/additions by diffing each category's identity set against the
        // last snapshot, so deletions can be propagated as tombstones (a union-merge never removes).
        detectBookmarkDeletions(context, isUserAction && !isApplyingRemoteUpdate)

        if (isApplyingRemoteUpdate && isUserAction) return

        // Bump per-identity LWW timestamps for any content change (add/rename/recolor/re-describe) so
        // edits — not just adds — propagate to the phone on the next push.
        if (isUserAction) {
            BookmarkSyncCore.stampLocalChange(context, BookmarkManager.INSTANCE)
        }

        val now = System.currentTimeMillis()
        val manager = BookmarkManager.INSTANCE
        val categories = manager.categories
        val prefs = getPrefs(context)
        
        prefs.edit {
            for (cat in categories) {
                val name = cat.name
                val bCount = cat.bookmarksCount
                val tCount = cat.tracksCount
                
                val lastB = prefs.getInt("last_count_b_$name", -1)
                val lastT = prefs.getInt("last_count_t_$name", -1)
                
                if (bCount != lastB || (tCount != lastT)) {
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
                val payload = BookmarkSyncCore.buildUpsertBatch(
                    context,
                    BookmarkManager.INSTANCE,
                )
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
                BookmarkSyncCore.applyUpsertBatch(
                    context,
                    BookmarkManager.INSTANCE,
                    payload,
                )
            } catch (e: Exception) {
                Log.w(TAG, "handleIncomingUpsert failed: ${e.message}")
            } finally {
                isApplyingRemoteUpdate = false
            }
            // Refresh snapshot so applied additions aren't later mistaken for local user edits.
            detectBookmarkDeletions(context, isUserAction = false)
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
            val now = System.currentTimeMillis()
            snap.edit {
                for (cat in manager.categories) {
                    val current = BookmarkTombstoneStore.currentIdentities(manager, cat)
                    val key = "snap_${cat.name}"
                    val previous = snap.getStringSet(key, null)
                    if (previous != null && isUserAction) {
                        for (gone in (previous - current)) {
                            BookmarkTombstoneStore.record(context, gone, now)
                            BookmarkTombstoneStore.encodeFromKey(gone, now)?.let {
                                Log.d(TAG, "Bookmark deleted locally → tombstone $gone")
                                WearCommandService.sendBookmarkTombstone(context, it)
                            }
                        }
                        for (added in (current - previous)) {
                            BookmarkTombstoneStore.clear(context, added)
                        }
                    }
                    putStringSet(key, current)
                }
            }
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
                // Snapshot — applyToCategory deletes, mutating the live categories view (CME otherwise).
                for (cat in manager.categories.toList())
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
            val cat = manager.categories.find { it.name.equals(parsed.categoryName, ignoreCase = true) } ?: return@launch
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

}
