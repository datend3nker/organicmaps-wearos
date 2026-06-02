package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.wear.NavigationStateHolder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class SearchHistoryHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val count = buffer.int
        val history = mutableListOf<String>()
        repeat(count) {
            val len = buffer.int
            val s = String(data, buffer.position(), len, StandardCharsets.UTF_8)
            buffer.position(buffer.position() + len)
            history.add(s)
        }
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(searchHistory = history))
    }
}
