package app.organicmaps.sdk.sync

/**
 * Central registry for all settings that are synchronized between the Phone and Watch apps.
 */
object SyncSettingsRegistry {
    private val SETTINGS = listOf(
        SyncedSetting(WearProtocol.SETTING_MAP_ENABLED, "pref_wear_os_map_enabled", "mapEnabled"),
        SyncedSetting(WearProtocol.SETTING_WATCH_LOCAL_MODE, "pref_wear_os_watch_local_mode", "watchLocalMode"),
        SyncedSetting(WearProtocol.SETTING_STANDALONE_MODE, "pref_wear_os_standalone_mode", "disconnectFromPhone"),
        SyncedSetting(WearProtocol.SETTING_AUTO_DOWNLOAD, "pref_wear_os_auto_download_route_maps", "pref_wear_os_auto_download_route_maps"),
        SyncedSetting(WearProtocol.SETTING_MAP_DOWNLOAD_MODE, "pref_wear_os_map_download_mode", "mapDownloadMode"),
        SyncedSetting(WearProtocol.SETTING_BACKEND, "pref_wear_os_backend", "pref_wear_os_backend"),
        SyncedSetting(WearProtocol.SETTING_POI_MASK, "poiCategoriesMask", "poiCategoriesMask"),
        SyncedSetting(WearProtocol.SETTING_3D_ENABLED, "pref_wear_os_3d", "pref_wear_os_3d"),
        SyncedSetting(WearProtocol.SETTING_3D_BUILDINGS_ENABLED, "pref_wear_os_3d_buildings", "pref_wear_os_3d_buildings"),
        SyncedSetting(WearProtocol.SETTING_AUTO_ZOOM_ENABLED, "pref_wear_os_auto_zoom", "pref_wear_os_auto_zoom"),
        SyncedSetting(WearProtocol.SETTING_MEASUREMENT_UNITS, "pref_wear_os_munits", "pref_wear_os_munits"),
        SyncedSetting(WearProtocol.SETTING_MAP_STYLE, "pref_wear_os_map_style", "pref_wear_os_map_style"),
        SyncedSetting(WearProtocol.SETTING_AVOID_TOLLS, "pref_wear_os_avoid_tolls", "pref_wear_os_avoid_tolls"),
        SyncedSetting(WearProtocol.SETTING_AVOID_MOTORWAYS, "pref_wear_os_avoid_motorways", "pref_wear_os_avoid_motorways"),
        SyncedSetting(WearProtocol.SETTING_AVOID_FERRIES, "pref_wear_os_avoid_ferries", "pref_wear_os_avoid_ferries"),
        SyncedSetting(WearProtocol.SETTING_AVOID_UNPAVED, "pref_wear_os_avoid_unpaved", "pref_wear_os_avoid_unpaved"),
        SyncedSetting(WearProtocol.SETTING_TRANSIT_ENABLED, "pref_wear_os_transit", "pref_wear_os_transit"),
        SyncedSetting(WearProtocol.SETTING_BIKING_ENABLED, "pref_wear_os_biking", "pref_wear_os_biking"),
        SyncedSetting(WearProtocol.SETTING_HIKING_ENABLED, "pref_wear_os_hiking", "pref_wear_os_hiking"),
        SyncedSetting(WearProtocol.SETTING_ISOLINES_ENABLED, "pref_wear_os_isolines", "pref_wear_os_isolines"),
        SyncedSetting(WearProtocol.SETTING_LOCATION_SOURCE, "locationSource", "locationSource"),
        SyncedSetting(WearProtocol.SETTING_SYNC_NOTIFICATIONS_ENABLED, "pref_sync_notifications", "pref_sync_notifications")
    )

    /**
     * Returns a mapping from canonical protocol keys to local preference keys for the current app context.
     *
     * @param isWatch True if the mapping is for the Watch app, false for the Phone app.
     * @return A map of [WearProtocol] keys to local SharedPreferences keys.
     */
    @JvmStatic
    fun getMapping(isWatch: Boolean): Map<String, String> {
        return SETTINGS.associate { it.canonicalKey to if (isWatch) it.watchLocalKey else it.phoneLocalKey }
    }

    /**
     * Returns the local preference key for a given canonical protocol key.
     *
     * @param canonicalKey The canonical key from [WearProtocol].
     * @param isWatch True if the key is for the Watch app, false for the Phone app.
     * @return The local SharedPreferences key.
     */
    @JvmStatic
    fun getLocalKey(canonicalKey: String, isWatch: Boolean): String {
        val setting = SETTINGS.find { it.canonicalKey == canonicalKey }
            ?: throw IllegalArgumentException("Unknown canonical key: $canonicalKey")
        return if (isWatch) setting.watchLocalKey else setting.phoneLocalKey
    }
}
