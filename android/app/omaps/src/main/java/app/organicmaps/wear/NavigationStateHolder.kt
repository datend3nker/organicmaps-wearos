package app.organicmaps.wear

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
    val type: Int = 2 // Default to TYPE_RESULT
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
    val isPhoneConnected: Boolean = false,
    val poiCategoriesMask: Int = 0x3F, // Default to all POIs
    val mapDownloadMode: String = "BLUETOOTH_ONLY",
    val autoDownloadRouteMaps: Boolean = true,
    val backend: String = "GMS",
    val manualCenterLat: Double = 0.0,
    val manualCenterLon: Double = 0.0,
    val manualViewSpan: Float = 0.003f,
    val isExploreMode: Boolean = false,
    val isNavigating: Boolean = false,
    val routeBuildProgress: Int = 0,
    val isRouteBuilding: Boolean = false,
    val isRouteReady: Boolean = false,
    val destinationName: String = "",
    val isAmbient: Boolean = false,
    val lastSettingsInteractionTime: Long = 0L
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
                isExploreMode = oldState.isExploreMode // Preserve explore mode during grace period
            )
            return
        }
        
        if (force || newState.isActive) {
            // Navigation is active or forced, cancel any pending stop
            pendingStopJob?.cancel()
            pendingStopJob = null
        }

        // Preserve isExploreMode when updating from phone if it wasn't explicitly changed
        val finalState = if (!force && newState.isActive && oldState.isActive) {
            newState.copy(isExploreMode = oldState.isExploreMode)
        } else {
            newState
        }

        _state.value = finalState
    }

    fun update(updater: (NavigationState) -> NavigationState) {
        update(updater(_state.value))
    }

    fun updateTimestamp(timestamp: Long) {
        lastMessageTimestamp = timestamp
    }
}
