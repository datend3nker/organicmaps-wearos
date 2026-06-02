package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

class SettingsSyncManager private constructor(context: Context) {
    private val mSyncPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mContext: Context = context.applicationContext

    @get:Synchronized
    var isApplyingRemoteUpdates: Boolean = false
        private set

    companion object {
        private const val TAG = "SettingsSyncManager"
        private const val PREFS_NAME = "wear_settings_sync_v2"
        private const val KEY_PREFIX_TIMESTAMP = "ts_"
        private const val KEY_PREFIX_DIRTY = "dirty_"

        private val CANONICAL_TO_LOCAL: MutableMap<String, String> = mutableMapOf()
        private val LOCAL_TO_CANONICAL: MutableMap<String, String> = mutableMapOf()

        init {
            addMapping("mapEnabled", "pref_wear_os_map_enabled")
            addMapping("watchLocalMode", "pref_wear_os_watch_local_mode")
            addMapping("standaloneMode", "pref_wear_os_standalone_mode")
            addMapping("autoDownload", "pref_wear_os_auto_download_route_maps")
            addMapping("mapDownloadMode", "pref_wear_os_map_download_mode")
            addMapping("backend", "pref_wear_os_backend")
            addMapping("poiMask", "poiCategoriesMask")
            addMapping("is3dEnabled", "pref_wear_os_3d")
            addMapping("is3dBuildingsEnabled", "pref_wear_os_3d_buildings")
            addMapping("isAutoZoomEnabled", "pref_wear_os_auto_zoom")
            addMapping("measurementUnits", "pref_wear_os_munits")
            addMapping("mapStyle", "pref_wear_os_map_style")
            addMapping("avoidTolls", "pref_wear_os_avoid_tolls")
            addMapping("avoidMotorways", "pref_wear_os_avoid_motorways")
            addMapping("avoidFerries", "pref_wear_os_avoid_ferries")
            addMapping("avoidUnpaved", "pref_wear_os_avoid_unpaved")
            addMapping("transitEnabled", "pref_wear_os_transit")
            addMapping("bikingEnabled", "pref_wear_os_biking")
            addMapping("hikingEnabled", "pref_wear_os_hiking")
            addMapping("isolinesEnabled", "pref_wear_os_isolines")
            addMapping("locationSource", "locationSource")
            addMapping("syncNotificationsEnabled", "pref_sync_notifications")
        }

        private fun addMapping(canonical: String, local: String) {
            CANONICAL_TO_LOCAL[canonical] = local
            LOCAL_TO_CANONICAL[local] = canonical
        }

        @Volatile
        @Suppress("StaticFieldLeak")
        private var sInstance: SettingsSyncManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): SettingsSyncManager {
            return sInstance ?: SettingsSyncManager(context).also { sInstance = it }
        }
    }

    data class SettingUpdate(
        @JvmField val key: String, // Canonical key
        @JvmField val value: Any,
        @JvmField val timestamp: Long,
    )

    @Synchronized
    fun onSettingChanged(localKey: String, value: Any?, isUserAction: Boolean) {
        val canonicalKey = LOCAL_TO_CANONICAL[localKey] ?: return

        val now = System.currentTimeMillis()
        mSyncPrefs.edit().apply {
            putLong(KEY_PREFIX_TIMESTAMP + canonicalKey, now)
            if (isUserAction) {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Setting $canonicalKey ($localKey) manually CHANGED by user to: $value")
                putBoolean(KEY_PREFIX_DIRTY + canonicalKey, true)
            }
            apply()
        }
    }

    @Synchronized
    fun getDirtyUpdates(): List<SettingUpdate> {
        val updates = mutableListOf<SettingUpdate>()
        val allSync = mSyncPrefs.all
        val mainPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        val allMain = mainPrefs.all

        for (prefKey in allSync.keys) {
            if (prefKey.startsWith(KEY_PREFIX_DIRTY) && (allSync[prefKey] == true)) {
                val canonicalKey = prefKey.substring(KEY_PREFIX_DIRTY.length)
                val localKey = CANONICAL_TO_LOCAL[canonicalKey]
                if (localKey != null) {
                    val ts = mSyncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0)
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
        val mainPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        val allValues = mainPrefs.all

        for ((localKey, value) in allValues) {
            val canonicalKey = LOCAL_TO_CANONICAL[localKey]
            if ((canonicalKey != null) && (value != null)) {
                val ts = mSyncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0)
                updates.add(SettingUpdate(canonicalKey, value, ts))
            }
        }
        return updates
    }

    @Synchronized
    fun markAsSynced(updates: List<SettingUpdate>) {
        mSyncPrefs.edit().apply {
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
            val mainPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
            val oldBackend = mainPrefs.getString("pref_wear_os_backend", "GMS")

            val mainEditor = mainPrefs.edit()
            val syncEditor = mSyncPrefs.edit()

            for (remote in updates) {
                val localTs = mSyncPrefs.getLong(KEY_PREFIX_TIMESTAMP + remote.key, 0)
                if (remote.timestamp > localTs) {
                    val localKey = CANONICAL_TO_LOCAL[remote.key]
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

                val newBackend = mainPrefs.getString("pref_wear_os_backend", "GMS")
                if (newBackend != oldBackend) {
                    WearSyncService.initSyncLayer(mContext)
                }
                mContext.sendBroadcast(Intent("app.organicmaps.wear.SETTINGS_CHANGED"))
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
