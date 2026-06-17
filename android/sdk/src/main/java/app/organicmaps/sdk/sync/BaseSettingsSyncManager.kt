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
        private const val KEY_PREFIX_VERSION = "v_"

        // The transport backend is negotiated via the dedicated BACKEND_SWITCH control message, not
        // here. Syncing it as an LWW setting too made the watch's runtime backend and persisted pref
        // diverge and the two sides fight forever ("Ignoring stale remote update for backend"), which
        // also blocked switching to Bluetooth. Keep it out of the LWW pipeline entirely.
        private val NON_LWW_KEYS = setOf(WearProtocol.SETTING_BACKEND)
    }

    /**
     * When versions and timestamps tie, defer to the remote so two independently-versioned devices
     * converge instead of ignoring each other's equal updates forever — and so a fresh device adopts
     * the peer's settings on the very first sync (everything at version 0 / timestamp 0). The watch
     * overrides this to true (the phone is the authority); the phone keeps its own value on ties.
     */
    protected open fun prefersRemoteOnEqual(): Boolean = false

    @get:Synchronized
    var isApplyingRemoteUpdates: Boolean = false
        protected set

    data class SettingUpdate(
        @JvmField val key: String,
        @JvmField val value: Any,
        @JvmField val timestamp: Long,
        @JvmField val version: Long = 0
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
                val currentVersion = syncPrefs.getLong(KEY_PREFIX_VERSION + canonicalKey, 0)
                val newVersion = currentVersion + 1
                putLong(KEY_PREFIX_VERSION + canonicalKey, newVersion)
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Setting $canonicalKey ($localKey) manually CHANGED by user to: $value. New version: $newVersion")
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
                if (canonicalKey in NON_LWW_KEYS) continue
                val localKey = getCanonicalToLocalMapping()[canonicalKey]
                if (localKey != null) {
                    val ts = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0)
                    val ver = syncPrefs.getLong(KEY_PREFIX_VERSION + canonicalKey, 0)
                    allMain[localKey]?.let { value ->
                        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Found DIRTY setting to buffer: $canonicalKey = $value (ts=$ts, ver=$ver)")
                        updates.add(SettingUpdate(canonicalKey, value, ts, ver))
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
            if (canonicalKey != null && canonicalKey in NON_LWW_KEYS) continue
            if ((canonicalKey != null) && (value != null)) {
                val ts = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0)
                val ver = syncPrefs.getLong(KEY_PREFIX_VERSION + canonicalKey, 0)
                updates.add(SettingUpdate(canonicalKey, value, ts, ver))
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
                if (remote.key in NON_LWW_KEYS) continue

                val localTs = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + remote.key, 0)
                val localVer = syncPrefs.getLong(KEY_PREFIX_VERSION + remote.key, 0)

                // Defer to the remote on an exact tie (and on a fresh device where everything is
                // 0/0) so the two sides converge instead of ignoring each other forever.
                val tieDeferToRemote = prefersRemoteOnEqual() &&
                        remote.version >= localVer && remote.timestamp >= localTs
                val isNewer = remote.version > localVer ||
                        (remote.version == localVer && remote.timestamp > localTs) ||
                        tieDeferToRemote

                if (isNewer) {
                    val localKey = getCanonicalToLocalMapping()[remote.key]
                    if (localKey != null) {
                        // Only count as a real change (and re-apply native settings) when the value
                        // actually differs — otherwise a 0/0 tie would re-fire onSettingsApplied every
                        // sync and churn the UI.
                        if (mainPrefs.all[localKey] != remote.value) {
                            Log.d(TAG, "DEBUG_WEAR_PIPELINE: APPLYING REMOTE update for ${remote.key} -> ${remote.value} (remoteVer=${remote.version}, localVer=$localVer, remoteTs=${remote.timestamp}, localTs=$localTs)")
                            applyValue(mainEditor, localKey, remote.value)
                            changed = true
                        }
                        syncEditor.putLong(KEY_PREFIX_TIMESTAMP + remote.key, maxOf(remote.timestamp, localTs))
                        syncEditor.putLong(KEY_PREFIX_VERSION + remote.key, maxOf(remote.version, localVer))
                        syncEditor.remove(KEY_PREFIX_DIRTY + remote.key)
                    }
                } else {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Ignoring stale remote update for ${remote.key} (remoteVer=${remote.version}, localVer=$localVer, remoteTs=${remote.timestamp}, localTs=$localTs)")
                }
            }

            if (changed) {
                mainEditor.commit()
                syncEditor.commit()
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
