package app.organicmaps.wear

import android.content.Context
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.search.SearchEngine
import app.organicmaps.sdk.search.SearchListener
import app.organicmaps.sdk.search.SearchResult

object WearCommandService {
    private var backend: IWearSyncBackend? = null

    @Synchronized
    private fun getBackend(context: Context): IWearSyncBackend {
        if (backend == null) {
            initBackend(context)
        }
        return backend!!
    }

    @Synchronized
    fun initBackend(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
        
        // Properly dispose of previous backend if it exists
        backend = null 
        
        backend = if (selectedBackend == "BLUETOOTH" || BuildConfig.FLAVOR == "oss") {
            BluetoothWearSyncBackend()
        } else {
            try {
                Class.forName("app.organicmaps.wear.GmsWearSyncBackend")
                    .getDeclaredConstructor().newInstance() as IWearSyncBackend
            } catch (e: Exception) {
                BluetoothWearSyncBackend()
            }
        }
        
        // Immediate connection attempt and pref sync
        syncPreferences(context)
    }

    fun stopNavigation(context: Context) = getBackend(context).stopNavigation(context)
    
    fun search(context: Context, query: String) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            ensureSearchInitialized(context)
            val hasLocation = navState.lat != 0.0
            SearchEngine.INSTANCE.search(
                context, query, false, System.currentTimeMillis(), 
                hasLocation, navState.lat, navState.lon
            )
        } else {
            getBackend(context).search(context, query)
        }
    }

    private var isSearchInitialized = false
    private fun ensureSearchInitialized(context: Context) {
        if (isSearchInitialized) return
        val searchEngine = SearchEngine.INSTANCE
        searchEngine.addListener(object : SearchListener {
            override fun onResultsUpdate(results: Array<out SearchResult>, timestamp: Long) {
                val mapped = results.map {
                    SearchResultItem(
                        name = it.getTitle(context) ?: "",
                        description = it.description?.localizedFeatureType ?: it.description?.region ?: "",
                        lat = it.lat,
                        lon = it.lon,
                        type = it.type
                    )
                }
                NavigationStateHolder.update { it.copy(searchResults = mapped, isSearching = true) }
            }

            override fun onResultsEnd(timestamp: Long) {
                NavigationStateHolder.update { it.copy(isSearching = false) }
            }
        })
        isSearchInitialized = true
    }

    fun requestSearchHistory(context: Context) = getBackend(context).requestSearchHistory(context)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            NavigationStateHolder.update(navState.copy(
                destinationName = result.name,
                routerType = routerType,
                isRouteBuilding = true,
                isRouteReady = false,
                isActive = true // Transition to map/nav view
            ))
            
            val wearApp = context.applicationContext as WearApplication
            val routing = RoutingController.get()
            val router = Router.values().getOrNull(routerType) ?: Router.Vehicle
            
            // Force POI type for fallback coordinates to ensure native engine uses them immediately
            // and doesn't wait for internal native LocationState fix (unblocks NO_POSITION error)
            val myPos = wearApp.organicMaps.locationHelper.savedLocation?.let { 
                MapObject.createMapObject(MapObject.POI, "My Location", "", it.latitude, it.longitude)
            }
            val startPoint = myPos
            
            val endPoint = MapObject.createMapObject(MapObject.SEARCH, result.name, result.description, result.lat, result.lon)
            
            // CRITICAL: Use prepare to initialize everything correctly on the native side
            routing.prepare(startPoint, endPoint, router)
        } else {
            getBackend(context).selectSearchResult(context, result, routerType)
        }
    }
    fun requestMapTile(context: Context, requestId: Long, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, routerType: Int, poiCategoriesMask: Int) =
        getBackend(context).requestMapTile(context, requestId, minLat, minLon, maxLat, maxLon, routerType, poiCategoriesMask)
    fun sendPing(context: Context) = getBackend(context).sendPing(context)
    fun syncPreferences(context: Context) = getBackend(context).syncPreferences(context)
    fun requestPreferences(context: Context) = getBackend(context).requestPreferences(context)
    fun checkConnection(context: Context, callback: (Boolean) -> Unit) = getBackend(context).checkConnection(context, callback)
    fun startNavigation(context: Context) = getBackend(context).startNavigation(context)
    fun showOnPhone(context: Context, result: SearchResultItem) = getBackend(context).showOnPhone(context, result)
}
