package app.organicmaps.wear

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.sync.SyncSettingsRegistry
import kotlin.time.Duration.Companion.seconds

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
    val featureType: String = "",
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
    val isPhoneConnected: Boolean = false,
    val isConnecting: Boolean = false,
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
    val trackRecordingDistance: Double = 0.0, // metres
    val trackRecordingDuration: Double = 0.0, // seconds
    val bookmarkCategories: List<BookmarkCategoryItem> = emptyList(),
    val missingMapId: String? = null,
    val phoneDownloadedMaps: Set<String> = emptySet()
) {
    val isEffectivelyStandalone: Boolean
        get() = standaloneMode || !isPhoneConnected
}

sealed class UiEvent {
    object OpenMap : UiEvent()
    object OpenMapManager : UiEvent()
    data class ShowToast(val message: String, val duration: Int = Toast.LENGTH_SHORT) : UiEvent()
}

data class NavigationInstructions(
    val distToTurn: String,
    val nextStreet: String,
    val carDirection: Int,
    val pedestrianDirection: Int,
    val exitNum: Int
)

data class RoutingStatus(
    val isActive: Boolean,
    val isNavigating: Boolean,
    val isRouteBuilding: Boolean,
    val isRouteReady: Boolean,
    val isRouteBuilt: Boolean,
    val routePoints: List<Pair<Double, Double>>
)

data class MapStatus(
    val isMapUnlocked: Boolean,
    val mapStyle: String,
    val isAmbient: Boolean
)

data class ConnectionStatus(
    val isPhoneConnected: Boolean,
    val standaloneMode: Boolean,
    val watchLocalMode: Boolean
)

data class TrackRecordingStatus(
    val isRecording: Boolean,
    val startTime: Long,
    val distance: Double = 0.0, // metres
    val duration: Double = 0.0  // seconds
)

data class BookmarkCategoryItem(
    val id: Long,
    val name: String,
    val isVisible: Boolean,
    val bookmarksCount: Int,
    val tracksCount: Int,
    val isSyncing: Boolean = false,
)

object NavigationStateHolder {
    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var pendingStopJob: Job? = null

    // Decomposed flows for better performance
    val locationFlow = state.map { 
        Triple(it.lat, it.lon, it.bearing)
    }.distinctUntilChanged()
    
    val navigationInstructionsFlow = state.map { 
        NavigationInstructions(it.distToTurn, it.nextStreet, it.carDirection, it.pedestrianDirection, it.exitNum)
    }.distinctUntilChanged()

    val routingStatusFlow = state.map {
        RoutingStatus(it.isActive, it.isNavigating, it.isRouteBuilding, it.isRouteReady, it.isRouteBuilt, it.routePoints)
    }.distinctUntilChanged()

    val mapStatusFlow = state.map {
        MapStatus(it.isMapUnlocked, it.mapStyle, it.isAmbient)
    }.distinctUntilChanged()

    val connectionStatusFlow = state.map {
        ConnectionStatus(it.isPhoneConnected, it.standaloneMode, it.watchLocalMode)
    }.distinctUntilChanged()

    val trackRecordingFlow = state.map {
        TrackRecordingStatus(it.isTrackRecording, it.trackRecordingStartTime, it.trackRecordingDistance, it.trackRecordingDuration)
    }.distinctUntilChanged()

    var lastMessageTimestamp: Long = 0L
        private set

    // When the user cancels navigation on the watch, the phone keeps streaming a few in-flight
    // /navigation/status frames with active=true before it processes the stop. Those would
    // resurrect the nav UI (and even relaunch the activity), forcing the user to press X twice.
    // While now < navCancelGuardUntil, NavStatusHandler drops active=true frames so a single X
    // sticks. Cleared as soon as an inactive frame (phone confirmed stop) arrives.
    @Volatile
    var navCancelGuardUntil: Long = 0L

