package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val lastMessageTimestamp: Long = 0L,
    val poiCategoriesMask: Int = 0x3F, // Default to all POIs
    val manualCenterLat: Double = 0.0,
    val manualCenterLon: Double = 0.0,
    val manualViewSpan: Float = 0.003f,
    val isExploreMode: Boolean = false,
    val isNavigating: Boolean = false
)

object NavigationStateHolder {
    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()

    fun update(newState: NavigationState) {
        _state.value = newState
    }
}
