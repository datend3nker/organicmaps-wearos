package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap

data class MapTileKey(val lat: Double, val lon: Double)

data class MapTile(val requestId: Long, val features: ByteArray, val anchor: Triple<Double, Double, Double>? = null)

object MapTileStateHolder {
    private const val MAX_CACHE_SIZE = 15
    
    data class CachedTile(val features: ByteArray, val anchor: Triple<Double, Double, Double>)

    // Simple LRU cache for tiles based on coordinates
    private val cache = object : LinkedHashMap<MapTileKey, CachedTile>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MapTileKey, CachedTile>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val _mapTile = MutableStateFlow<MapTile?>(null)
    val mapTile = _mapTile.asStateFlow()

    fun getCachedTile(lat: Double, lon: Double): CachedTile? {
        synchronized(cache) {
            // We use a simple rounding to check for nearby tiles
            val key = MapTileKey(Math.round(lat * 1000.0) / 1000.0, Math.round(lon * 1000.0) / 1000.0)
            return cache[key]
        }
    }

    fun update(requestId: Long, features: ByteArray) {
        _mapTile.value = MapTile(requestId, features)
    }

    fun updateCache(lat: Double, lon: Double, features: ByteArray, anchor: Triple<Double, Double, Double>) {
        synchronized(cache) {
            val key = MapTileKey(Math.round(lat * 1000.0) / 1000.0, Math.round(lon * 1000.0) / 1000.0)
            cache[key] = CachedTile(features, anchor)
        }
    }
}
