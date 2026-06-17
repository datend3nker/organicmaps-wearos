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
        repeat(count) {
            if (buffer.remaining() < 4) return@repeat
            val keyLen = buffer.int
            if (buffer.remaining() < keyLen) return@repeat
            val kb = ByteArray(keyLen)
            buffer.get(kb)
            val key = String(kb, StandardCharsets.UTF_8)
            if (buffer.remaining() < 5) return@repeat
            val type = buffer.get()
            val valLen = buffer.int
            // The phone serializes timestamp(8) + version(8) after the value
            // (WearProtocolDataConverter.encodePreferenceUpdates). Both must be read or the
            // buffer desyncs (dropping every setting after the first) and version stays 0,
            // which makes applyRemoteUpdates treat the update as stale and ignore it.
            if (buffer.remaining() < valLen + 16) return@repeat
            val vb = ByteArray(valLen)
            buffer.get(vb)
            val value = deserializeValue(type, vb)
            val ts = buffer.long
            val version = buffer.long
            if (value != null) updates.add(BaseSettingsSyncManager.SettingUpdate(key, value, ts, version))
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
        } catch (_: Exception) {
            null
        }
    }
}
