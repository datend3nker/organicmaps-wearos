package app.organicmaps.wear

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.Framework

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

    fun isMounted(mwmName: String): Boolean = mountedMwms.contains(mwmName)

    @JvmStatic
    fun onDataRequired(mwmName: String, offset: Long, size: Int) {
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
                nativeDataArrived(mwmName, offset, size)
            }
            return
        }

        Log.d(TAG, "Native requested data: $mwmName, offset: $offset, size: $size")
        markPending(mwmName, offset, size)
        
        val context = WearApplication.instance
        WearCommandService.requestMwmBytes(context, mwmName, offset, size)
        
        // Resilience: timeout to clear pending state if phone doesn't respond
        saveHandler.postDelayed({
            clearPending(mwmName, offset, size)
        }, 15000) // 15s timeout
    }

    @JvmStatic
    fun onBytesReceived(mwmName: String, offset: Long, data: ByteArray) {
        clearPending(mwmName, offset, data.size)

        if (hasData(mwmName, offset, data.size)) {
            Log.v(TAG, "Ignoring duplicate data for $mwmName at $offset")
            return
        }

        Log.d(TAG, "Received data for $mwmName, offset: $offset, size: ${data.size}")
        try {
            val raf = mwmFiles[mwmName]
            if (raf != null) {
                synchronized(raf) {
                    raf.seek(offset)
                    raf.write(data)
                }
                markDataReceived(mwmName, offset, data.size)
                nativeDataArrived(mwmName, offset, data.size)
            } else {
                Log.w(TAG, "Received data for unmounted MWM: $mwmName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write MWM data: ${e.message}")
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

    fun unmount(mwmName: String) {
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
        try {
            val storageDir = File(StoragePathManager.findMapsStorage(context))
            val dataVersion = Framework.nativeGetDataVersion()
            val versionDir = File(storageDir, dataVersion.toString())
            if (!versionDir.exists()) return

            val now = System.currentTimeMillis()
            val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L
            
            versionDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".mwm") || file.name.endsWith(".bits")) {
                    val mwmName = file.name.substringBefore(".")
                    if (isMounted(mwmName)) return@forEach
                    
                    if (now - file.lastModified() > maxAgeMs) {
                        Log.i(TAG, "Pruning old virtual file: ${file.name}")
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pruning failed: ${e.message}")
        }
    }

    fun mount(context: Context, mwmName: String, totalSize: Long): String? {
        Log.d(TAG, "Mounting virtual MWM: $mwmName, size: $totalSize")
        // Resilience: If already mounted, unmount first to ensure clean state
        if (mountedMwms.contains(mwmName)) {
            unmount(mwmName)
        }
        try {
            val storageDir = File(StoragePathManager.findMapsStorage(context))
            val dataVersion = Framework.nativeGetDataVersion()
            val versionDir = File(storageDir, dataVersion.toString())
            if (!versionDir.exists()) versionDir.mkdirs()
            
            val mwmFile = File(versionDir, "$mwmName.mwm")
            val path = mwmFile.absolutePath
            
            // Resilience: If file exists but size differs, reset it
            if (mwmFile.exists() && mwmFile.length() != totalSize) {
                Log.w(TAG, "MWM file size mismatch, resetting: $path")
                mwmFile.delete()
                File("$path.bits").delete()
                mwmBlockTrackers.remove(mwmName)
            }

            val raf = RandomAccessFile(mwmFile, "rw")
            if (mwmFile.length() != totalSize) {
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
            
            nativeNotifyMounted(mwmName, path, totalSize)
            return path
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mount virtual MWM: ${e.message}")
            return null
        }
    }

    private external fun nativeDataArrived(mwmName: String, offset: Long, size: Int)
    private external fun nativeNotifyMounted(mwmName: String, path: String, totalSize: Long)
}
