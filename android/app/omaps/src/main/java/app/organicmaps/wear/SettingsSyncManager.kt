package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import app.organicmaps.sdk.sync.SyncSettingsRegistry

class SettingsSyncManager private constructor(context: Context) : BaseSettingsSyncManager(context) {

    companion object {
        private val CANONICAL_TO_LOCAL = SyncSettingsRegistry.getMapping(isWatch = true)

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
        return context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
    }

    override fun onSettingsApplied() {
        SettingsManager.applyCurrentSettings(context)
        context.sendBroadcast(Intent("app.organicmaps.wear.SETTINGS_CHANGED"))
    }
}
