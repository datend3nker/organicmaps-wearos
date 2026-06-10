package app.organicmaps.sdk.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

abstract class BaseSettingsSyncManager(protected val context: Context) {
    protected val syncPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val TAG = "BaseSettingsSync"
        private const val PREFS_NAME = "wear_settings_sync_v2"
        private const val KEY_PREFIX_TIMESTAMP = "ts_"
        private const val KEY_PREFIX_DIRTY = "dirty_"
    }

    @get:Synchronized
    var isApplyingRemoteUpdates: Boolean = false
        protected set

    data class SettingUpdate(
        @JvmField val key: String,
        @JvmField val value: Any,
        @JvmField val timestamp: Long
    )

    abstract fun getCanonicalToLocalMapping(): Map<String, String>
    abstract fun getMainPrefs(): SharedPreferences
    abstract fun onSettingsApplied()

    @Synchronized
    fun onSettingChanged(localKey: String, value: Any?, isUserAction: Boolean) {
        val canonicalKey = getLocalToCanonicalMapping()[localKey] ?: return

        val now = System.currentTimeMillis()
        syncPrefs.edit().apply {
            putLong(KEY_PREFIX_TIMESTAMP + canonicalKey, now)
            if (isUserAction) {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Setting $canonicalKey ($localKey) manually CHANGED by user to: $value")
                putBoolean(KEY_PREFIX_DIRTY + canonicalKey, true)
            }
            apply()
        }
    }

    private var localToCanonicalCached: Map<String, String>? = null
    private fun getLocalToCanonicalMapping(): Map<String, String> {
        return localToCanonicalCached ?: getCanonicalToLocalMapping().entries.associate { (k, v) -> v to k }.also { localToCanonicalCached = it }
    }

    @Synchronized
    fun getDirtyUpdates(): List<SettingUpdate> {
        val updates = mutableListOf<SettingUpdate>()
        val allSync = syncPrefs.all
        val mainPrefs = getMainPrefs()
        val allMain = mainPrefs.all

        for (prefKey in allSync.keys) {
            if (prefKey.startsWith(KEY_PREFIX_DIRTY) && (allSync[prefKey] == true)) {
                val canonicalKey = prefKey.substring(KEY_PREFIX_DIRTY.length)
                val localKey = getCanonicalToLocalMapping()[canonicalKey]
                if (localKey != null) {
                    val ts = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0)
                    allMain[localKey]?.let { value ->
                        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Found DIRTY setting to buffer: $canonicalKey = $value (ts=$ts)")
                        updates.add(SettingUpdate(canonicalKey, value, ts))
                    }
                }
            }
        }
        return updates
    }

    @Synchronized
    fun getAllSettings(): List<SettingUpdate> {
        val updates = mutableListOf<SettingUpdate>()
        val mainPrefs = getMainPrefs()
        val allValues = mainPrefs.all

        for ((localKey, value) in allValues) {
            val canonicalKey = getLocalToCanonicalMapping()[localKey]
            if ((canonicalKey != null) && (value != null)) {
                val ts = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0)
                updates.add(SettingUpdate(canonicalKey, value, ts))
            }
        }
        return updates
    }

    @Synchronized
    fun markAsSynced(updates: List<SettingUpdate>) {
        syncPrefs.edit().apply {
            for (update in updates) {
                remove(KEY_PREFIX_DIRTY + update.key)
            }
            apply()
        }
    }

    @Synchronized
    fun applyRemoteUpdates(updates: List<SettingUpdate>): Boolean {
        var changed = false
        isApplyingRemoteUpdates = true
        try {
            val mainPrefs = getMainPrefs()
            val mainEditor = mainPrefs.edit()
            val syncEditor = syncPrefs.edit()

            for (remote in updates) {
                val localTs = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + remote.key, 0)
                if (remote.timestamp > localTs) {
                    val localKey = getCanonicalToLocalMapping()[remote.key]
                    if (localKey != null) {
                        Log.d(TAG, "DEBUG_WEAR_PIPELINE: APPLYING REMOTE update for ${remote.key} -> ${remote.value} (remote=${remote.timestamp}, local=$localTs)")
                        applyValue(mainEditor, localKey, remote.value)
                        syncEditor.putLong(KEY_PREFIX_TIMESTAMP + remote.key, remote.timestamp)
                        syncEditor.remove(KEY_PREFIX_DIRTY + remote.key)
                        changed = true
                    }
                } else {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Ignoring stale remote update for ${remote.key} (remote=${remote.timestamp}, local=$localTs)")
                }
            }

            if (changed) {
                mainEditor.apply()
                syncEditor.apply()
                onSettingsApplied()
            }
        } finally {
            isApplyingRemoteUpdates = false
        }
        return changed
    }

    private fun applyValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }
}
