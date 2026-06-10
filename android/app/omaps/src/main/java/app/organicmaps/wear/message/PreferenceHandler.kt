package app.organicmaps.wear.message

import android.content.Context
import app.organicmaps.wear.SettingsSyncManager
import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class PreferenceHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        if (buffer.remaining() < 4) return
        val updates = parseUpdates(buffer)
        if (updates.isNotEmpty()) {
            SettingsSyncManager.getInstance(context).applyRemoteUpdates(updates)
        }
    }

    private fun parseUpdates(buffer: ByteBuffer): List<BaseSettingsSyncManager.SettingUpdate> {
        val updates = mutableListOf<BaseSettingsSyncManager.SettingUpdate>()
        if (buffer.remaining() < 4) return updates
        val count = buffer.int
        for (i in 0 until count) {
            if (buffer.remaining() < 4) break
            val keyLen = buffer.int
            if (buffer.remaining() < keyLen) break
            val kb = ByteArray(keyLen)
            buffer.get(kb)
            val key = String(kb, StandardCharsets.UTF_8)
            if (buffer.remaining() < 5) break
            val type = buffer.get()
            val valLen = buffer.int
            if (buffer.remaining() < valLen + 8) break
            val vb = ByteArray(valLen)
            buffer.get(vb)
            val value = deserializeValue(type, vb)
            val ts = buffer.long
            if (value != null) updates.add(BaseSettingsSyncManager.SettingUpdate(key, value, ts))
        }
        return updates
    }

    private fun deserializeValue(type: Byte, b: ByteArray): Any? {
        return try {
            when (type) {
                1.toByte() -> b[0] == 1.toByte()
                2.toByte() -> String(b, StandardCharsets.UTF_8)
                3.toByte() -> ByteBuffer.wrap(b).int
                4.toByte() -> ByteBuffer.wrap(b).long
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
