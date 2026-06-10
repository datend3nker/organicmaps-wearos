package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import app.organicmaps.sdk.sync.SyncSettingsRegistry

class SettingsSyncManager private constructor(context: Context) : BaseSettingsSyncManager(context) {

    companion object {
        private val CANONICAL_TO_LOCAL = SyncSettingsRegistry.getMapping(isWatch = false)

        @Volatile
        private var sInstance: SettingsSyncManager? = null

        @JvmStatic
        fun getInstance(context: Context): SettingsSyncManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: SettingsSyncManager(context).also { sInstance = it }
            }
        }
    }

    override fun getCanonicalToLocalMapping(): Map<String, String> = CANONICAL_TO_LOCAL

    override fun getMainPrefs(): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    override fun onSettingsApplied() {
        context.sendBroadcast(Intent("app.organicmaps.wear.SETTINGS_CHANGED"))
    }
}
