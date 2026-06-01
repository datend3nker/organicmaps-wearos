package app.organicmaps.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchResultItem(
    val name: String,
    val description: String,
    val lat: Double,
    val lon: Double,
    val type: Int = 2, // Default to TYPE_RESULT
    val openingHours: String = "",
    val website: String = "",
    val phone: String = "",
    val address: String = "",
    val cuisine: String = "",
    val operator: String = "",
    val brand: String = "",
    val stars: String = "",
    val distance: String = "",
    val featureType: String = ""
)

data class NavigationState(
    val distToTurn: String = "",
    val nextStreet: String = "",
    val carDirection: Int = 0,
    val pedestrianDirection: Int = 0,
    val exitNum: Int = 0,
    val isActive: Boolean = false,
    val isSearching: Boolean = false,
    val speedMps: Double = -1.0,
    val speedLimitMps: Double = -1.0,
    val bearing: Float = -1f,
    val completionPercent: Double = 0.0,
    val distToTarget: String = "",
    val eta: Int = 0, // Seconds
    val searchResults: List<SearchResultItem> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val mapEnabled: Boolean = false,
    val watchLocalMode: Boolean = false,
    val standaloneMode: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val turnLat: Double = 0.0,
    val turnLon: Double = 0.0,
    val distToTurnMeters: Double = -1.0,
    val routerType: Int = 0, // 0: Vehicle, 1: Pedestrian, 2: Bicycle, 3: Transit
    val routePoints: List<Pair<Double, Double>> = emptyList(),
    val openMapManager: Boolean = false,
    val openMap: Boolean = false,
    val isPhoneConnected: Boolean = false,
    val poiCategoriesMask: Int = 0x7FFFFFFF, // Default to all POIs
    val mapDownloadMode: String = "PHONE_SYNC", // PHONE_SYNC, DIRECT_DOWNLOAD
    val autoDownloadRouteMaps: Boolean = true,
    val backend: String = "GMS",
    val manualCenterLat: Double = 0.0,
    val manualCenterLon: Double = 0.0,
    val manualViewSpan: Float = 0.003f,
    val isMapUnlocked: Boolean = false,
    val isMapUnlockedBeforeNav: Boolean = false,
    val isNavigating: Boolean = false,
    val routeBuildProgress: Int = 0,
    val isRouteBuilding: Boolean = false,
    val isRouteReady: Boolean = false,
    val destinationName: String = "",
    val isAmbient: Boolean = false,
    val lastSettingsInteractionTime: Long = 0L,
    val lastRouteError: Int = 0,
    val isRecalculating: Boolean = false,
    val measurementUnits: Int = 0, // 0: Metric, 1: Imperial
    val mapStyle: String = "default", // default, night, auto, nav_auto
    val is3dEnabled: Boolean = true,
    val is3dBuildingsEnabled: Boolean = true,
    val isAutoZoomEnabled: Boolean = true,
    // Routing Options
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    // Map Layers
    val transitEnabled: Boolean = false,
    val bikingEnabled: Boolean = false,
    val hikingEnabled: Boolean = false,
    val isolinesEnabled: Boolean = false,
    val allowMobileData: Boolean = false,
    val hasPhysicalButtons: Boolean = false,
    val forceGuiButtons: Boolean = false,
    val locationSource: String = "AUTO", // AUTO, PHONE_ONLY
    val isRouteBuilt: Boolean = false,
    val showOnLockScreen: Boolean = true,
    val lastFixTime: Long = 0L,
    val syncNotificationsEnabled: Boolean = true,
    val isTrackRecording: Boolean = false,
    val trackRecordingStartTime: Long = 0L,
    val bookmarkCategories: List<BookmarkCategoryItem> = emptyList(),
    val missingMapId: String? = null
) {
    val isEffectivelyStandalone: Boolean
        get() = standaloneMode || !isPhoneConnected
}

data class BookmarkCategoryItem(
    val id: Long,
    val name: String,
    val isVisible: Boolean,
    val bookmarksCount: Int,
    val tracksCount: Int,
    val isSyncing: Boolean = false
)

object NavigationStateHolder {
    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var pendingStopJob: Job? = null

    // Decomposed flows for better performance
    val navInfo = state.map { 
        Triple(it.distToTurn, it.nextStreet, it.carDirection to it.pedestrianDirection to it.exitNum)
    }.distinctUntilChanged()
    
    val location = state.map { 
        Triple(it.lat, it.lon, it.bearing)
    }.distinctUntilChanged()
    
