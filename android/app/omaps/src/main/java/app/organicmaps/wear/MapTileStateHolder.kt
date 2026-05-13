package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

import kotlin.math.*

data class MapTileKey(val x: Int, val y: Int, val zoom: Int = 16)

object Mercator {
    fun lonToX(lon: Double): Double = lon / 360.0 + 0.5
    fun latToY(lat: Double): Double {
        val sinLat = sin(lat * PI / 180.0)
        return 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)
    }
    
    fun lonToTileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    fun latToTileY(lat: Double, zoom: Int): Int = floor((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)).toInt()
    
    fun tileXToLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0
    fun tileYToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y.toDouble() / (1 shl z)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }
}

data class ParsedMapTile(
    val pathsByType: Map<Int, Path>,
    val pointsByType: Map<Int, List<Offset>>,
    val key: MapTileKey,
    val mercatorX: Double,
    val mercatorY: Double,
    val mercatorSpan: Double
)

data class MapTile(val requestId: Long, val features: ByteArray, val key: MapTileKey? = null)

object MapTileStateHolder {
    private const val MAX_CACHE_SIZE = 100
    
    // Simple LRU cache for tiles based on grid coordinates
    private val cache = object : LinkedHashMap<MapTileKey, ParsedMapTile>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MapTileKey, ParsedMapTile>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val _mapTile = MutableStateFlow<MapTile?>(null)
    val mapTile = _mapTile.asStateFlow()

    private val _cachedTilesFlow = MutableStateFlow<List<ParsedMapTile>>(emptyList())
    val cachedTilesFlow: StateFlow<List<ParsedMapTile>> = _cachedTilesFlow.asStateFlow()

    fun getCachedTile(lat: Double, lon: Double): ParsedMapTile? {
        val x = Mercator.lonToTileX(lon, 16)
        val y = Mercator.latToTileY(lat, 16)
        return getCachedTileByKey(MapTileKey(x, y))
    }

    fun getCachedTileByKey(key: MapTileKey): ParsedMapTile? {
        synchronized(cache) {
            return cache[key]
        }
    }

    fun getAllCachedTiles(): List<ParsedMapTile> {
        synchronized(cache) {
            return cache.values.toList()
        }
    }

    fun update(requestId: Long, features: ByteArray, key: MapTileKey? = null) {
        _mapTile.value = MapTile(requestId, features, key)
    }

    fun updateCache(key: MapTileKey, parsedTile: ParsedMapTile) {
        synchronized(cache) {
            cache[key] = parsedTile
            _cachedTilesFlow.value = cache.values.toList()
        }
    }

    fun parseTile(features: ByteArray, key: MapTileKey, width: Float, height: Float): ParsedMapTile {
        val centerLat = Mercator.tileYToLat(key.y, key.zoom)
        val centerLon = Mercator.tileXToLon(key.x, key.zoom)
        
        // At zoom 16, a tile's span is exactly 1/(2^16) in mercator space
        val mercSpan = 1.0 / (1 shl key.zoom)
        val mercCenterX = Mercator.lonToX(centerLon) + mercSpan / 2.0
        val mercCenterY = Mercator.latToY(centerLat) // This is top, but we need center
        
        // Recalculate properly for the tile
        val tileLeftLon = Mercator.tileXToLon(key.x, key.zoom)
        val tileTopLat = Mercator.tileYToLat(key.y, key.zoom)
        val tileRightLon = Mercator.tileXToLon(key.x + 1, key.zoom)
        val tileBottomLat = Mercator.tileYToLat(key.y + 1, key.zoom)
        
        val mercLeftX = Mercator.lonToX(tileLeftLon)
        val mercTopY = Mercator.latToY(tileTopLat)
        val mercRightX = Mercator.lonToX(tileRightLon)
        val mercBottomY = Mercator.latToY(tileBottomLat)
        
        val actualMercCenterX = (mercLeftX + mercRightX) / 2.0
        val actualMercCenterY = (mercTopY + mercBottomY) / 2.0
        val actualMercSpan = mercRightX - mercLeftX

        val buffer = ByteBuffer.wrap(features).order(ByteOrder.LITTLE_ENDIAN)
        val pathsByType = mutableMapOf<Int, Path>()
        val pointsByType = mutableMapOf<Int, MutableList<Offset>>()

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 5) break
            val type = buffer.get().toInt()
            val count = buffer.getInt()
            if (buffer.remaining() < count * 16) break
            
            if (type >= 100) {
                val list = pointsByType.getOrPut(type) { mutableListOf() }
                for (i in 0 until count) {
                    val lon = buffer.getDouble()
                    val lat = buffer.getDouble()
                    
                    val x = ((Mercator.lonToX(lon) - (actualMercCenterX - actualMercSpan / 2.0)) / actualMercSpan * width).toFloat()
                    val y = ((Mercator.latToY(lat) - (actualMercCenterY - actualMercSpan / 2.0)) / actualMercSpan * height).toFloat()
                    list.add(Offset(x, y))
                }
            } else {
                val mapPath = pathsByType.getOrPut(type) { Path() }
                for (i in 0 until count) {
                    val lon = buffer.getDouble()
                    val lat = buffer.getDouble()
                    
                    val x = ((Mercator.lonToX(lon) - (actualMercCenterX - actualMercSpan / 2.0)) / actualMercSpan * width).toFloat()
                    val y = ((Mercator.latToY(lat) - (actualMercCenterY - actualMercSpan / 2.0)) / actualMercSpan * height).toFloat()
                    
                    if (i == 0) mapPath.moveTo(x, y)
                    else mapPath.lineTo(x, y)
                    if (i == count - 1 && type in listOf(2, 3, 9)) mapPath.close()
                }
            }
        }
        return ParsedMapTile(pathsByType, pointsByType, key, actualMercCenterX, actualMercCenterY, actualMercSpan)
    }
}
