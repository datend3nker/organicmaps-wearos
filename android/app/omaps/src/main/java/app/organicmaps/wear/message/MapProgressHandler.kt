package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.WearMapDownloader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class MapProgressHandler : WearMessageHandler {
    companion object {
        private const val TAG = "MapProgressHandler"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val countryLen = if (buffer.remaining() >= 4) buffer.int else 0
        if (countryLen > 0 && buffer.remaining() >= countryLen + 4) {
            val countryBytes = ByteArray(countryLen)
            buffer.get(countryBytes)
            val countryId = String(countryBytes, StandardCharsets.UTF_8)
            val progress = buffer.int
            Log.d(TAG, "Received map progress via Bluetooth: $countryId -> $progress%")
            if (countryId == WearMapDownloader.currentMap.value) {
                WearMapDownloader.setStreamingProgress(progress / 100f)
            }
        }
    }
}
