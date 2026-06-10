package app.organicmaps.wear

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.Framework
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manage the local sparse-cache and bridge the JNI calls to the Transport layer.
 */
object VirtualMwmManager {
    private const val TAG = "VirtualMwmManager"
    private const val BLOCK_SIZE = 64 * 1024 // 64KB blocks for tracking
    
    private val mwmFiles = ConcurrentHashMap<String, RandomAccessFile>()
    private val mwmPaths = ConcurrentHashMap<String, String>()
    private val mountedMwms = ConcurrentHashMap.newKeySet<String>()
    private val mwmBlockTrackers = ConcurrentHashMap<String, BitSet>()
    private val mwmPendingTrackers = ConcurrentHashMap<String, BitSet>()
    
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnables = ConcurrentHashMap<String, Runnable>()

    fun isMounted(mwmNameWithExt: String): Boolean {
        val mwmName = if (mwmNameWithExt.endsWith(".mwm")) mwmNameWithExt.substringBeforeLast(".") else mwmNameWithExt
        return mountedMwms.contains(mwmName)
    }

    @JvmStatic
    fun onDataRequired(mwmNameWithExt: String, offset: Long, size: Int) {
        try {
            val mwmName = if (mwmNameWithExt.endsWith(".mwm")) mwmNameWithExt.substringBeforeLast(".") else mwmNameWithExt
            // Efficiency: Check what we actually need from this range
            val tracker = mwmBlockTrackers.getOrPut(mwmName) { BitSet() }
            val pending = mwmPendingTrackers.getOrPut(mwmName) { BitSet() }
            
            val startBlock = (offset / BLOCK_SIZE).toInt()
            val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
            
            var anyMissing = false
            synchronized(tracker) {
                synchronized(pending) {
                    for (i in startBlock..endBlock) {
                        if (!tracker.get(i) && !pending.get(i)) {
                            anyMissing = true
                            break
                        }
                    }
                }
            }

            if (!anyMissing) {
                if (hasData(mwmName, offset, size)) {
                    Log.v(TAG, "DEBUG_WEAR_PIPELINE: Native requested data already present for $mwmName: offset=$offset, size=$size")
                    // Use launch to avoid recursion on the same thread
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            nativeDataArrived(mwmName, offset, size)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Native crash in arrived data: ${e.message}")
                        }
                    }
                } else {
                    Log.v(TAG, "DEBUG_WEAR_PIPELINE: Native requested data pending for $mwmName: offset=$offset, size=$size")
                }
                return
            }

            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Native requested data from phone: $mwmName, offset=$offset, size=$size (Blocks: $startBlock-$endBlock)")
            
            // BACKBURNER: Skip chunk requests if map is already being fully pulled
            if (WearMapDownloader.currentMap.value == mwmName && 
                WearMapDownloader.downloadState.value == WearMapDownloader.DownloadState.STREAMING_FROM_PHONE) {
                Log.v(TAG, "DEBUG_WEAR_PIPELINE: Skipping chunk request for map currently being fully PULLED: $mwmName")
                return
            }

            markPending(mwmName, offset, size)
            
            val context = WearApplication.instance
            WearCommandService.requestMwmBytes(context, mwmName, offset, size)
            
