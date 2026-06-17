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
                sInstance ?: SettingsSyncManager(context.applicationContext).also { sInstance = it }
            }
        }
    }

    override fun getCanonicalToLocalMapping(): Map<String, String> = CANONICAL_TO_LOCAL

    // The phone is the settings authority: on a tie (or a fresh install where everything is 0/0)
    // the watch adopts the phone's value so the two converge. Without this, equal-version updates
    // were ignored forever and a freshly installed watch never reconciled with the phone.
    override fun prefersRemoteOnEqual(): Boolean = true

    override fun getMainPrefs(): SharedPreferences {
        return context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
    }

    override fun onSettingsApplied() {
        SettingsManager.applyCurrentSettings(context)
        context.sendBroadcast(Intent("app.organicmaps.wear.SETTINGS_CHANGED"))
    }
}
