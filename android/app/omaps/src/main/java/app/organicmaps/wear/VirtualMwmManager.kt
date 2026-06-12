package app.organicmaps.wear

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.util.MapIdUtils
import kotlinx.coroutines.*

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

    fun setNativeLibraryLoaded() {
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Native library signal received")
        nativeLibraryLoaded.complete(Unit)
    }

    fun isMounted(mwmNameWithExt: String): Boolean {
        val normalized = MapIdUtils.normalize(mwmNameWithExt)
        return normalized != null && mountedMwms.contains(normalized)
    }

    @JvmStatic
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
                
                val totalSize = mwmTotalSizes[mwmName] ?: try { raf.length() } catch (e: Exception) { 0L }
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
        synchronized(tracker) {
            for (i in startBlock until endBlock) {
                if (!tracker.get(i)) {
                    tracker.set(i)
                    changed = true
                }
            }
        }
        
        if (changed) {
            scheduleSave(mwmName)
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
        try {
            val bytes = bitsFile.readBytes()
            return BitSet.valueOf(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitset for $mwmPath: ${e.message}")
            return null
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
            mwmPaths.remove(mwmName)
            mwmTotalSizes.remove(mwmName)
            mountedMwms.remove(mwmName)
            mwmBlockTrackers.remove(mwmName)
            mwmPendingTrackers.remove(mwmName)
            nativeMountedMwms.remove(mwmName)
            saveRunnables.remove(mwmName)?.let { saveHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error during unmount of $mwmName: ${e.message}")
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up all virtual MWMs")
        mountedMwms.toList().forEach { unmount(it) }
    }

    fun markMetadataFailure(mwmNameWithExt: String) {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        metadataFailures[mwmName] = System.currentTimeMillis()
    }

    fun clearMetadataFailure(mwmNameWithExt: String) {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        metadataFailures.remove(mwmName)
    }

    fun shouldRequestMetadata(mwmNameWithExt: String): Boolean {
        val mwmName = MapIdUtils.normalize(mwmNameWithExt)!!
        val lastFailure = metadataFailures[mwmName] ?: return true
        return System.currentTimeMillis() - lastFailure > 60000 
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
                                doMount(mwmName, file, file.length(), null, null)
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
}
