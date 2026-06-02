package app.organicmaps.wear.message

import android.content.Context
import java.nio.ByteBuffer

interface WearMessageHandler {
    fun handle(buffer: ByteBuffer, data: ByteArray, context: Context)
}