            // Resilience: timeout to clear pending state if phone doesn't respond
            saveHandler.postDelayed({
                if (isPending(mwmName, offset, size)) {
                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: Request TIMEOUT for $mwmName at $offset ($size)")
                    clearPending(mwmName, offset, size)
                }
            }, 15000) // 15s timeout
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in onDataRequired: ${e.message}", e)
        }
    }

    @JvmStatic
    @Synchronized
    fun onBytesReceived(mwmNameWithExt: String, offset: Long, data: ByteArray) {
        val mwmName = if (mwmNameWithExt.endsWith(".mwm")) mwmNameWithExt.substringBeforeLast(".") else mwmNameWithExt
        clearPending(mwmName, offset, data.size.toInt())

        if (hasData(mwmName, offset, data.size)) {
            Log.v(TAG, "DEBUG_WEAR_PIPELINE: Ignoring duplicate data for $mwmName at $offset (size=${data.size})")
            return
        }

        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Received data for $mwmName, offset: $offset, size: ${data.size}")
        try {
            val raf = mwmFiles[mwmName]
            if (raf != null) {
                synchronized(raf) {
                    raf.seek(offset)
                    raf.write(data)
                }
                markDataReceived(mwmName, offset, data.size)
                
                // FIX: BREAK THE CALL CHAIN - Use async dispatch to avoid native re-entrancy
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        nativeDataArrived(mwmName, offset, data.size)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Native crash prevented in nativeDataArrived: ${e.message}")
                    }
                }
                
                // If this is the footer, trigger a map reload so the engine can pick it up
                val totalSize = raf.length()
                val lastBlock = ((totalSize - 1) / BLOCK_SIZE).toInt()
                val receivedStartBlock = (offset / BLOCK_SIZE).toInt()
                val receivedEndBlock = ((offset + data.size - 1) / BLOCK_SIZE).toInt()
                
                if (receivedStartBlock <= lastBlock && receivedEndBlock >= lastBlock) {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Footer arrived for $mwmName, triggering reload")
                    ReloadWorldMapsDebouncer.reload()
                }
            } else {
                Log.w(TAG, "DEBUG_WEAR_PIPELINE: Received data for UNMOUNTED MWM: $mwmName at $offset")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to write MWM data for $mwmName at $offset: ${e.message}", e)
        }
    }

    private fun hasData(mwmName: String, offset: Long, size: Int): Boolean {
        val tracker = mwmBlockTrackers[mwmName] ?: return false
        val startBlock = (offset / BLOCK_SIZE).toInt()
        val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
        
        synchronized(tracker) {
            for (i in startBlock..endBlock) {
                if (!tracker.get(i)) return false
            }
        }
        return true
    }

    private fun markDataReceived(mwmName: String, offset: Long, size: Int) {
        val tracker = mwmBlockTrackers.getOrPut(mwmName) { BitSet() }
        val startBlock = (offset / BLOCK_SIZE).toInt()
        val endBlock = ((offset + size - 1) / BLOCK_SIZE).toInt()
        
        var changed = false
        synchronized(tracker) {
            for (i in startBlock..endBlock) {
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
            saveHandler.postDelayed(it, 5000) // Debounce 5s
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
            val tracker = BitSet.valueOf(bytes)
            synchronized(tracker) {
                // Ensure initial state is loaded under synchronization if ever used immediately
                return tracker
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitset for $mwmPath: ${e.message}")
            return null
        }
    }

    fun unmount(mwmNameWithExt: String) {
        val mwmName = if (mwmNameWithExt.endsWith(".mwm")) mwmNameWithExt.substringBeforeLast(".") else mwmNameWithExt
        Log.d(TAG, "Unmounting virtual MWM: $mwmName")
        try {
            mwmFiles.remove(mwmName)?.close()
            mwmPaths.remove(mwmName)
            mountedMwms.remove(mwmName)
            mwmBlockTrackers.remove(mwmName)
            mwmPendingTrackers.remove(mwmName)
            saveRunnables.remove(mwmName)?.let { saveHandler.removeCallbacks(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error during unmount of $mwmName: ${e.message}")
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up all virtual MWMs")
        mountedMwms.toList().forEach { unmount(it) }
    }

    /**
     * Delete old virtual maps and bitsets that haven't been modified recently.
     */
    fun prune(context: Context, maxAgeDays: Int = 7) {
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Pruning virtual files...")
        try {
            val storageDir = File(StoragePathManager.findMapsStorage(context))
            
            // Try to get version from native, fallback to scanning directories if native not ready
            val dataVersion = try {
                val v = Framework.nativeGetDataVersion()
                if (v > 0) v.toString() else null
            } catch (e: Throwable) { null }

            val versionDirs = if (dataVersion != null) {
                arrayOf(File(storageDir, dataVersion))
            } else {
                storageDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()
            }

            val now = System.currentTimeMillis()
            val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L
            
            versionDirs.forEach { versionDir ->
                versionDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".mwm")) {
                        val mwmName = file.name.substringBefore(".")
                        if (isMounted(mwmName)) return@forEach
                        
                        // VALIDATION: Check if this file was fully streamed or at least has the footer.
                        // If not, it will cause "IO error" on next scan.
                        val bitsFile = File(file.absolutePath + ".bits")
                        val isComplete = if (bitsFile.exists()) {
                            try {
                                val bytes = bitsFile.readBytes()
                                val tracker = BitSet.valueOf(bytes)
                                val lastBlock = ((file.length() - 1) / BLOCK_SIZE).toInt()
                                lastBlock >= 0 && tracker.get(lastBlock)
                            } catch (e: Exception) { false }
                        } else {
                            // No bits file but .mwm exists? Might be a real download or a failure.
                            // If it's small, it's likely a failed virtual mount.
                            file.length() > 1024 * 1024 // assume >1MB is real map
                        }

                        if (!isComplete) {
                            Log.i(TAG, "DEBUG_WEAR_PIPELINE: Deleting incomplete virtual file: ${file.name}")
                            file.delete()
                            if (bitsFile.exists()) bitsFile.delete()
                        } else if (now - file.lastModified() > maxAgeMs) {
                            Log.i(TAG, "DEBUG_WEAR_PIPELINE: Pruning old virtual file: ${file.name}")
                            file.delete()
                            if (bitsFile.exists()) bitsFile.delete()
                        }
                    } else if (file.name.endsWith(".bits")) {
                        // Cleanup orphan bits files
                        val mwmFile = File(file.absolutePath.substringBeforeLast("."))
                        if (!mwmFile.exists()) file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Pruning failed: ${e.message}")
        }
    }

    fun mount(context: Context, mwmNameWithExt: String, totalSize: Long, headerData: ByteArray? = null, footerData: ByteArray? = null): String? {
        val mwmName = if (mwmNameWithExt.endsWith(".mwm")) mwmNameWithExt.substringBeforeLast(".") else mwmNameWithExt
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Mounting virtual MWM: $mwmName, size: $totalSize (Header: ${headerData?.size ?: 0}, Footer: ${footerData?.size ?: 0})")
        // Resilience: If already mounted, unmount first to ensure clean state
        if (mountedMwms.contains(mwmName)) {
            unmount(mwmName)
        }
        try {
            val storageDir = File(StoragePathManager.findMapsStorage(context))
            val dataVersion = Framework.nativeGetDataVersion()
            val versionDir = File(storageDir, dataVersion.toString())
            if (!versionDir.exists()) {
                Log.d(TAG, "Creating version directory: ${versionDir.absolutePath}")
                versionDir.mkdirs()
            }
            
            val mwmFile = File(versionDir, "$mwmName.mwm")
            val path = mwmFile.absolutePath
            
            // Resilience: If file exists but size differs, reset it
            if (mwmFile.exists() && mwmFile.length() != totalSize) {
                Log.w(TAG, "DEBUG_WEAR_PIPELINE: MWM file size mismatch ($mwmName), resetting: $path (Expected: $totalSize, Actual: ${mwmFile.length()})")
                mwmFiles.remove(mwmName)?.close()
                mwmFile.delete()
                File("$path.bits").delete()
                mwmBlockTrackers.remove(mwmName)
            }

            val raf = RandomAccessFile(mwmFile, "rw")
            try {
                if (raf.length() != totalSize) {
                    Log.d(TAG, "Setting length for $mwmName to $totalSize")
                    raf.setLength(totalSize)
                }

                // Restore tracker from disk or create new BEFORE marking as mounted
                if (!mwmBlockTrackers.containsKey(mwmName)) {
                    val loaded = loadTracker(path) ?: BitSet()
                    mwmBlockTrackers.putIfAbsent(mwmName, loaded)
                }

                mwmFiles[mwmName] = raf
                mwmPaths[mwmName] = path
                mountedMwms.add(mwmName)
                
                // Write header if provided
                if (headerData != null && headerData.isNotEmpty()) {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Writing received header for $mwmName")
                    synchronized(raf) {
                        raf.seek(0)
                        raf.write(headerData)
                    }
                    markDataReceived(mwmName, 0, headerData.size)
                }

                // Write footer if provided
                if (footerData != null && footerData.isNotEmpty()) {
                    val footerOffset = totalSize - footerData.size
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Writing received footer for $mwmName at $footerOffset")
                    synchronized(raf) {
                        raf.seek(footerOffset)
                        raf.write(footerData)
                    }
                    markDataReceived(mwmName, footerOffset, footerData.size)
                }

                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Notifying native core about mounted virtual MWM: $mwmName")
                CoroutineScope(Dispatchers.Main).launch {
                    nativeNotifyMounted(mwmName, path, totalSize)
                }
                
                // CRITICAL: Ensure the MWM footer (section index) is available before reloading maps.
                // The native FilesContainer needs the footer to open the file.
                val lastBlock = ((totalSize - 1) / BLOCK_SIZE).toInt()
                val tracker = mwmBlockTrackers[mwmName]
                if (tracker != null && !tracker.get(lastBlock)) {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Footer missing for $mwmName, requesting last block ($lastBlock)")
                    val footerOffset = (lastBlock * BLOCK_SIZE).toLong()
                    val footerSize = (totalSize - footerOffset).toInt()
                    markPending(mwmName, footerOffset, footerSize)
                    WearCommandService.requestMwmBytes(context, mwmName, footerOffset, footerSize)
                } else {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Mount successful for $mwmName, reloading world maps")
                    ReloadWorldMapsDebouncer.reload()
                }
                
                return path
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to mount virtual MWM $mwmName: ${e.message}", e)
            return null
        }
    }

    @JvmStatic
    external fun nativeDataArrived(mwmName: String, offset: Long, size: Int)
    @JvmStatic
    external fun nativeNotifyMounted(mwmName: String, path: String, totalSize: Long)
}
