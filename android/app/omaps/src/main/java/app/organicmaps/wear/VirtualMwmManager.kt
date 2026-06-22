package app.organicmaps.wear

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.util.MapIdUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manage the local sparse-cache and bridge the JNI calls to the Transport layer.
 */
object VirtualMwmManager {
    private const val TAG = "VirtualMwmManager"
    private const val BLOCK_SIZE = 64 * 1024 
    private const val READ_AHEAD_BLOCKS = 3
    
    private val mwmFiles = ConcurrentHashMap<String, RandomAccessFile>()
    private val mwmPaths = ConcurrentHashMap<String, String>()
    private val mwmTotalSizes = ConcurrentHashMap<String, Long>()
    private val mountedMwms = ConcurrentHashMap.newKeySet<String>()
    private val mwmBlockTrackers = ConcurrentHashMap<String, BitSet>()
    private val mwmPendingTrackers = ConcurrentHashMap<String, BitSet>()

    private val metadataFailures = ConcurrentHashMap<String, Long>()
    
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnables = ConcurrentHashMap<String, Runnable>()

    private val mScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nativeLibraryLoaded = CompletableDeferred<Unit>()
    private val nativeMountedMwms = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // --- Bounded LRU cache ---
    // Per-(mwm, blockIndex) last-access tick; higher = more recently used.
    private val mwmBlockAccess = ConcurrentHashMap<String, LongArray>()
    private val accessTick = AtomicLong(0)
    // Blocks that must never be evicted: the container header/footer (set on mount) and any
    // natively-pinned range discovered lazily (e.g. the mmap'd features-offsets table).
    private val mwmPinnedBlocks = ConcurrentHashMap<String, BitSet>()
    // Physical bytes currently resident across all mounted virtual mwms (sum of present blocks).
    private val residentBytes = AtomicLong(0)
    private val evictionRunning = AtomicBoolean(false)
    @Volatile private var cachedBudgetBytes = 0L
    @Volatile private var budgetComputedAt = 0L
    private val _cacheBytes = MutableStateFlow(0L)
    /** Current streaming-cache size in bytes (for storage-aware UI). */
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private const val MIN_BUDGET_BYTES = 24L * 1024 * 1024
    private const val MAX_BUDGET_BYTES = 192L * 1024 * 1024

    private fun blockLen(totalSize: Long, blockIdx: Int): Int =
        (totalSize - (blockIdx.toLong() * BLOCK_SIZE)).coerceAtMost(BLOCK_SIZE.toLong()).coerceAtLeast(0).toInt()

    private fun touchBlock(mwmName: String, blockIdx: Int) {
        val totalSize = mwmTotalSizes[mwmName] ?: return
        val numBlocks = ((totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE).toInt()
        if (blockIdx !in 0 until numBlocks) return
        val arr = mwmBlockAccess.getOrPut(mwmName) { LongArray(numBlocks) }
        if (blockIdx < arr.size) arr[blockIdx] = accessTick.incrementAndGet()
    }

    private fun touchRange(mwmName: String, offset: Long, size: Int) {
        if (size <= 0) return
        val start = (offset / BLOCK_SIZE).toInt()
        val end = ((offset + size - 1) / BLOCK_SIZE).toInt()
        for (i in start..end) touchBlock(mwmName, i)
    }

    /** Auto budget: ~25% of (free space + what we already hold), clamped. Cached for 30s. */
    private fun computeBudgetBytes(): Long {
        val now = System.currentTimeMillis()
        if (cachedBudgetBytes > 0 && now - budgetComputedAt < 30_000) return cachedBudgetBytes
        val budget = try {
            val path = StoragePathManager.findMapsStorage(WearApplication.instance)
            val available = StatFs(path).availableBytes
            ((available + residentBytes.get()) / 4).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "computeBudgetBytes failed, using min: ${e.message}")
            MIN_BUDGET_BYTES
        }
        cachedBudgetBytes = budget
        budgetComputedAt = now
        return budget
    }

