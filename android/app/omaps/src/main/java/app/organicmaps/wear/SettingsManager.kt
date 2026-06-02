package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType

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
            SettingsSyncManager.SettingUpdate("mapEnabled", mapEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("watchLocalMode", watchLocalMode, timestamp),
            SettingsSyncManager.SettingUpdate("disconnectFromPhone", standaloneMode, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_auto_download_route_maps", autoDownload, timestamp),
            SettingsSyncManager.SettingUpdate("mapDownloadMode", mapDownloadMode, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_backend", backend, timestamp),
            SettingsSyncManager.SettingUpdate("poiCategoriesMask", poiMask, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_3d", is3dEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_3d_buildings", is3dBuildingsEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_auto_zoom", isAutoZoomEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_munits", measurementUnits, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_map_style", mapStyle, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_avoid_tolls", avoidTolls, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_avoid_motorways", avoidMotorways, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_avoid_ferries", avoidFerries, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_avoid_unpaved", avoidUnpaved, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_transit", transitEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_biking", bikingEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_hiking", hikingEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("pref_wear_os_isolines", isolinesEnabled, timestamp),
            SettingsSyncManager.SettingUpdate("locationSource", locationSource, timestamp)
        )
        SettingsSyncManager.applyRemoteUpdates(context, updates)
    }

    fun applyCurrentSettings(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val currentState = NavigationStateHolder.state.value

        val is3dEnabled = prefs.getBoolean("pref_wear_os_3d", true)
        val is3dBuildingsEnabled = prefs.getBoolean("pref_wear_os_3d_buildings", true)
        val isAutoZoomEnabled = prefs.getBoolean("pref_wear_os_auto_zoom", true)
        val transitEnabled = prefs.getBoolean("pref_wear_os_transit", false)
        val bikingEnabled = prefs.getBoolean("pref_wear_os_biking", false)
        val hikingEnabled = prefs.getBoolean("pref_wear_os_hiking", false)
        val isolinesEnabled = prefs.getBoolean("pref_wear_os_isolines", false)
        val avoidTolls = prefs.getBoolean("pref_wear_os_avoid_tolls", false)
        val avoidMotorways = prefs.getBoolean("pref_wear_os_avoid_motorways", false)
        val avoidFerries = prefs.getBoolean("pref_wear_os_avoid_ferries", false)
        val avoidUnpaved = prefs.getBoolean("pref_wear_os_avoid_unpaved", false)
        
        val watchLocalMode = prefs.getBoolean("watchLocalMode", false)
        val standaloneMode = prefs.getBoolean("disconnectFromPhone", false)
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val poiMask = prefs.getInt("poiCategoriesMask", 0x3F)
        val mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
        val autoDownload = prefs.getBoolean("pref_wear_os_auto_download_route_maps", true)
        val backend = prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS"
        val measurementUnits = prefs.getInt("pref_wear_os_munits", 0)
        val mapStyle = prefs.getString("pref_wear_os_map_style", "default") ?: "default"
        val locationSource = prefs.getString("locationSource", "AUTO") ?: "AUTO"
        val timestamp = prefs.getLong("last_sync_timestamp", 0L)

        val isForcedOffline = prefs.getBoolean("forceWatchLocalMode", false)
        val finalOfflineState = isForcedOffline || watchLocalMode
        val finalMapEnabled = standaloneMode || mapEnabled

        context.sendBroadcast(Intent("app.organicmaps.wear.SETTINGS_CHANGED").apply {
            putExtra("source", "remote")
        })

        if ((context.applicationContext as WearApplication).isFullyInitialized) {
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