    val navigationActive = state.map { it.isActive }.distinctUntilChanged()
    val phoneConnected = state.map { it.isPhoneConnected }.distinctUntilChanged()

    var lastMessageTimestamp: Long = 0L
        private set

    fun update(newState: NavigationState, force: Boolean = false) {
        val oldState = _state.value
        
        // Skip if nothing important changed to prevent ripple recompositions
        if (!force && oldState == newState) return

        // LOGGING FOR DEBUGGING MAP UNLOCK
        if (newState.isMapUnlocked != oldState.isMapUnlocked) {
            android.util.Log.d("NavState", "Map Unlocked transition: ${oldState.isMapUnlocked} -> ${newState.isMapUnlocked}")
        }

        // GRACE PERIOD LOGIC
        if (!force && oldState.isActive && !newState.isActive) {
            // Navigation trying to stop - start grace period
            if (pendingStopJob == null) {
                pendingStopJob = scope.launch {
                    delay(8000) // 8 second grace period for stability
                    _state.value = newState
                    pendingStopJob = null
                }
            }
            // Keep the active state for now, but update other data (lat/lon etc)
            _state.value = newState.copy(
                isActive = true, 
                isNavigating = true,
                isMapUnlocked = oldState.isMapUnlocked,
                manualCenterLat = oldState.manualCenterLat,
                manualCenterLon = oldState.manualCenterLon,
                manualViewSpan = oldState.manualViewSpan
            )
            return
        }
        
        if (force || newState.isActive) {
            // Navigation is active or forced, cancel any pending stop
            pendingStopJob?.cancel()
            pendingStopJob = null
        }

        // AUTO-RESET MAP STATE ON NAVIGATION TRANSITIONS
        var finalState = newState
        if (newState.isActive && !oldState.isActive) {
            // Navigation or Preview starting, remember if we were unlocked
            finalState = finalState.copy(isMapUnlockedBeforeNav = oldState.isMapUnlocked, isMapUnlocked = false)
        } else if (newState.isNavigating && !oldState.isNavigating) {
            // Navigation starting from preview, force lock
            finalState = finalState.copy(isMapUnlocked = false)
        } else if (!newState.isActive && oldState.isActive) {
            // Navigation stopping, restore state
            finalState = finalState.copy(isMapUnlocked = oldState.isMapUnlockedBeforeNav)
        }

        _state.value = finalState
    }

    fun update(updater: (NavigationState) -> NavigationState) {
        update(updater(_state.value))
    }

    fun updateTimestamp(timestamp: Long) {
        lastMessageTimestamp = timestamp
    }

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        update { current ->
            current.copy(
                mapEnabled = prefs.getBoolean("mapEnabled", true),
                watchLocalMode = prefs.getBoolean("watchLocalMode", false),
                standaloneMode = prefs.getBoolean("disconnectFromPhone", false),
                autoDownloadRouteMaps = prefs.getBoolean("pref_wear_os_auto_download_route_maps", true),
                mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC",
                backend = prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS",
                locationSource = prefs.getString("locationSource", "AUTO") ?: "AUTO",
                measurementUnits = prefs.getInt("pref_wear_os_munits", 0),
                mapStyle = prefs.getString("pref_wear_os_map_style", "default") ?: "default",
                is3dEnabled = prefs.getBoolean("pref_wear_os_3d", true),
                is3dBuildingsEnabled = prefs.getBoolean("pref_wear_os_3d_buildings", true),
                isAutoZoomEnabled = prefs.getBoolean("pref_wear_os_auto_zoom", true),
                avoidTolls = prefs.getBoolean("pref_wear_os_avoid_tolls", false),
                avoidMotorways = prefs.getBoolean("pref_wear_os_avoid_motorways", false),
                avoidFerries = prefs.getBoolean("pref_wear_os_avoid_ferries", false),
                avoidUnpaved = prefs.getBoolean("pref_wear_os_avoid_unpaved", false),
                transitEnabled = prefs.getBoolean("pref_wear_os_transit", false),
                isolinesEnabled = prefs.getBoolean("pref_wear_os_isolines", false),
                bikingEnabled = prefs.getBoolean("pref_wear_os_biking", false),
                hikingEnabled = prefs.getBoolean("pref_wear_os_hiking", false),
                allowMobileData = prefs.getBoolean("pref_mobile_data", false),
                forceGuiButtons = prefs.getBoolean("pref_force_gui_buttons", false),
                showOnLockScreen = prefs.getBoolean("pref_show_on_lock_screen", true),
                syncNotificationsEnabled = prefs.getBoolean("pref_sync_notifications", true)
            )
        }
    }
}
