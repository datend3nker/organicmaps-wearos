package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MapTile(val requestId: Long, val features: ByteArray)

object MapTileStateHolder {
    private val _mapTile = MutableStateFlow<MapTile?>(null)
    val mapTile = _mapTile.asStateFlow()

    fun update(requestId: Long, features: ByteArray) {
        _mapTile.value = MapTile(requestId, features)
    }
}
