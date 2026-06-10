package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.wear.WearMessageRouter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class CommandHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val pathLen = if (buffer.remaining() >= 4) buffer.int else 0
        if (pathLen > 0 && buffer.remaining() >= pathLen) {
            val pathBytes = ByteArray(pathLen)
            buffer.get(pathBytes)
            val path = String(pathBytes, StandardCharsets.UTF_8)
            val cmdData = ByteArray(buffer.remaining())
            buffer.get(cmdData)
            WearMessageRouter.onMessageReceived(context, path, cmdData, "bluetooth_phone", null)
        }
    }
}
