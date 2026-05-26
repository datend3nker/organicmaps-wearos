package app.organicmaps.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

import kotlin.math.*

import java.nio.charset.StandardCharsets

data class MapTileKey(val x: Int, val y: Int, val zoom: Int)

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

data class MapFeaturePath(val path: Path, val name: String, val labelPos: Offset? = null, val color: Int = 0, val width: Float = 0f, val casingColor: Int = 0, val casingWidth: Float = 0f, val layer: Int = 0, val priority: Int = 0)
data class MapFeaturePoint(val x: Float, val y: Float, val name: String, val iconName: String = "", val priority: Int = 0)

data class ParsedMapTile(
    val pathsByType: Map<Int, List<MapFeaturePath>>,
    val pointsByType: Map<Int, List<MapFeaturePoint>>,
    val key: MapTileKey,
    val mercatorX: Double,
    val mercatorY: Double,
    val mercatorSpan: Double
)

data class MapTile(val requestId: Long, val features: ByteArray, val key: MapTileKey? = null)

object MapTileStateHolder {
    private const val MAX_CACHE_SIZE = 120
    
    // Simple LRU cache for tiles based on grid coordinates
    private val cache = object : LinkedHashMap<MapTileKey, ParsedMapTile>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MapTileKey, ParsedMapTile>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val _mapTileChannel = Channel<MapTile>(Channel.BUFFERED)
    val mapTileFlow = _mapTileChannel.receiveAsFlow()

    private val _cachedTilesFlow = MutableStateFlow<List<ParsedMapTile>>(emptyList())
    val cachedTilesFlow: StateFlow<List<ParsedMapTile>> = _cachedTilesFlow.asStateFlow()

    fun getCachedTile(lat: Double, lon: Double, zoom: Int): ParsedMapTile? {
        val x = Mercator.lonToTileX(lon, zoom)
        val y = Mercator.latToTileY(lat, zoom)
        return getCachedTileByKey(MapTileKey(x, y, zoom))
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
        _mapTileChannel.trySend(MapTile(requestId, features, key))
    }

    fun updateCache(key: MapTileKey, parsedTile: ParsedMapTile) {
        synchronized(cache) {
            cache[key] = parsedTile
            
            // PERFORMANCE: Prune tiles that are too far from the current zoom or center
            val currentZoom = key.zoom
            val toRemove = mutableListOf<MapTileKey>()
            
            for (cachedKey in cache.keys) {
                // Remove tiles from other zoom levels immediately if they are not the current one
                // (keeps only the most relevant LOD)
                if (abs(cachedKey.zoom - currentZoom) > 1) {
                    toRemove.add(cachedKey)
                }
                
                // Remove tiles that are more than 4 tiles away in any direction (approx 2 screens)
                if (abs(cachedKey.x - key.x) > 4 || abs(cachedKey.y - key.y) > 4) {
                    toRemove.add(cachedKey)
                }
            }
            
            toRemove.forEach { cache.remove(it) }
            _cachedTilesFlow.value = cache.values.toList()
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
            _cachedTilesFlow.value = emptyList()
        }
    }

    fun parseTile(features: ByteArray, key: MapTileKey, width: Float, height: Float): ParsedMapTile {
        // In Mercator space, the full map (360 degrees) is 1.0 unit.
        // A tile at zoom Z has a span of 1.0 / (2^Z).
        val mercSpan = 1.0 / (1 shl key.zoom)
        
        // Mercator coordinates for the tile
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
        val pathsByType = mutableMapOf<Int, MutableList<MapFeaturePath>>()
        val pointsByType = mutableMapOf<Int, MutableList<MapFeaturePoint>>()

        // Temporary storage for merged paths to fix stitching
        val mergedPaths = mutableMapOf<String, Path>()
        val lastPoints = mutableMapOf<String, Offset>()

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 1) break
            val type = buffer.get().toInt() and 0xFF
            
            if (buffer.remaining() < 4) break
            val priorityValue = buffer.int

            if (buffer.remaining() < 17) break
            var roadColor = buffer.int
            var roadWidthValue = buffer.float
            var roadCasingColor = buffer.int
            var roadCasingWidthValue = buffer.float
            var layerValue = (buffer.get().toInt() and 0xFF) - 128