    private fun publishCacheBytes() { _cacheBytes.value = residentBytes.get() }

    private fun initResidentFromTracker(mwmName: String, totalSize: Long) {
        val tracker = mwmBlockTrackers[mwmName] ?: return
        var sum = 0L
        synchronized(tracker) {
            var b = tracker.nextSetBit(0)
            while (b != -1) { sum += blockLen(totalSize, b); b = tracker.nextSetBit(b + 1) }
        }
        if (sum > 0) { residentBytes.addAndGet(sum); publishCacheBytes() }
    }

    private fun maybeEnforceBudget() {
        if (residentBytes.get() <= computeBudgetBytes()) return
        if (!evictionRunning.compareAndSet(false, true)) return
        mScope.launch {
            try { enforceBudget() } finally { evictionRunning.set(false) }
        }
    }

    private fun enforceBudget() {
        val target = computeBudgetBytes() * 9 / 10  // hysteresis: free down to 90% of budget
        data class Victim(val mwm: String, val block: Int, val tick: Long)
        while (residentBytes.get() > target) {
            val candidates = ArrayList<Victim>()
            for (mwm in mountedMwms) {
                val tracker = mwmBlockTrackers[mwm] ?: continue
                val pinned = mwmPinnedBlocks[mwm]
                val pending = mwmPendingTrackers[mwm]
                val access = mwmBlockAccess[mwm]
                synchronized(tracker) {
                    var b = tracker.nextSetBit(0)
                    while (b != -1) {
                        val isPinned = pinned != null && synchronized(pinned) { pinned[b] }
                        val isPending = pending != null && synchronized(pending) { pending[b] }
                        if (!isPinned && !isPending) {
                            val tick = access?.getOrElse(b) { 0L } ?: 0L
                            candidates.add(Victim(mwm, b, tick))
                        }
                        b = tracker.nextSetBit(b + 1)
                    }
                }
            }
            if (candidates.isEmpty()) break
            candidates.sortBy { it.tick }
            var freedAny = false
            for (v in candidates) {
                if (residentBytes.get() <= target) break
                if (evictBlock(v.mwm, v.block)) freedAny = true
            }
            if (!freedAny) break  // remaining are all pinned or hole-punch unsupported
        }
    }

    /** Returns true iff a block's physical bytes were freed. */
    private fun evictBlock(mwmName: String, blockIdx: Int): Boolean {
        val totalSize = mwmTotalSizes[mwmName] ?: return false
        val path = mwmPaths[mwmName] ?: return false
        val offset = blockIdx.toLong() * BLOCK_SIZE
        val len = blockLen(totalSize, blockIdx)
        if (len <= 0) return false

        // Clear native availability. Natively-pinned ranges (offsets table) return false.
        val cleared = try { nativeInvalidateData(mwmName, offset, len.toLong()) } catch (e: Throwable) {
            Log.w(TAG, "nativeInvalidateData failed for $mwmName@$offset: ${e.message}"); false
        }
        if (!cleared) {
            // Remember the pin locally so we stop re-selecting this block.
            mwmPinnedBlocks.getOrPut(mwmName) { BitSet() }.let { synchronized(it) { it.set(blockIdx) } }
            return false
        }

        val punched = try { nativePunchHole(path, offset, len.toLong()) } catch (e: Throwable) {
            Log.w(TAG, "nativePunchHole failed for $path@$offset: ${e.message}"); false
        }
        if (!punched) {
            // Disk wasn't freed (fs unsupported). The bytes are still present, so re-signal
            // availability to avoid a pointless re-fetch, and stop evicting.
            try { nativeDataArrived(mwmName, offset, len) } catch (_: Throwable) {}
            return false
        }

        mwmBlockTrackers[mwmName]?.let { synchronized(it) { it.clear(blockIdx) } }
        residentBytes.addAndGet(-len.toLong())
        publishCacheBytes()
        scheduleSave(mwmName)
        return true
    }

