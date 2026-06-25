package app.organicmaps.wear

import android.content.Context
import android.util.Log
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult
import app.organicmaps.sdk.bookmarks.data.KmlFileType
import app.organicmaps.sdk.sync.TrackSyncCore
import app.organicmaps.sdk.sync.WearProtocol
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch-side saved-track sync, the mirror of the phone's track logic in [WearSyncService] (gms). See
 * [TrackSyncCore] for the model: a lightweight manifest (uuid/name/color/category + LWW ts) reconciles
 * which tracks exist on each device, and a heavy KMZ blob is fetched on demand only for tracks this
 * device lacks. Identity is a uuid embedded in the track description; visibility is per-device and not
 * synced.
 */
object WatchTrackSyncManager {
    private const val TAG = "WatchTrackSync"

    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile
    var isApplyingRemoteUpdate = false

    // FIFO of pending exports awaiting the shared sharing callback: uuid -> target category name.
    private val pendingExports = ArrayDeque<Pair<String, String>>()
    private val pendingImportTs = ConcurrentHashMap<String, Long>()
    private val trackOutputStreams = HashMap<String, FileOutputStream>()
    private var lastSentManifest: ByteArray? = null
    @Volatile
    private var sharingListenerRegistered = false
    // Captured on the most recent incoming handler so the (context-less) sharing callback can send.
    @Volatile
    private var appContext: Context? = null

    private val sharingListener = BookmarkManager.BookmarksSharingListener { result ->
        if (pendingExports.isNotEmpty()) {
            val (uuid, catName) = pendingExports.pollFirst()
            if (result.code == BookmarkSharingResult.SUCCESS)
                shipTrackBlob(uuid, catName, result.sharingPath)
            else
                Log.w(TAG, "Track export failed for $uuid: code ${result.code}")
        }
    }

    private fun ensureSharingListener() {
        if (sharingListenerRegistered) return
        BookmarkManager.INSTANCE.addSharingListener(sharingListener)
        sharingListenerRegistered = true
    }

    /** Push our manifest and re-send pending tombstones (used on connect / explicit refresh). */
    fun requestSync(context: Context) {
        pushManifest(context)
        flushTombstones(context)
    }

    /** Build + push the saved-track manifest, deduped against the last sent payload. */
    fun pushManifest(context: Context) {
        scope.launch(Dispatchers.Main) {
            isApplyingRemoteUpdate = true
            val payload = try {
                TrackSyncCore.buildManifest(context, BookmarkManager.INSTANCE)
            } finally {
                isApplyingRemoteUpdate = false
            }
            if (payload.contentEquals(lastSentManifest)) return@launch
            lastSentManifest = payload
            WearCommandService.sendTrackManifest(context, payload)
        }
    }

    /**
     * React to a local saved-track change (rename / recolor / move / delete): stamp LWW timestamps,
     * tombstone vanished tracks, and push a fresh manifest. Skipped while applying a remote update so
     * incoming changes aren't echoed back.
     */
    fun onLocalTracksChanged(context: Context) {
        if (isApplyingRemoteUpdate) return
        scope.launch(Dispatchers.Main) {
            isApplyingRemoteUpdate = true
            try {
                val mgr = BookmarkManager.INSTANCE
                TrackSyncCore.stampLocalChange(context, mgr)
                val removed = TrackSyncCore.detectDeletions(context, mgr)
                val now = System.currentTimeMillis()
                for (uuid in removed) {
                    Log.d(TAG, "Track deleted locally -> tombstone $uuid")
                    WearCommandService.sendTrackTombstone(context, TrackSyncCore.encodeTombstone(uuid, now))
                }
            } finally {
                isApplyingRemoteUpdate = false
            }
            pushManifest(context)
        }
    }

    /** Re-send every stored track tombstone (covers deletions made while disconnected). */
    fun flushTombstones(context: Context) {
        val all = TrackSyncCore.allTombstones(context)
        if (all.isEmpty()) return
        for ((uuid, ts) in all)
            WearCommandService.sendTrackTombstone(context, TrackSyncCore.encodeTombstone(uuid, ts))
    }

    /** Apply a manifest from the phone; request the blob of any track we don't have yet. */
    fun handleIncomingManifest(context: Context, payload: ByteArray) {
        scope.launch(Dispatchers.Main) {
            isApplyingRemoteUpdate = true
            val missing = try {
                TrackSyncCore.applyManifest(context, BookmarkManager.INSTANCE, payload)
            } finally {
                isApplyingRemoteUpdate = false
            }
            for (m in missing) {
                pendingImportTs[m.uuid] = m.ts
                WearCommandService.requestTrackBlob(context, encodeBlobRequest(m.uuid))
            }
            refreshTrackCategoriesUi(context)
        }
    }

    /** Apply a single track tombstone from the phone. */
    fun handleIncomingTombstone(context: Context, payload: ByteArray) {
        scope.launch(Dispatchers.Main) {
            isApplyingRemoteUpdate = true
            try {
                TrackSyncCore.applyTombstone(context, BookmarkManager.INSTANCE, payload)
            } finally {
                isApplyingRemoteUpdate = false
            }
            refreshTrackCategoriesUi(context)
        }
    }

