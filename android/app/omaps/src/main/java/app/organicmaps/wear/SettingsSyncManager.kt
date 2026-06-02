package app.organicmaps.wear

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object SettingsSyncManager {
    private const val TAG = "SettingsSyncManager"
    private const val PREFS_NAME = "wear_settings_sync_v2"
    private const val KEY_PREFIX_TIMESTAMP = "ts_"
    private const val KEY_PREFIX_DIRTY = "dirty_"

    var isApplyingRemoteUpdates = false
        private set

    data class SettingUpdate(val key: String, val value: Any, val timestamp: Long)

    private val CANONICAL_TO_LOCAL = mapOf(
        "mapEnabled" to "mapEnabled",
        "watchLocalMode" to "watchLocalMode",
        "standaloneMode" to "disconnectFromPhone",
        "autoDownload" to "pref_wear_os_auto_download_route_maps",
        "mapDownloadMode" to "mapDownloadMode",
        "backend" to "pref_wear_os_backend",
        "poiMask" to "poiCategoriesMask",
        "is3dEnabled" to "pref_wear_os_3d",
        "is3dBuildingsEnabled" to "pref_wear_os_3d_buildings",
        "isAutoZoomEnabled" to "pref_wear_os_auto_zoom",
        "measurementUnits" to "pref_wear_os_munits",
        "mapStyle" to "pref_wear_os_map_style",
        "avoidTolls" to "pref_wear_os_avoid_tolls",
        "avoidMotorways" to "pref_wear_os_avoid_motorways",
        "avoidFerries" to "pref_wear_os_avoid_ferries",
        "avoidUnpaved" to "pref_wear_os_avoid_unpaved",
        "transitEnabled" to "pref_wear_os_transit",
        "bikingEnabled" to "pref_wear_os_biking",
        "hikingEnabled" to "pref_wear_os_hiking",
        "isolinesEnabled" to "pref_wear_os_isolines",
        "locationSource" to "locationSource",
        "syncNotificationsEnabled" to "pref_sync_notifications"
    )

    private val LOCAL_TO_CANONICAL = CANONICAL_TO_LOCAL.entries.associate { (k, v) -> v to k }

    private fun getSyncPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getMainPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun onSettingChanged(context: Context, localKey: String, value: Any, isUserAction: Boolean) {
        val canonicalKey = LOCAL_TO_CANONICAL[localKey] ?: return

        val now = System.currentTimeMillis()
        val syncPrefs = getSyncPrefs(context)
        
        syncPrefs.edit().putLong(KEY_PREFIX_TIMESTAMP + canonicalKey, now).apply()
        
        if (isUserAction) {
            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Setting $canonicalKey ($localKey) manually CHANGED by user to: $value")
            syncPrefs.edit().putBoolean(KEY_PREFIX_DIRTY + canonicalKey, true).apply()
        }
    }

    @Synchronized
    fun getDirtyUpdates(context: Context): List<SettingUpdate> {
        val updates = mutableListOf<SettingUpdate>()
        val syncPrefs = getSyncPrefs(context)
        val allSync = syncPrefs.all
        val mainPrefs = getMainPrefs(context)
        val allValues = mainPrefs.all

        for ((prefKey, isDirty) in allSync) {
            if (prefKey.startsWith(KEY_PREFIX_DIRTY) && isDirty == true) {
                val canonicalKey = prefKey.substring(KEY_PREFIX_DIRTY.length)
                val localKey = CANONICAL_TO_LOCAL[canonicalKey] ?: continue
                val ts = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0L)
                val value = allValues[localKey]
                if (value != null) {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Found DIRTY setting to buffer: $canonicalKey = $value (ts=$ts)")
                    updates.add(SettingUpdate(canonicalKey, value, ts))
                }
            }
        }
        return updates
    }

    @Synchronized
    fun getAllSettings(context: Context): List<SettingUpdate> {
        val updates = mutableListOf<SettingUpdate>()
        val mainPrefs = getMainPrefs(context)
        val syncPrefs = getSyncPrefs(context)
        val allValues = mainPrefs.all
        
        for ((localKey, value) in allValues) {
            val canonicalKey = LOCAL_TO_CANONICAL[localKey] ?: continue
            val ts = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + canonicalKey, 0L)
            updates.add(SettingUpdate(canonicalKey, value ?: continue, ts))
        }
        return updates
    }

    @Synchronized
    fun markAsSynced(context: Context, updates: List<SettingUpdate>) {
        val editor = getSyncPrefs(context).edit()
        for (update in updates) {
            editor.remove(KEY_PREFIX_DIRTY + update.key)
        }
        editor.apply()
    }

    @Synchronized
    fun applyRemoteUpdates(context: Context, updates: List<SettingUpdate>): Boolean {
        var changed = false
        isApplyingRemoteUpdates = true
        try {
            val mainPrefs = getMainPrefs(context)
            val syncPrefs = getSyncPrefs(context)
            
            val mainEditor = mainPrefs.edit()
            val syncEditor = syncPrefs.edit()

            for (remote in updates) {
                val localTs = syncPrefs.getLong(KEY_PREFIX_TIMESTAMP + remote.key, 0L)
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
                
                // Re-apply settings to core
                SettingsManager.applyCurrentSettings(context)
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
