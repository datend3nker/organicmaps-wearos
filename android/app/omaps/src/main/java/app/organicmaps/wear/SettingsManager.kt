package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import app.organicmaps.sdk.sync.BaseSettingsSyncManager
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.sync.SyncSettingsRegistry
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SettingsManager {
    private const val TAG = "SettingsManager"

    fun applySettings(
        context: Context,
        timestamp: Long,
        mapEnabled: Boolean,
        watchLocalMode: Boolean,
        standaloneMode: Boolean,
        autoDownload: Boolean,
        mapDownloadMode: String,
        backend: String,
        poiMask: Int,
        is3dEnabled: Boolean,
        is3dBuildingsEnabled: Boolean,
        isAutoZoomEnabled: Boolean,
        measurementUnits: Int,
        mapStyle: String,
        avoidTolls: Boolean,
        avoidMotorways: Boolean,
        avoidFerries: Boolean,
        avoidUnpaved: Boolean,
        transitEnabled: Boolean,
        bikingEnabled: Boolean,
        hikingEnabled: Boolean,
        isolinesEnabled: Boolean,
        locationSource: String,
        isTrackRecording: Boolean = false,
        trackRecordingStartTime: Long = 0L
    ) {
        val updates = listOf(
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_MAP_ENABLED, mapEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_WATCH_LOCAL_MODE, watchLocalMode, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_STANDALONE_MODE, standaloneMode, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_AUTO_DOWNLOAD, autoDownload, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_MAP_DOWNLOAD_MODE, mapDownloadMode, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_BACKEND, backend, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_POI_MASK, poiMask, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_3D_ENABLED, is3dEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_3D_BUILDINGS_ENABLED, is3dBuildingsEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_AUTO_ZOOM_ENABLED, isAutoZoomEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_MEASUREMENT_UNITS, measurementUnits, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_MAP_STYLE, mapStyle, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_AVOID_TOLLS, avoidTolls, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_AVOID_MOTORWAYS, avoidMotorways, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_AVOID_FERRIES, avoidFerries, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_AVOID_UNPAVED, avoidUnpaved, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_TRANSIT_ENABLED, transitEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_BIKING_ENABLED, bikingEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_HIKING_ENABLED, hikingEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_ISOLINES_ENABLED, isolinesEnabled, timestamp),
            BaseSettingsSyncManager.SettingUpdate(WearProtocol.SETTING_LOCATION_SOURCE, locationSource, timestamp)
        )
        SettingsSyncManager.getInstance(context).applyRemoteUpdates(updates)
    }

    private fun getSafeInt(prefs: SharedPreferences, key: String, default: Int): Int {
        val value = prefs.all[key] ?: return default
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun getSafeBoolean(prefs: SharedPreferences, key: String, default: Boolean): Boolean {
        val value = prefs.all[key] ?: return default
        return when (value) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> default
        }
    }

    fun applyCurrentSettings(context: Context) {
        Log.d(TAG, "DEBUG_WEAR: applyCurrentSettings - FIX_V3_ACTIVE")
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val currentState = NavigationStateHolder.state.value

        val isWatch = true
        val key3d = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_3D_ENABLED, isWatch)
        val key3dBld = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_3D_BUILDINGS_ENABLED, isWatch)
        val keyAutoZoom = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_AUTO_ZOOM_ENABLED, isWatch)
        val keyTransit = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_TRANSIT_ENABLED, isWatch)
        val keyBiking = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_BIKING_ENABLED, isWatch)
        val keyHiking = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_HIKING_ENABLED, isWatch)
        val keyIsolines = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_ISOLINES_ENABLED, isWatch)
        val keyAvoidTolls = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_AVOID_TOLLS, isWatch)
        val keyAvoidMotorways = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_AVOID_MOTORWAYS, isWatch)
        val keyAvoidFerries = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_AVOID_FERRIES, isWatch)
        val keyAvoidUnpaved = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_AVOID_UNPAVED, isWatch)
        
        val keyLocalMode = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_WATCH_LOCAL_MODE, isWatch)
        val keyStandalone = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_STANDALONE_MODE, isWatch)
        val keyMapEnabled = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_MAP_ENABLED, isWatch)
        val keyPoiMask = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_POI_MASK, isWatch)
        val keyDlMode = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_MAP_DOWNLOAD_MODE, isWatch)
        val keyAutoDl = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_AUTO_DOWNLOAD, isWatch)
        val keyBackend = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_BACKEND, isWatch)
        val keyUnits = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_MEASUREMENT_UNITS, isWatch)
        val keyStyle = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_MAP_STYLE, isWatch)
        val keyLocSource = SyncSettingsRegistry.getLocalKey(WearProtocol.SETTING_LOCATION_SOURCE, isWatch)

        val is3dEnabled = getSafeBoolean(prefs, key3d, true)
        val is3dBuildingsEnabled = getSafeBoolean(prefs, key3dBld, true)
        val isAutoZoomEnabled = getSafeBoolean(prefs, keyAutoZoom, true)
        val transitEnabled = getSafeBoolean(prefs, keyTransit, false)
        val bikingEnabled = getSafeBoolean(prefs, keyBiking, false)
        val hikingEnabled = getSafeBoolean(prefs, keyHiking, false)
        val isolinesEnabled = getSafeBoolean(prefs, keyIsolines, false)
        val avoidTolls = getSafeBoolean(prefs, keyAvoidTolls, false)
        val avoidMotorways = getSafeBoolean(prefs, keyAvoidMotorways, false)
        val avoidFerries = getSafeBoolean(prefs, keyAvoidFerries, false)
        val avoidUnpaved = getSafeBoolean(prefs, keyAvoidUnpaved, false)
        
        val watchLocalMode = getSafeBoolean(prefs, keyLocalMode, false)
        val standaloneMode = getSafeBoolean(prefs, keyStandalone, false)
        val mapEnabled = getSafeBoolean(prefs, keyMapEnabled, false)
        val poiMask = getSafeInt(prefs, keyPoiMask, 0x3F)
        val mapDownloadMode = prefs.getString(keyDlMode, "PHONE_SYNC") ?: "PHONE_SYNC"
        val autoDownload = getSafeBoolean(prefs, keyAutoDl, true)
        val backend = prefs.getString(keyBackend, "GMS") ?: "GMS"
        val measurementUnits = getSafeInt(prefs, keyUnits, 0)
        val mapStyle = prefs.getString(keyStyle, "default") ?: "default"
        val locationSource = prefs.getString(keyLocSource, "AUTO") ?: "AUTO"
        val timestamp = prefs.getLong("last_sync_timestamp", 0L)

        val isForcedOffline = getSafeBoolean(prefs, "forceWatchLocalMode", false)
        val finalOfflineState = isForcedOffline || watchLocalMode
        val finalMapEnabled = standaloneMode || mapEnabled

        context.sendBroadcast(Intent("app.organicmaps.wear.SETTINGS_CHANGED").apply {
            putExtra("source", "remote")
        })

        if ((context.applicationContext as WearApplication).isFullyInitialized) {
            // Apply native settings on UI thread - FIX: native core is not thread-safe
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    Framework.nativeSet3dMode(is3dEnabled, is3dBuildingsEnabled)
                    Framework.nativeSetAutoZoomEnabled(isAutoZoomEnabled)
                    Framework.nativeSetTransitSchemeEnabled(transitEnabled)
                    Framework.nativeSetCyclingLayerEnabled(bikingEnabled)
                    Framework.nativeSetHikingLayerEnabled(hikingEnabled)
                    Framework.nativeSetIsolinesLayerEnabled(isolinesEnabled)
                    
                    if (avoidTolls) RoutingOptions.addOption(RoadType.Toll) else RoutingOptions.removeOption(RoadType.Toll)
                    if (avoidMotorways) RoutingOptions.addOption(RoadType.Motorway) else RoutingOptions.removeOption(RoadType.Motorway)
                    if (avoidFerries) RoutingOptions.addOption(RoadType.Ferry) else RoutingOptions.removeOption(RoadType.Ferry)
                    if (avoidUnpaved) RoutingOptions.addOption(RoadType.Dirty) else RoutingOptions.removeOption(RoadType.Dirty)
                } catch (_: Throwable) {}
            }
        }

        NavigationStateHolder.update(currentState.copy(
            mapEnabled = finalMapEnabled,
            watchLocalMode = finalOfflineState,
            standaloneMode = standaloneMode,
            poiCategoriesMask = poiMask,
            mapDownloadMode = mapDownloadMode,
            autoDownloadRouteMaps = autoDownload,
            backend = backend,
            measurementUnits = measurementUnits,
            mapStyle = mapStyle,
            avoidTolls = avoidTolls,
            avoidMotorways = avoidMotorways,
            avoidFerries = avoidFerries,
            avoidUnpaved = avoidUnpaved,
            locationSource = locationSource,
            lastSettingsInteractionTime = timestamp
        ))
    }
}
