package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.ReloadWorldMapsDebouncer
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearMapDownloader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class MapChunkHandler(
    private val mapOutputStreams: MutableMap<String, FileOutputStream>
) : WearMessageHandler {
    companion object {
        private const val TAG = "MapChunkHandler"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 5) return
        val mapIdLen = buffer.int
        val mapIdBytes = ByteArray(mapIdLen)
        buffer.get(mapIdBytes)
        val mapId = String(mapIdBytes, StandardCharsets.UTF_8)
        val isLast = buffer.get().toInt() == 1
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        
        saveMapChunk(context, mapId, chunk, isLast)
    }

    private fun saveMapChunk(context: Context, mapId: String, data: ByteArray, isLast: Boolean) {
        val currentState = WearMapDownloader.downloadState.value
        val currentMap = WearMapDownloader.currentMap.value
        
        if (mapId == currentMap && (currentState == WearMapDownloader.DownloadState.CANCELLED || currentState == WearMapDownloader.DownloadState.COMPLETED)) {
            Log.d(TAG, "Ignoring chunk for map $mapId because state is $currentState")
            return
        }

        try {
            val wearApp = context.applicationContext as WearApplication
            wearApp.waitForInitializationBlocking()
            
            val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(context)
            val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
            val versionedPath = File(storagePath, dataVersion.toString())
            if (!versionedPath.exists()) versionedPath.mkdirs()

            // Calculate offset based on current file length or track it?
            // Since it's a stream, we can probably just keep track of bytes written.
            // But wait, if it's already mounted as a virtual MWM, we should use RandomAccessFile or similar.
            
            // Optimization: if it's mounted, write to it via VirtualMwmManager
            if (app.organicmaps.wear.VirtualMwmManager.isMounted(mapId)) {
                // We need to know the offset. Assuming sequential stream:
                val offsetKey = "stream_offset_$mapId"
                val prefs = context.getSharedPreferences("streaming_prefs", Context.MODE_PRIVATE)
                val offset = prefs.getLong(offsetKey, 0L)
                
                app.organicmaps.wear.VirtualMwmManager.onBytesReceived(mapId, offset, data)
                prefs.edit().putLong(offsetKey, offset + data.size).apply()
                
                if (isLast) {
                    prefs.edit().remove(offsetKey).apply()
                    WearMapDownloader.onDownloadCompleted()
                    ReloadWorldMapsDebouncer.reload()
                }
                return
            }

            val fos = mapOutputStreams.getOrPut(mapId) {
                val file = File(versionedPath, "$mapId.mwm.tmp")
                WearMapDownloader.setStreamingMap(mapId)
                FileOutputStream(file)
            }
            fos.write(data)
            if (isLast) {
                fos.close()
                mapOutputStreams.remove(mapId)
                val tmpFile = File(versionedPath, "$mapId.mwm.tmp")
                val finalFile = File(versionedPath, "$mapId.mwm")
                tmpFile.renameTo(finalFile)
                WearMapDownloader.onDownloadCompleted()
                Log.d(TAG, "Successfully received map via Bluetooth: $mapId")
                
                ReloadWorldMapsDebouncer.reload()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save map chunk: ${e.message}")
            try {
                mapOutputStreams.remove(mapId)?.close()
                val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(context)
                val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
                val tempFile = File(File(storagePath, dataVersion.toString()), "$mapId.mwm.tmp")
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Throwable) {}
            WearMapDownloader.onDownloadCancelled()
        }
    }
}