    fun emitEvent(event: UiEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    fun update(newState: NavigationState, force: Boolean = false) {
        val oldState = _state.value
        
        // Skip if nothing important changed to prevent ripple recompositions
        if (!force && oldState == newState) return

        // LOGGING FOR DEBUGGING MAP UNLOCK
        if (newState.isMapUnlocked != oldState.isMapUnlocked) {
            Log.d("NavState", "Map Unlocked transition: ${oldState.isMapUnlocked} -> ${newState.isMapUnlocked}")
        }

        // GRACE PERIOD LOGIC
        if (!force && oldState.isActive && !newState.isActive) {
            // Navigation trying to stop - start grace period
            if (pendingStopJob == null) {
                pendingStopJob = scope.launch {
                    delay(8.seconds) // 8 second grace period for stability
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

    fun updateSettings(updater: (NavigationState) -> NavigationState) {
        val now = System.currentTimeMillis()
        update(updater(_state.value).copy(lastSettingsInteractionTime = now))
    }



    fun updateTimestamp(timestamp: Long) {
        lastMessageTimestamp = timestamp
    }

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val isWatch = true
        
        fun getSafeInt(key: String, default: Int): Int {
            val value = prefs.all[key] ?: return default
            return when (value) {
                is Int -> value
                is Long -> value.toInt()
                is String -> value.toIntOrNull() ?: default
                else -> default
            }
        }

        fun getSafeBoolean(key: String, default: Boolean): Boolean {
            val value = prefs.all[key] ?: return default
            return when (value) {
                is Boolean -> value
                is String -> value.toBoolean()
                else -> default
            }
        }

        fun getK(canonical: String) = SyncSettingsRegistry.getLocalKey(canonical, isWatch)

        update { current ->
            current.copy(
                mapEnabled = getSafeBoolean(getK(WearProtocol.SETTING_MAP_ENABLED), true),
                watchLocalMode = getSafeBoolean(getK(WearProtocol.SETTING_WATCH_LOCAL_MODE), false),
                standaloneMode = getSafeBoolean(getK(WearProtocol.SETTING_STANDALONE_MODE), false),
                autoDownloadRouteMaps = getSafeBoolean(getK(WearProtocol.SETTING_AUTO_DOWNLOAD), true),
                mapDownloadMode = prefs.getString(getK(WearProtocol.SETTING_MAP_DOWNLOAD_MODE), "PHONE_SYNC") ?: "PHONE_SYNC",
                backend = prefs.getString(getK(WearProtocol.SETTING_BACKEND), "GMS") ?: "GMS",
                locationSource = prefs.getString(getK(WearProtocol.SETTING_LOCATION_SOURCE), "AUTO") ?: "AUTO",
                measurementUnits = getSafeInt(getK(WearProtocol.SETTING_MEASUREMENT_UNITS), 0),
                mapStyle = prefs.getString(getK(WearProtocol.SETTING_MAP_STYLE), "default") ?: "default",
                is3dEnabled = getSafeBoolean(getK(WearProtocol.SETTING_3D_ENABLED), true),
                is3dBuildingsEnabled = getSafeBoolean(getK(WearProtocol.SETTING_3D_BUILDINGS_ENABLED), true),
                isAutoZoomEnabled = getSafeBoolean(getK(WearProtocol.SETTING_AUTO_ZOOM_ENABLED), true),
                avoidTolls = getSafeBoolean(getK(WearProtocol.SETTING_AVOID_TOLLS), false),
                avoidMotorways = getSafeBoolean(getK(WearProtocol.SETTING_AVOID_MOTORWAYS), false),
                avoidFerries = getSafeBoolean(getK(WearProtocol.SETTING_AVOID_FERRIES), false),
                avoidUnpaved = getSafeBoolean(getK(WearProtocol.SETTING_AVOID_UNPAVED), false),
                transitEnabled = getSafeBoolean(getK(WearProtocol.SETTING_TRANSIT_ENABLED), false),
                isolinesEnabled = getSafeBoolean(getK(WearProtocol.SETTING_ISOLINES_ENABLED), false),
                bikingEnabled = getSafeBoolean(getK(WearProtocol.SETTING_BIKING_ENABLED), false),
                hikingEnabled = getSafeBoolean(getK(WearProtocol.SETTING_HIKING_ENABLED), false),
                allowMobileData = getSafeBoolean("pref_mobile_data", false),
                forceGuiButtons = getSafeBoolean("pref_force_gui_buttons", false),
                showOnLockScreen = getSafeBoolean("pref_show_on_lock_screen", true),
                syncNotificationsEnabled = getSafeBoolean(getK(WearProtocol.SETTING_SYNC_NOTIFICATIONS_ENABLED), true)
            )
        }
    }
}
