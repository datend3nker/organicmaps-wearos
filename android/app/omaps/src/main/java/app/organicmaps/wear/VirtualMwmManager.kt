package app.organicmaps.wear

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Manage the local sparse-cache and bridge the JNI calls to the Transport layer.
 */
object VirtualMwmManager {
    private const val TAG = "VirtualMwmManager"
    private val mwmFiles = ConcurrentHashMap<String, RandomAccessFile>()

    @JvmStatic
    fun onDataRequired(mwmName: String, offset: Long, size: Int) {
        Log.d(TAG, "Native requested data: $mwmName, offset: $offset, size: $size")
        val context = WearApplication.instance
        WearCommandService.requestMwmBytes(context, mwmName, offset, size)
    }

    @JvmStatic
    fun onBytesReceived(mwmName: String, offset: Long, data: ByteArray) {
        Log.d(TAG, "Received data for $mwmName, offset: $offset, size: ${data.size}")
        try {
            val raf = mwmFiles[mwmName]
            if (raf != null) {
                synchronized(raf) {
                    raf.seek(offset)
                    raf.write(data)
                }
                nativeDataArrived(mwmName, offset, data.size)
            } else {
                Log.w(TAG, "Received data for unmounted MWM: $mwmName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write MWM data: ${e.message}")
        }
    }

    fun mount(context: Context, mwmName: String, totalSize: Long): String? {
        Log.d(TAG, "Mounting virtual MWM: $mwmName, size: $totalSize")
        try {
            val storageDir = context.getExternalFilesDir(null) ?: context.filesDir
            val mwmFile = File(storageDir, "$mwmName.mwm")
            val raf = RandomAccessFile(mwmFile, "rw")
            if (mwmFile.length() != totalSize) {
                raf.setLength(totalSize)
            }
            mwmFiles[mwmName] = raf
            
            nativeNotifyMounted(mwmName, mwmFile.absolutePath, totalSize)
            return mwmFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mount virtual MWM: ${e.message}")
            return null
        }
    }

    private external fun nativeDataArrived(mwmName: String, offset: Long, size: Int)
    private external fun nativeNotifyMounted(mwmName: String, path: String, totalSize: Long)
}
