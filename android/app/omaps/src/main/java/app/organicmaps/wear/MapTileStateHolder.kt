package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap

data class MapTileKey(val x: Int, val y: Int, val zoom: Int)

data class StreamedMapTile(
    val key: MapTileKey,
    val features: ByteArray
)

object MapTileStateHolder {
    private const val MAX_CACHE_SIZE = 15
    
    // We use a simple LRU cache for tiles
    private val cache = object : LinkedHashMap<MapTileKey, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MapTileKey, ByteArray>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val _tilesUpdateSignal = MutableStateFlow(0)
    val tilesUpdateSignal = _tilesUpdateSignal.asStateFlow()

    fun getTile(x: Int, y: Int, zoom: Int): ByteArray? {
        synchronized(cache) {
            return cache[MapTileKey(x, y, zoom)]
        }
    }

    fun update(x: Int, y: Int, zoom: Int, features: ByteArray) {
        synchronized(cache) {
            cache[MapTileKey(x, y, zoom)] = features
        }
        _tilesUpdateSignal.value += 1
    }
}
