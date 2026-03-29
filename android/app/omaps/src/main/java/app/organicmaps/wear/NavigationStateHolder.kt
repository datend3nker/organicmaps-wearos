package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NavigationState(
    val distToTurn: String = "",
    val nextStreet: String = "",
    val carDirection: Int = 0,
    val pedestrianDirection: Int = 0,
    val exitNum: Int = 0,
    val isActive: Boolean = false,
    val speedMps: Double = -1.0,
    val completionPercent: Double = 0.0,
    val distToTarget: String = ""
)

object NavigationStateHolder {
    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()

    fun update(newState: NavigationState) {
        _state.value = newState
    }
}