    /** Phone asked for a track's geometry: export it to KMZ (shipped via the sharing listener). */
    fun handleIncomingBlobRequest(context: Context, payload: ByteArray) {
        appContext = context.applicationContext
        val uuid = readWireString(ByteBuffer.wrap(payload)) ?: return
        scope.launch(Dispatchers.Main) {
            val mgr = BookmarkManager.INSTANCE
            val trackId = TrackSyncCore.findTrackByUuid(mgr, uuid)
            if (trackId == -1L) {
                Log.w(TAG, "Track blob requested for unknown uuid $uuid")
                return@launch
            }
            val t = mgr.getTrack(trackId)
            val catName = mgr.categories.find { it.id == t.categoryId }?.name ?: "Tracks"
            ensureSharingListener()
            pendingExports.addLast(uuid to catName)
            mgr.prepareTrackForSharing(trackId, KmlFileType.Text)
        }
    }

    /** Receive a track blob (chunked KMZ), merge it into its target category, stamp its LWW ts. */
    fun handleIncomingBlob(context: Context, payload: ByteArray) {
        val b = ByteBuffer.wrap(payload)
        if (b.remaining() < 1) return
        val flags = b.get().toInt()
        val isLast = (flags and 1) != 0
        val uuid = readWireString(b) ?: return
        val catName = readWireString(b) ?: return
        val chunk = ByteArray(b.remaining())
        b.get(chunk)
        try {
            val safe = uuid.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val tmpFile = File(context.cacheDir, "trk_$safe.part")
            val fos = trackOutputStreams.getOrPut(uuid) { FileOutputStream(tmpFile) }
            fos.write(chunk)
            if (!isLast) return
            fos.close()
            trackOutputStreams.remove(uuid)

            val isZip = FileInputStream(tmpFile).use { input ->
                val sig = ByteArray(4)
                input.read(sig) == 4 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte() &&
                    sig[2] == 0x03.toByte() && sig[3] == 0x04.toByte()
            }
            val finalFile = File(context.cacheDir, "trk_$safe" + if (isZip) ".kmz" else ".kml")
            if (finalFile.exists()) finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.inputStream().use { i -> finalFile.outputStream().use { o -> i.copyTo(o) } }
                tmpFile.delete()
            }

            val ts = pendingImportTs[uuid] ?: System.currentTimeMillis()
            scope.launch(Dispatchers.Main) {
                isApplyingRemoteUpdate = true
                try {
                    val mgr = BookmarkManager.INSTANCE
                    val existing = mgr.categories.find { it.name.equals(catName, ignoreCase = true) }
                    val catId = existing?.id ?: mgr.createCategory(catName)
                    // Native merges the imported temp category into catId and deletes the leftover; the
                    // track's uuid rides along in its description.
                    mgr.loadBookmarksFile(finalFile.absolutePath, true, catId)
                } finally {
                    isApplyingRemoteUpdate = false
                }
                // The import is async; stamp the LWW ts once it settles so we don't bounce it back.
                delay(1500)
                isApplyingRemoteUpdate = true
                try {
                    TrackSyncCore.recordImported(context, BookmarkManager.INSTANCE, uuid, ts)
                } finally {
                    isApplyingRemoteUpdate = false
                }
                pendingImportTs.remove(uuid)
                refreshTrackCategoriesUi(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming track blob: ${e.message}")
            trackOutputStreams.remove(uuid)?.let { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun shipTrackBlob(uuid: String, catName: String, path: String) {
        val context = appContext ?: run {
            Log.w(TAG, "No context to ship track blob $uuid")
            return
        }
        val file = File(path)
        val length = file.length()
        try {
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(32 * 1024)
                var sent = 0L
                while (true) {
                    val read = fis.read(buffer)
                    if (read == -1) break
                    sent += read
                    val isLast = sent >= length
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    WearCommandService.sendTrackBlob(context, encodeBlob(uuid, catName, chunk, isLast))
                    if (isLast) break
                }
            }
            Log.i(TAG, "Shipped track blob $uuid ($length bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ship track blob: ${e.message}")
        }
    }

    /** Re-sync the visible category list so freshly-imported/removed tracks reflect their trk counts. */
    private fun refreshTrackCategoriesUi(context: Context) {
        val mgr = BookmarkManager.INSTANCE
        NavigationStateHolder.update { current ->
            val merged = current.bookmarkCategories.toMutableList()
            for (cat in mgr.categories) {
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

    private fun encodeBlobRequest(uuid: String): ByteArray {
        val u = uuid.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(4 + u.size).putInt(u.size).put(u).array()
    }

    private fun encodeBlob(uuid: String, catName: String, chunk: ByteArray, isLast: Boolean): ByteArray {
        val u = uuid.toByteArray(StandardCharsets.UTF_8)
        val c = catName.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + u.size + 4 + c.size + chunk.size)
            .put((if (isLast) 1 else 0).toByte())
            .putInt(u.size).put(u)
            .putInt(c.size).put(c)
            .put(chunk)
            .array()
    }

    private fun readWireString(b: ByteBuffer): String? {
        if (b.remaining() < 4) return null
        val len = b.int
        if (len < 0 || b.remaining() < len) return null
        val bytes = ByteArray(len)
        b.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