            val iconName = if (type >= 100) {
                if (buffer.remaining() < 1) break
                val iconLen = buffer.get().toInt() and 0xFF
                if (iconLen > 0 && buffer.remaining() >= iconLen) {
                    val bytes = ByteArray(iconLen)
                    buffer.get(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                } else ""
            } else ""

            if (buffer.remaining() < 2) break
            // Read name
            val nameLen = buffer.short.toInt() and 0xFFFF
            val name = if (nameLen > 0 && buffer.remaining() >= nameLen) {
                val bytes = ByteArray(nameLen)
                buffer.get(bytes)
                String(bytes, StandardCharsets.UTF_8)
            } else ""

            if (buffer.remaining() < 4) break
            val count = buffer.getInt()
            if (count < 0 || buffer.remaining().toLong() < count.toLong() * 16L) break
            
            if (type >= 100) {
                val list = pointsByType.getOrPut(type) { mutableListOf() }
                for (i in 0 until count) {
                    val lon = buffer.getDouble()
                    val lat = buffer.getDouble()
                    
                    val x = ((Mercator.lonToX(lon) - (actualMercCenterX - actualMercSpan / 2.0)) / actualMercSpan * width).toFloat()
                    val y = ((Mercator.latToY(lat) - (actualMercCenterY - actualMercSpan / 2.0)) / actualMercSpan * height).toFloat()
                    list.add(MapFeaturePoint(x, y, name, iconName, priorityValue))
                }
            } else {
                val isRoad = type in 4..8 || type == 1
                // For roads in the SAME tile, we try to merge them to ensure StrokeJoin works.
                // We use a looser key (just type) for unnamed roads to group them, 
                // but keep named roads separate if they have different names.
                val mergeKey = if (isRoad) {
                    if (name.isNotEmpty()) "$type-$name-$roadColor" else "$type-unnamed-$roadColor"
                } else null
                
                val mapPath = if (mergeKey != null) mergedPaths.getOrPut(mergeKey) { Path() } else Path()
                
                var labelPos: Offset? = null
                for (i in 0 until count) {
                    val lon = buffer.getDouble()
                    val lat = buffer.getDouble()
                    
                    // COORDINATE STABILIZATION: Use double precision until the very end
                    val px = (Mercator.lonToX(lon) - (actualMercCenterX - actualMercSpan / 2.0)) / actualMercSpan * width
                    val py = (Mercator.latToY(lat) - (actualMercCenterY - actualMercSpan / 2.0)) / actualMercSpan * height
                    
                    val x = px.toFloat()
                    val y = py.toFloat()
                    
                    if (type == 2 || type == 3 || type == 9) {
                        // Triangle based area
                        if (i % 3 == 0) mapPath.moveTo(x, y)
                        else mapPath.lineTo(x, y)
                        if (i % 3 == 2) mapPath.close()
                        
                        // Set label pos to first triangle's first point as approximate center
                        if (labelPos == null) labelPos = Offset(x, y)
                    } else {
                        if (i == 0) {
                            val lastP = if (mergeKey != null) lastPoints[mergeKey] else null
                            // Relaxed matching for stitching (1.0 units)
                            if (lastP != null && abs(lastP.x - x) < 1.0f && abs(lastP.y - y) < 1.0f) {
                                // Continue from last point to enable StrokeJoin and fix stitching
                            } else {
                                mapPath.moveTo(x, y)
                            }
                        } else {
                            mapPath.lineTo(x, y)
                        }
                        if (i == count / 2) labelPos = Offset(x, y)
                    }
                    if (i == count - 1) {
                        if (mergeKey != null) lastPoints[mergeKey] = Offset(x, y)
                    }
                }
                
                if (mergeKey == null || !pathsByType.getOrPut(type) { mutableListOf() }.any { it.name == name && name.isNotEmpty() }) {
                    // For unnamed roads, we keep adding to the merged path but only add the path OBJECT once
                    if (mergeKey != null && name.isEmpty() && pathsByType.getOrPut(type) { mutableListOf() }.any { it.name.isEmpty() }) {
                        // Already added the "unnamed" path for this type
                    } else {
                        pathsByType.getOrPut(type) { mutableListOf() }.add(MapFeaturePath(mapPath, name, labelPos, roadColor, roadWidthValue, roadCasingColor, roadCasingWidthValue, layerValue, priorityValue))
                    }
                }
            }
        }
        return ParsedMapTile(pathsByType, pointsByType, key, actualMercCenterX, actualMercCenterY, actualMercSpan)
    }
}
