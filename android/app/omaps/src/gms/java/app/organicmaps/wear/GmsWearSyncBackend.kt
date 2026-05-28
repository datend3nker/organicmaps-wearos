package app.organicmaps.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class GmsWearSyncBackend : IWearSyncBackend {
    companion object {
        private const val PATH_STOP_NAVIGATION = "/navigation/stop"
        private const val PATH_SEARCH_QUERY = "/search/query"
        private const val PATH_SEARCH_SELECT = "/search/select"
        private const val PATH_SEARCH_HISTORY_REQUEST = "/search/history/request"
        private const val PATH_MAP_TILE_REQUEST = "/map/tile/request"
        private const val PATH_PING = "/ping"
        private const val PATH_PREFERENCES_REQUEST = "/preferences/request"
        private const val PATH_START_NAVIGATION_REQUEST = "/navigation/start/request"
        private const val PATH_POI_SHOW = "/poi/show"
        private const val PATH_MAP_DOWNLOAD_CANCEL = "/map/download/cancel"
    }

    override fun stopNavigation(context: Context) {
        sendMessage(context, PATH_STOP_NAVIGATION, byteArrayOf())
    }

    override fun cancelMapSync(context: Context) {
        sendMessage(context, PATH_MAP_DOWNLOAD_CANCEL, byteArrayOf())
    }

    override fun search(context: Context, query: String) {
        sendMessage(context, PATH_SEARCH_QUERY, query.toByteArray(StandardCharsets.UTF_8))
    }

    override fun requestSearchHistory(context: Context) {
        sendMessage(context, PATH_SEARCH_HISTORY_REQUEST, byteArrayOf())
    }

    override fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + 4 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.putInt(routerType)
        buffer.put(nameBytes)

        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            destinationName = result.name,
            routerType = routerType
        ))

        sendMessage(context, PATH_SEARCH_SELECT, buffer.array())
    }

    override fun requestMapTile(context: Context, requestId: Long, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, scale: Int, routerType: Int, poiCategoriesMask: Int) {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES + (Double.SIZE_BYTES * 4) + Int.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES)
        buffer.putLong(requestId)
        buffer.putDouble(minLat)
        buffer.putDouble(minLon)
        buffer.putDouble(maxLat)
        buffer.putDouble(maxLon)
        buffer.putInt(scale)
        buffer.putInt(routerType)
        buffer.putInt(poiCategoriesMask)
        sendMessage(context, PATH_MAP_TILE_REQUEST, buffer.array())
    }

    override fun sendPing(context: Context) {
        sendMessage(context, PATH_PING, byteArrayOf())
    }

    override fun syncPreferences(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val watchLocalMode = prefs.getBoolean("watchLocalMode", false)
        val standaloneMode = prefs.getBoolean("disconnectFromPhone", false)
        val autoDownload = prefs.getBoolean("autoDownloadRouteMaps", true)
        val downloadMode = prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY") ?: "BLUETOOTH_ONLY"
        val backend = prefs.getString("pref_wear_os_backend", "GMS")
        val poiMask = prefs.getInt("poiCategoriesMask", 0x3F)
        
        val is3dEnabled = prefs.getBoolean("pref_3d", true)
        val is3dBuildingsEnabled = prefs.getBoolean("pref_3d_buildings", true)
        val isAutoZoomEnabled = prefs.getBoolean("pref_auto_zoom", true)
        val mUnits = prefs.getInt("pref_munits", 0)
        val mapStyle = prefs.getString("pref_map_style", "default")

        val transitEnabled = prefs.getBoolean("transit_enabled", false)
        val bikingEnabled = prefs.getBoolean("biking_enabled", false)
        val hikingEnabled = prefs.getBoolean("hiking_enabled", false)
        val isolinesEnabled = prefs.getBoolean("isolines_enabled", false)

        val avoidTolls = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Toll)
        val avoidMotorways = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Motorway)
        val avoidFerries = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Ferry)
        val avoidUnpaved = app.organicmaps.sdk.routing.RoutingOptions.hasOption(app.organicmaps.sdk.settings.RoadType.Dirty)

        android.util.Log.d("GmsWearSync", "Syncing preferences to phone: mapEnabled=$mapEnabled, watchLocal=$watchLocalMode")

        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/preferences/watch")
        val map = putDataMapReq.dataMap
        map.putBoolean("mapEnabled", mapEnabled)
        map.putBoolean("watchLocalMode", watchLocalMode)
        map.putBoolean("standaloneMode", standaloneMode)
        map.putBoolean("autoDownloadRouteMaps", autoDownload)
        map.putString("mapDownloadMode", downloadMode)
        map.putString("backend", backend ?: "GMS")
        map.putInt("poiCategoriesMask", poiMask)
        map.putBoolean("is3dEnabled", is3dEnabled)
        map.putBoolean("is3dBuildingsEnabled", is3dBuildingsEnabled)
        map.putBoolean("isAutoZoomEnabled", isAutoZoomEnabled)
        map.putInt("measurementUnits", mUnits)
        map.putString("mapStyle", mapStyle ?: "default")
        map.putBoolean("transitEnabled", transitEnabled)
        map.putBoolean("bikingEnabled", bikingEnabled)
        map.putBoolean("hikingEnabled", hikingEnabled)
        map.putBoolean("isolinesEnabled", isolinesEnabled)
        map.putBoolean("avoidTolls", avoidTolls)
        map.putBoolean("avoidMotorways", avoidMotorways)
        map.putBoolean("avoidFerries", avoidFerries)
        map.putBoolean("avoidUnpaved", avoidUnpaved)
        map.putLong("timestamp", System.currentTimeMillis())
        
        val putDataReq = putDataMapReq.asPutDataRequest()
        putDataReq.setUrgent() // Critical for immediate sync
        Wearable.getDataClient(context).putDataItem(putDataReq)
    }

    override fun requestPreferences(context: Context) {
        sendMessage(context, PATH_PREFERENCES_REQUEST, byteArrayOf())
    }

    override fun startNavigation(context: Context) {
        sendMessage(context, PATH_START_NAVIGATION_REQUEST, byteArrayOf())
    }

    override fun showOnPhone(context: Context, result: SearchResultItem) {
        val nameBytes = result.name.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + 8 + nameBytes.size)
        buffer.putDouble(result.lat)
        buffer.putDouble(result.lon)
        buffer.put(nameBytes)
        sendMessage(context, PATH_POI_SHOW, buffer.array())
    }

    override fun checkConnection(context: Context, callback: (Boolean) -> Unit) {
        val nodeClient = Wearable.getNodeClient(context)
        nodeClient.connectedNodes.addOnCompleteListener { task ->
            if (task.isSuccessful && task.result != null) {
                callback(task.result.isNotEmpty())
            } else {
                callback(false)
            }
        }
    }

    private fun sendMessage(context: Context, path: String, data: ByteArray) {
        val messageClient = Wearable.getMessageClient(context)
        val nodeClient = Wearable.getNodeClient(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    messageClient.sendMessage(node.id, path, data).await()
                }
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
