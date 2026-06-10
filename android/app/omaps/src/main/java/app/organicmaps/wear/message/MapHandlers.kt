package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class DownloadedMapsHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 4) return
        val count = buffer.int
        val maps = mutableSetOf<String>()
        Log.d("DownloadedMapsHandler", "Received phone downloaded maps list: $count items")
        for (i in 0 until count) {
            if (buffer.remaining() < 4) break
            val len = buffer.int
            if (buffer.remaining() < len) break
            val b = ByteArray(len)
            buffer.get(b)
            maps.add(String(b, StandardCharsets.UTF_8))
        }
        NavigationStateHolder.update { it.copy(phoneDownloadedMaps = maps) }
    }
}
