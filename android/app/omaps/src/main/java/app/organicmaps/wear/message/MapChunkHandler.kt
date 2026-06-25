package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.ReloadWorldMapsDebouncer
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearMapDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class MapChunkHandler : WearMessageHandler {
    companion object {
        private const val TAG = "MapChunkHandler"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 20) return // int(4) + long(8) + long(8)
        val mapIdLen = buffer.int
        if (buffer.remaining() < mapIdLen + 16) return
        val mapIdBytes = ByteArray(mapIdLen)
        buffer.get(mapIdBytes)
        val mapId = String(mapIdBytes, StandardCharsets.UTF_8)
        val offset = buffer.long
        val totalSize = buffer.long
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        
        saveMapChunk(context, mapId, chunk, offset, totalSize)
    }

    private fun saveMapChunk(context: Context, mapId: String, data: ByteArray, offset: Long, totalSize: Long) {
        val currentState = WearMapDownloader.downloadState.value
        val currentMap = WearMapDownloader.currentMap.value
        
        if (mapId == currentMap && (currentState == WearMapDownloader.DownloadState.CANCELLED || currentState == WearMapDownloader.DownloadState.COMPLETED)) {
            Log.d(TAG, "Ignoring chunk for map $mapId because state is $currentState")
            return
        }

        val wearApp = context.applicationContext as WearApplication
        CoroutineScope(Dispatchers.IO).launch {
            try {
                wearApp.waitForInitializationSuspend()
                
                val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(context)
                val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
                val versionedPath = File(storagePath, dataVersion.toString())
                if (!versionedPath.exists()) versionedPath.mkdirs()

                val tmpFile = File(versionedPath, "$mapId.mwm.tmp")
                java.io.RandomAccessFile(tmpFile, "rw").use { raf ->
                    if (offset == 0L && raf.length() > 0) {
                        Log.i(TAG, "Resetting .tmp file for $mapId (offset 0 received)")
                        raf.setLength(0)
                    }
                    raf.seek(offset)
                    raf.write(data)
                    
                    val currentSize = offset + data.size
                    val progress = currentSize.toFloat() / totalSize.toFloat()
                    WearMapDownloader.setStreamingProgress(progress)

                    if (currentSize >= totalSize) {
                        Log.d(TAG, "Map transfer complete: $mapId. Finalizing...")
                        
                        // Ensure the file is not busy before renaming and delete virtual map metadata/sidecar files
                        app.organicmaps.wear.VirtualMwmManager.deleteVirtual(context, "$mapId.mwm")
                        
                        val finalFile = File(versionedPath, "$mapId.mwm")
                        if (finalFile.exists()) finalFile.delete()
                        tmpFile.renameTo(finalFile)
                        
                        WearMapDownloader.onDownloadCompleted()
                        Log.d(TAG, "Successfully received map via Bluetooth: $mapId")
                        
                        ReloadWorldMapsDebouncer.reload()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save map chunk: ${e.message}")
                WearMapDownloader.onDownloadCancelled()
            }
        }
    }
}
