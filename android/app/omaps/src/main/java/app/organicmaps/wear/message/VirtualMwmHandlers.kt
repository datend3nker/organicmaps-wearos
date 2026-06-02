package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.wear.VirtualMwmManager
import app.organicmaps.sdk.util.GzipUtils
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class VirtualMwmDataHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val nameLen = buffer.int
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val mwmName = String(nameBytes, StandardCharsets.UTF_8)
        val offset = buffer.long
        
        // Efficiency: Check compression flag
        val isCompressed = if (buffer.remaining() > 0) buffer.get().toInt() == 1 else false
        var mwmData = ByteArray(buffer.remaining())
        buffer.get(mwmData)
        
        if (isCompressed) {
            try {
                mwmData = GzipUtils.decompress(mwmData)
            } catch (e: Exception) {
                android.util.Log.e("VirtualMwmDataHandler", "Failed to decompress data for $mwmName")
                return
            }
        }
        
        VirtualMwmManager.onBytesReceived(mwmName, offset, mwmData)
    }
}

class VirtualMwmMountHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val nameLen = buffer.int
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val mwmName = String(nameBytes, StandardCharsets.UTF_8)
        val totalSize = buffer.long
        VirtualMwmManager.mount(context, mwmName, totalSize)
    }
}
