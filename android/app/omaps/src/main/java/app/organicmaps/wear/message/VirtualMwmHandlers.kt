package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.sdk.util.GzipUtils
import app.organicmaps.wear.VirtualMwmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class VirtualMwmDataHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val nameLen = buffer.int
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val mwmName = String(nameBytes, StandardCharsets.UTF_8)
        val offset = buffer.long
        
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
        
        val headerData = if (buffer.remaining() >= 4) {
            val hLen = buffer.int
            if (hLen > 0 && buffer.remaining() >= hLen) {
                val hb = ByteArray(hLen)
                buffer.get(hb)
                hb
            } else null
        } else null
        
        val footerData = if (buffer.remaining() >= 4) {
            val fLen = buffer.int
            if (fLen > 0 && buffer.remaining() >= fLen) {
                val fb = ByteArray(fLen)
                buffer.get(fb)
                fb
            } else null
        } else null
        
        // FIX: native core initialization and mount checks must be on UI thread
        CoroutineScope(Dispatchers.Main).launch {
            VirtualMwmManager.mount(context, mwmName, totalSize, headerData, footerData)
        }
    }
}