    fun setNativeLibraryLoaded() {
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Native library signal received")
        nativeLibraryLoaded.complete(Unit)
    }

    fun isMounted(mwmNameWithExt: String): Boolean {
        val normalized = MapIdUtils.normalize(mwmNameWithExt)
        return normalized != null && mountedMwms.contains(normalized)
    }

    @JvmStatic
    @Suppress("UNUSED")
    fun onDataRequired(mwmNameWithExt: String, offset: Long, size: Int) {
        mScope.launch {
            try {
                val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
                val totalSize = mwmTotalSizes[mwmName]
                if (totalSize == null) {
                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: Requested data for map with unknown size: $mwmName. Ensure it is mounted in Kotlin.")
                    return@launch
                }
                
                val tracker = mwmBlockTrackers.getOrPut(mwmName) { BitSet() }
                val pending = mwmPendingTrackers.getOrPut(mwmName) { BitSet() }
                
                val startBlock = (offset / BLOCK_SIZE).toInt()
                val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
                
                // Read-ahead: extend the window
                val requestEndBlock = (endBlock + READ_AHEAD_BLOCKS).coerceAtMost(((totalSize - 1) / BLOCK_SIZE).toInt())

                val alignedOffset = startBlock.toLong() * BLOCK_SIZE
                val alignedLen = ((endBlock - startBlock + 1).toLong() * BLOCK_SIZE).coerceAtMost(totalSize - alignedOffset).toInt()

                var allAvailable = true
                synchronized(tracker) {
                    for (i in startBlock..endBlock) {
                        if (!tracker.get(i)) {
                            allAvailable = false
                            break
                        }
                    }
                }

                if (allAvailable) {
                    // Reading from the viewport keeps these blocks hot so eviction spares them.
                    touchRange(mwmName, alignedOffset, alignedLen)
                    nativeLibraryLoaded.await()
                    nativeMountedMwms[mwmName]?.await()
                    nativeDataArrived(mwmName, alignedOffset, alignedLen)
                    return@launch
                }

                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Native requested data from phone: $mwmName, offset=$offset, size=$size (Blocks: $startBlock-$endBlock, Read-ahead up to $requestEndBlock)")
                
                if (WearMapDownloader.currentMap.value == mwmName && 
                    WearMapDownloader.downloadState.value == WearMapDownloader.DownloadState.STREAMING_FROM_PHONE) {
                    Log.v(TAG, "DEBUG_WEAR_PIPELINE: Skipping chunk request for map currently being fully PULLED: $mwmName")
                    return@launch
                }

                for (i in startBlock..requestEndBlock) {
                    val isMissing = synchronized(tracker) { !tracker.get(i) }
                    val isActuallyPending = synchronized(pending) { pending.get(i) }
                    
                    if (isMissing && !isActuallyPending) {
                        val blockOffset = i.toLong() * BLOCK_SIZE
                        val blockSize = BLOCK_SIZE.toLong().coerceAtMost(totalSize - blockOffset).toInt()
                        
                        markPending(mwmName, blockOffset, blockSize)
                        WearCommandService.requestMwmBytes(WearApplication.instance, mwmName, blockOffset, blockSize)
                        
                        saveHandler.postDelayed({
                            if (isPending(mwmName, blockOffset, blockSize)) {
                                Log.w(TAG, "DEBUG_WEAR_PIPELINE: Request TIMEOUT for $mwmName at $blockOffset ($blockSize)")
                                clearPending(mwmName, blockOffset, blockSize)
                            }
                        }, 4500)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Exception in onDataRequired: ${e.message}", e)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun onBytesReceived(mwmNameWithExt: String, offset: Long, data: ByteArray) {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        clearPending(mwmName, offset, data.size)

        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Received data for $mwmName, offset: $offset, size: ${data.size}")
        try {
            val raf = mwmFiles[mwmName]
            if (raf != null) {
                synchronized(raf) {
                    raf.seek(offset)
                    raf.write(data)
                }
                markDataReceived(mwmName, offset, data.size)
                
                mScope.launch {
                    nativeLibraryLoaded.await()
                    nativeMountedMwms[mwmName]?.await()
                    nativeDataArrived(mwmName, offset, data.size)
                }
                
                val totalSize = mwmTotalSizes[mwmName] ?: try { raf.length() } catch (_: Exception) { 0L }
                val end = offset + data.size
                
                if (totalSize > 0 && end >= totalSize) {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Footer arrived for $mwmName, triggering reload")
                    ReloadWorldMapsDebouncer.reload()
                }

                val tracker = mwmBlockTrackers[mwmName]
                if (tracker != null && totalSize > 0) {
                    val numBlocks = ((totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE).toInt()
                    var isComplete = true
                    synchronized(tracker) {
                        for (i in 0 until numBlocks) {
                            if (!tracker.get(i)) {
                                isComplete = false
                                break
                            }
                        }
                    }
                    if (isComplete) {
                        Log.i(TAG, "DEBUG_WEAR_PIPELINE: $mwmName is COMPLETE. Deleting sidecar.")
                        val path = mwmPaths[mwmName]
                        if (path != null) {
                            File("$path.bits").delete()
                            saveRunnables.remove(mwmName)?.let { saveHandler.removeCallbacks(it) }
                        }
                    }
                }
            } else {
                Log.w(TAG, "DEBUG_WEAR_PIPELINE: Received data for UNMOUNTED MWM: $mwmName at $offset")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to write MWM data for $mwmName at $offset: ${e.message}", e)
        }
    }


    private fun markDataReceived(mwmName: String, offset: Long, size: Int) {
        val totalSize = mwmTotalSizes[mwmName] ?: return
        val tracker = mwmBlockTrackers.getOrPut(mwmName) { BitSet() }
        
        val end = offset + size
        val startBlock = ((offset + BLOCK_SIZE - 1) / BLOCK_SIZE).toInt()
        var endBlock = (end / BLOCK_SIZE).toInt()
        
        if (end >= totalSize) {
            endBlock = ((totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE).toInt()
        }
        
        var changed = false
        var addedBytes = 0L
        synchronized(tracker) {
            for (i in startBlock until endBlock) {
                if (!tracker.get(i)) {
                    tracker.set(i)
                    addedBytes += blockLen(totalSize, i)
                    changed = true
                }
            }
        }

        // LRU + budget accounting: a freshly written block is the most-recently used.
        touchRange(mwmName, offset, size)
        if (addedBytes > 0) {
            residentBytes.addAndGet(addedBytes)
            publishCacheBytes()
        }

        if (changed) {
            scheduleSave(mwmName)
            maybeEnforceBudget()
        }
    }

    private fun isPending(mwmName: String, offset: Long, size: Int): Boolean {
        val tracker = mwmPendingTrackers[mwmName] ?: return false
        val startBlock = (offset / BLOCK_SIZE).toInt()
        val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
        
        synchronized(tracker) {
            for (i in startBlock..endBlock) {
                if (tracker.get(i)) return true
            }
        }
        return false
    }

    private fun markPending(mwmName: String, offset: Long, size: Int) {
        val tracker = mwmPendingTrackers.getOrPut(mwmName) { BitSet() }
        val startBlock = (offset / BLOCK_SIZE).toInt()
        val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
        
        synchronized(tracker) {
            for (i in startBlock..endBlock) {
                tracker.set(i)
            }
        }
    }

    private fun clearPending(mwmName: String, offset: Long, size: Int) {
        val tracker = mwmPendingTrackers[mwmName] ?: return
        val startBlock = (offset / BLOCK_SIZE).toInt()
        val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
        
        synchronized(tracker) {
            for (i in startBlock..endBlock) {
                tracker.clear(i)
            }
        }
    }

    private fun scheduleSave(mwmName: String) {
        saveRunnables.getOrPut(mwmName) {
            Runnable { 
                saveTracker(mwmName) 
            }
        }.let {
            saveHandler.removeCallbacks(it)
            saveHandler.postDelayed(it, 5000) 
        }
    }

    private fun saveTracker(mwmName: String) {
        val path = mwmPaths[mwmName] ?: return
        val tracker = mwmBlockTrackers[mwmName] ?: return
        try {
            val bitsFile = File("$path.bits")
            val data = synchronized(tracker) {
                tracker.toByteArray()
            }
            bitsFile.writeBytes(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitset for $mwmName: ${e.message}")
        }
    }

    private fun loadTracker(mwmPath: String): BitSet? {
        val bitsFile = File("$mwmPath.bits")
        if (!bitsFile.exists()) return null
        return try {
            val bytes = bitsFile.readBytes()
            BitSet.valueOf(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitset for $mwmPath: ${e.message}")
            null
        }
    }

    fun unmount(mwmNameWithExt: String) {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        Log.d(TAG, "Unmounting virtual MWM: $mwmName")
        try {
            mwmFiles.remove(mwmName)?.let { raf ->
                mScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            raf.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing raf for $mwmName", e)
                        }
                    }
                }
            }
            // Drop this mwm's resident bytes from the global budget accounting.
            val totalSize = mwmTotalSizes[mwmName]
            val tracker = mwmBlockTrackers[mwmName]
            if (totalSize != null && tracker != null) {
                var sum = 0L
                synchronized(tracker) {
                    var b = tracker.nextSetBit(0)
                    while (b != -1) { sum += blockLen(totalSize, b); b = tracker.nextSetBit(b + 1) }
                }
                if (sum > 0) { residentBytes.addAndGet(-sum); publishCacheBytes() }
            }

            mwmPaths.remove(mwmName)
            mwmTotalSizes.remove(mwmName)
            mountedMwms.remove(mwmName)
            mwmBlockTrackers.remove(mwmName)
            mwmPendingTrackers.remove(mwmName)
            mwmBlockAccess.remove(mwmName)
            mwmPinnedBlocks.remove(mwmName)
            nativeMountedMwms.remove(mwmName)
            saveRunnables.remove(mwmName)?.let { saveHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error during unmount of $mwmName: ${e.message}")
        }
    }

    /**
     * Delete a streamed (virtual / partial) map: tear down the mount AND remove its sparse
     * `.mwm` + `.bits` files. The generic `MapManager.delete` is not virtual-aware — it leaves
     * the sparse cache half-deleted, which `restoreMountsEarly` then re-mounts into a corrupt
     * map (zeroed holes marked "present") that crashes the render thread on next launch
     * (issue #7: "app won't start after removing a partial map"). This leaves no leftovers.
     * Returns true if a virtual map was found and handled.
     */
    fun deleteVirtual(context: Context, mwmNameWithExt: String): Boolean {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt) ?: return false
        val path = mwmPaths[mwmName] ?: findSparseFile(context, mwmName)?.absolutePath ?: return false
        Log.i(TAG, "DEBUG_WEAR_PIPELINE: Deleting virtual MWM $mwmName at $path")

        // Close the RAF synchronously so the file handle is released before we delete the file.
        mwmFiles.remove(mwmName)?.let { raf -> runCatching { raf.close() } }

        // Drop this mwm's resident bytes from the global budget accounting, then clear all state.
        val totalSize = mwmTotalSizes[mwmName]
        val tracker = mwmBlockTrackers[mwmName]
        if (totalSize != null && tracker != null) {
            var sum = 0L
            synchronized(tracker) {
                var b = tracker.nextSetBit(0)
                while (b != -1) { sum += blockLen(totalSize, b); b = tracker.nextSetBit(b + 1) }
            }
            if (sum > 0) { residentBytes.addAndGet(-sum); publishCacheBytes() }
        }
        mwmPaths.remove(mwmName)
        mwmTotalSizes.remove(mwmName)
        mountedMwms.remove(mwmName)
        mwmBlockTrackers.remove(mwmName)
        mwmPendingTrackers.remove(mwmName)
        mwmBlockAccess.remove(mwmName)
        mwmPinnedBlocks.remove(mwmName)
        nativeMountedMwms.remove(mwmName)
        saveRunnables.remove(mwmName)?.let { saveHandler.removeCallbacks(it) }
        metadataFailures.remove(mwmName)

        // Remove the sparse cache files so nothing is left to restore on next launch.
        runCatching { File(path).delete() }
        runCatching { File("$path.bits").delete() }

        // Re-scan so the engine drops the now-missing map within this session.
        runCatching { ReloadWorldMapsDebouncer.reload() }
        return true
    }

    /** Locate an on-disk sparse file (with sibling .bits) for a not-currently-mounted map. */
    private fun findSparseFile(context: Context, mwmName: String): File? {
        return try {
            val storagePath = StoragePathManager.findMapsStorage(context) ?: return null
            File(storagePath)
                .listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
                ?.asSequence()
                ?.map { File(it, "$mwmName.mwm") }
                ?.firstOrNull { it.exists() && File("${it.absolutePath}.bits").exists() }
        } catch (e: Exception) {
            Log.w(TAG, "findSparseFile failed for $mwmName: ${e.message}")
            null
        }
    }

    fun markMetadataFailure(mwmNameWithExt: String) {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        metadataFailures[mwmName] = System.currentTimeMillis()
    }

    fun shouldRequestMetadata(mwmNameWithExt: String): Boolean {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        val lastFailure = metadataFailures[mwmName] ?: return true
        return System.currentTimeMillis() - lastFailure > 60000
    }

    /**
     * The phone just reported the set of maps it now has downloaded. Drop the streaming back-off for
     * any of them and un-latch missingMapId if it's now present, so the periodic mount loop re-requests
     * metadata on its next tick (≤5s) instead of waiting out the 60s failure window. Without this a map
     * downloaded on the phone after the watch gave up would never stream until the back-off expired.
     */
    fun onPhoneMapsAvailable(ids: Set<String>) {
        if (ids.isEmpty()) return
        val normalized = ids.mapNotNull { MapIdUtils.normalize(it) }.toSet()
        for (id in normalized) metadataFailures.remove(id)
        val missing = NavigationStateHolder.state.value.missingMapId
        if (missing != null && MapIdUtils.normalize(missing) in normalized) {
            NavigationStateHolder.update { it.copy(missingMapId = null) }
        }
        // Unlike onBytesReceived (which reloads once ITS streamed map's footer arrives), this fires on
        // the phone's download-list broadcast alone, with no bytes streamed yet — so a still-blank
        // World map on the watch leaves nativeFindCountry() returning null forever (it needs World's
        // country polygons to resolve a viewport coordinate to a country id), and the periodic mount
        // loop in MapPanel can never even issue a metadata request. Reload now so the engine re-scans
        // and the World map — if the phone just got it for the first time — becomes visible without
        // requiring an app restart.
        ReloadWorldMapsDebouncer.reload()
    }

    /**
     * Synchronous restore called from Application.onCreate before Framework init.
     * Must complete before the framework's RegisterAllMaps scans the maps dir,
     * otherwise sparse files get registered with a plain FileReader full of holes.
     */
    fun restoreMountsEarly(context: Context) {
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: restoreMountsEarly - Restoring virtual mounts (blocking)")
        runBlocking {
            try {
                val storagePath = StoragePathManager.findMapsStorage(context)
                if (storagePath.isNullOrEmpty()) return@runBlocking
                val storageDir = File(storagePath)

                val versionDirs = storageDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()

                val now = System.currentTimeMillis()
                val maxAgeMs = 7 * 24 * 60 * 60 * 1000L

                versionDirs.forEach { versionDir ->
                    versionDir.listFiles()?.forEach { file ->
                        if (file.name.endsWith(".mwm")) {
                            val mwmName = file.name.substringBeforeLast(".")
                            val bitsFile = File(file.absolutePath + ".bits")

                            if (bitsFile.exists()) {
                                // Prune if older than 7 days
                                if (now - file.lastModified() > maxAgeMs) {
                                    Log.i(TAG, "DEBUG_WEAR_PIPELINE: Pruning old virtual file: ${file.name}")
                                    file.delete()
                                    bitsFile.delete()
                                    return@forEach
                                }

                                Log.i(TAG, "DEBUG_WEAR_PIPELINE: Restoring early mount for partial file: $mwmName")
                                // Isolate per-file failures: a single un-mountable sparse file must
                                // never abort the whole restore (or be left to crash the render thread
                                // later). If it can't be mounted, delete it to self-heal.
                                val mounted = try {
                                    doMount(mwmName, file, file.length(), null, null)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "DEBUG_WEAR_PIPELINE: doMount threw for $mwmName: ${e.message}")
                                    null
                                }
                                if (mounted == null) {
                                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: removing un-mountable sparse file: $mwmName")
                                    runCatching { file.delete() }
                                    runCatching { bitsFile.delete() }
                                }
                            }
                        } else if (file.name.endsWith(".bits")) {
                            val mwmFile = File(file.absolutePath.substringBeforeLast("."))
                            if (!mwmFile.exists()) {
                                Log.i(TAG, "DEBUG_WEAR_PIPELINE: Deleting orphan .bits file: ${file.name}")
                                file.delete()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DEBUG_WEAR_PIPELINE: restoreMountsEarly failed: ${e.message}")
            }
        }
    }

    private suspend fun doMount(mwmName: String, mwmFile: File, totalSize: Long, headerData: ByteArray?, footerData: ByteArray?): String? {
        try {
            val path = mwmFile.absolutePath
            
            if (mwmFile.exists() && mwmFile.length() != totalSize) {
                mwmFiles.remove(mwmName)?.let { raf ->
                    try { withContext(Dispatchers.IO) { raf.close() } } catch (_: Exception) {}
                }
                mwmFile.delete()
                File("$path.bits").delete()
                mwmBlockTrackers.remove(mwmName)
            }

            val raf = withContext(Dispatchers.IO) {
                val r = RandomAccessFile(mwmFile, "rw")
                if (r.length() != totalSize) {
                    r.setLength(totalSize)
                }
                r
            }

            if (!mwmBlockTrackers.containsKey(mwmName)) {
                val loaded = loadTracker(path) ?: BitSet()
                mwmBlockTrackers.putIfAbsent(mwmName, loaded)
            }

            mwmFiles.put(mwmName, raf)?.let { old -> runCatching { old.close() } }
            mwmPaths[mwmName] = path
            mwmTotalSizes[mwmName] = totalSize
            mountedMwms.add(mwmName)

            // Account for blocks already resident on disk (restored from .bits) toward the budget,
            // and pin the container header (first block) and footer (last block) so the directory
            // structure is never evicted.
            initResidentFromTracker(mwmName, totalSize)
            mwmPinnedBlocks.getOrPut(mwmName) { BitSet() }.let { pinned ->
                val lastBlk = ((totalSize - 1) / BLOCK_SIZE).toInt()
                synchronized(pinned) { pinned.set(0); pinned.set(lastBlk) }
            }
            
            if (headerData != null && headerData.isNotEmpty()) {
                synchronized(raf) {
                    raf.seek(0)
                    raf.write(headerData)
                }
                markDataReceived(mwmName, 0, headerData.size)
            }

            if (footerData != null && footerData.isNotEmpty()) {
                val footerOffset = totalSize - footerData.size
                synchronized(raf) {
                    raf.seek(footerOffset)
                    raf.write(footerData)
                }
                markDataReceived(mwmName, footerOffset, footerData.size)
            }

            // Await native library before notifications
            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Awaiting native library for $mwmName")
            nativeLibraryLoaded.await()
            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Native library ready, notifying mount for $mwmName")

            val mountedDeferred = nativeMountedMwms.getOrPut(mwmName) { CompletableDeferred() }
            
            // Native notification and replay tracker
            nativeNotifyMounted(mwmName, path, totalSize)
            mountedDeferred.complete(Unit)

            // Pin header/footer natively too (RegisterVirtualMwm has now sized the pinned bitmap),
            // so they are protected even if read via a direct mmap on some path.
            if (headerData != null && headerData.isNotEmpty())
                nativePinData(mwmName, 0L, headerData.size.toLong())
            if (footerData != null && footerData.isNotEmpty())
                nativePinData(mwmName, totalSize - footerData.size, footerData.size.toLong())
            
            val tracker = mwmBlockTrackers[mwmName]
            if (tracker != null) {
                var bit = tracker.nextSetBit(0)
                while (bit != -1) {
                    val nextClear = tracker.nextClearBit(bit)
                    val runOffset = bit.toLong() * BLOCK_SIZE
                    var runLen = (nextClear - bit).toLong() * BLOCK_SIZE
                    
                    if (runOffset + runLen > totalSize) runLen = totalSize - runOffset
                    
                    var remaining = runLen
                    var currentOffset = runOffset
                    while (remaining > 0) {
                        val chunk = remaining.coerceAtMost(1024 * 1024 * 1024).toInt()
                        nativeDataArrived(mwmName, currentOffset, chunk)
                        remaining -= chunk
                        currentOffset += chunk
                    }
                    
                    bit = tracker.nextSetBit(nextClear)
                }
            }
            
            val lastBlock = ((totalSize - 1) / BLOCK_SIZE).toInt()
            if (tracker == null || !tracker.get(lastBlock)) {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Footer missing for $mwmName, requesting last block")
                val footerOffset = lastBlock.toLong() * BLOCK_SIZE
                val footerSize = (totalSize - footerOffset).toInt()
                markPending(mwmName, footerOffset, footerSize)
                WearCommandService.requestMwmBytes(WearApplication.instance, mwmName, footerOffset, footerSize)
            } else {
                ReloadWorldMapsDebouncer.reload()
            }
            
            return path
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: doMount failed for $mwmName: ${e.message}")
            return null
        }
    }

    suspend fun mount(context: Context, mwmNameWithExt: String, totalSize: Long, headerData: ByteArray? = null, footerData: ByteArray? = null): String? {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Mounting virtual MWM: $mwmName, size: $totalSize")
        
        if (mountedMwms.contains(mwmName)) {
            unmount(mwmName)
        }
        
        metadataFailures.remove(mwmName)

        return withContext(Dispatchers.IO) {
            try {
                val storageDir = File(StoragePathManager.findMapsStorage(context))
                val dataVersion = withContext(Dispatchers.Main) { Framework.nativeGetDataVersion() }
                val versionDir = File(storageDir, dataVersion.toString())
                if (!versionDir.exists()) {
                    versionDir.mkdirs()
                }
                
                val mwmFile = File(versionDir, "$mwmName.mwm")
                val path = doMount(mwmName, mwmFile, totalSize, headerData, footerData)
                
                return@withContext path
            } catch (e: Exception) {
                Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to mount virtual MWM $mwmName: ${e.message}", e)
                null
            }
        }
    }

    @JvmStatic
    external fun nativeDataArrived(mwmName: String, offset: Long, size: Int)
    @JvmStatic
    external fun nativeNotifyMounted(mwmName: String, path: String, totalSize: Long)
    @JvmStatic
    external fun nativePinData(mwmName: String, offset: Long, size: Long)
    @JvmStatic
    external fun nativeInvalidateData(mwmName: String, offset: Long, size: Long): Boolean
    @JvmStatic
    external fun nativePunchHole(path: String, offset: Long, size: Long): Boolean
}
